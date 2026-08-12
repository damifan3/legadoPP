package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.downloadManager
import splitties.systemservices.notificationManager

/**
 * 下载文件
 */
class DownloadService : BaseService() {
    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = hashMapOf<Long, DownloadInfo>()
    private val completeDownloads = hashSetOf<Long>()
    // 记录正在重试的下载任务，避免重复重试
    private val retryingDownloads = hashSetOf<Long>()
    private var upStateJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            queryState()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName"),
                // 接收镜像 URL 列表，用于下载失败时自动重试
                intent.getStringArrayListExtra("mirrorUrls")
            )

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                if (completeDownloads.contains(id)) {
                    openDownload(id, downloads[id]?.fileName)
                } else {
                    toastOnUi("未完成,下载的文件夹Download")
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                removeDownload(downloadId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 开始下载
     *
     * @param url 下载地址
     * @param fileName 文件名
     * @param mirrorUrls 可选的镜像 URL 列表，用于 GitHub 下载失败时自动重试
     * @param currentMirrorIndex 当前使用的镜像索引，用于重试时追踪进度
     */
    @Synchronized
    private fun startDownload(
        url: String?,
        fileName: String?,
        mirrorUrls: ArrayList<String>? = null,
        currentMirrorIndex: Int = 0
    ) {
        if (url == null || fileName == null) {
            if (downloads.isEmpty()) {
                stopSelf()
            }
            return
        }
        if (downloads.values.any { it.url == url }) {
            toastOnUi("已在下载列表")
            return
        }
        kotlin.runCatching {
            // 指定下载地址
            val request = DownloadManager.Request(Uri.parse(url))
            // 设置通知
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // 设置下载文件保存的路径和文件名
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            // 添加一个下载任务
            val downloadId = downloadManager.enqueue(request)
            downloads[downloadId] =
                DownloadInfo(
                    url, fileName, NotificationId.Download + downloads.size,
                    mirrorUrls = mirrorUrls,
                    currentMirrorIndex = currentMirrorIndex
                )

            //查询下载进度 包含重试调用
            queryState()
            if (upStateJob == null) {
                checkDownloadState()
            }
        }.onFailure {
            it.printStackTrace()
            val msg = when (it) {
                is SecurityException -> "下载出错,没有存储权限"
                else -> "下载出错,${it.localizedMessage}"
            }
            toastOnUi(msg)
            AppLog.put(msg, it)
        }
    }

    /**
     * 取消下载
     */
    @Synchronized
    private fun removeDownload(downloadId: Long) {
        if (!completeDownloads.contains(downloadId)) {
            downloadManager.remove(downloadId)
        }
        downloads.remove(downloadId)
        completeDownloads.remove(downloadId)
        notificationManager.cancel(downloadId.toInt())
    }

    /**
     * 下载成功
     */
    @Synchronized
    private fun successDownload(downloadId: Long) {
        if (!completeDownloads.contains(downloadId)) {
            completeDownloads.add(downloadId)
            val fileName = downloads[downloadId]?.fileName
            openDownload(downloadId, fileName)
        }
    }

    private fun checkDownloadState() {
        upStateJob?.cancel()
        upStateJob = lifecycleScope.launch {
            while (isActive) {
                queryState()
                delay(1000)
            }
        }
    }

    /**
     * 查询下载进度，更新通知栏
     */
    @Synchronized
    private fun queryState() {
        if (downloads.isEmpty()) {
            stopSelf()
            return
        }
        val ids = downloads.keys
        val query = DownloadManager.Query()
        query.setFilterById(*ids.toLongArray())
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val progressIndex =
                    cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                do {
                    val id = cursor.getLong(idIndex)
                    val progress = cursor.getInt(progressIndex)
                    val max = cursor.getInt(fileSizeIndex)
                    val status = when (cursor.getInt(statusIndex)) {
                        DownloadManager.STATUS_PAUSED -> getString(R.string.pause)
                        DownloadManager.STATUS_PENDING -> getString(R.string.wait_download)
                        DownloadManager.STATUS_RUNNING -> getString(R.string.downloading)
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            successDownload(id)
                            getString(R.string.download_success)
                        }

                        DownloadManager.STATUS_FAILED -> {
                            // 下载失败时，尝试使用镜像 URL 自动重试
                            retryWithMirror(id)
                            getString(R.string.download_error)
                        }
                        else -> getString(R.string.unknown_state)
                    }
                    downloads[id]?.let { downloadInfo ->
                        upDownloadNotification(
                            id,
                            downloadInfo.notificationId,
                            "${downloadInfo.fileName} $status",
                            max,
                            progress,
                            downloadInfo.startTime
                        )
                    }
                } while (cursor.moveToNext())
            }
        }
    }

    /**
     * 打开下载文件
     */
    private fun openDownload(downloadId: Long, fileName: String?) {
        kotlin.runCatching {
            downloadManager.getUriForDownloadedFile(downloadId)?.let { uri ->
                val type = IntentType.from(fileName)
                openFileUri(uri, type)
            }
        }.onFailure {
            AppLog.put("打开下载文件${fileName}出错", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    /**
     * 更新通知
     */
    private fun upDownloadNotification(
        downloadId: Long,
        notificationId: Int,
        content: String,
        max: Int,
        progress: Int,
        startTime: Long
    ) {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(startTime)
        if (progress < max) {
            notificationBuilder.setProgress(max, progress, false)
        }
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * 镜像重试下载
     *
     * 当下载失败时，检查是否有可用的镜像 URL，
     * 如果有则自动移除失败的下载任务并使用下一个镜像 URL 重新发起下载。
     * 所有镜像都尝试失败后提示用户。
     *
     * @param downloadId 失败的下载任务 ID
     */
    @Synchronized
    private fun retryWithMirror(downloadId: Long) {
        // 防止同一个下载任务重复触发重试
        if (retryingDownloads.contains(downloadId)) return
        val info = downloads[downloadId] ?: return
        val mirrorUrls = info.mirrorUrls ?: return
        //下载 index ++
        val nextIndex = info.currentMirrorIndex + 1
        if (nextIndex < mirrorUrls.size) {
            // 标记为正在重试，避免 queryState 轮询时重复触发
            retryingDownloads.add(downloadId)
            val nextUrl = mirrorUrls[nextIndex]
            AppLog.put("下载失败，正在通过镜像重试 (${nextIndex}/${mirrorUrls.size - 1}): $nextUrl")
            toastOnUi("正在通过镜像重试下载...")
            // 移除失败的下载任务
            downloadManager.remove(downloadId)
            downloads.remove(downloadId)
            retryingDownloads.remove(downloadId)
            // 使用下一个镜像 URL 重新发起下载
            startDownload(nextUrl, info.fileName, mirrorUrls, nextIndex)
        } else {
            // 所有镜像都失败了
            AppLog.put("所有下载源均失败，共尝试 ${mirrorUrls.size} 个源")
            toastOnUi("所有下载源均失败")
        }
    }

    /**
     * 下载任务信息
     *
     * @param url 当前下载 URL
     * @param fileName 下载文件名
     * @param notificationId 通知 ID
     * @param startTime 下载开始时间
     * @param mirrorUrls GitHub 镜像 URL 列表（包含原始 URL），用于失败重试
     * @param currentMirrorIndex 当前使用的镜像索引
     */
    private data class DownloadInfo(
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val startTime: Long = System.currentTimeMillis(),
        val mirrorUrls: ArrayList<String>? = null,
        val currentMirrorIndex: Int = 0
    )

}
