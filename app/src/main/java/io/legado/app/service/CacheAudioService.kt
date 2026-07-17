package io.legado.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.model.CacheAudio
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.base.BaseService

class CacheAudioService : BaseService() {

    companion object {
        var isRun = false
    }

    private var notificationContent = ""

    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CacheAudioService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        // 通知界面变 Stop 按钮
        postEvent(EventBus.UP_DOWNLOAD_STATE, "")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> {
                    val bookUrl = intent.getStringExtra("bookUrl")
                    val start = intent.getIntExtra("start", 0)
                    val end = intent.getIntExtra("end", 0)
                    if (bookUrl != null) {
                        CacheAudio.startDownload(this, bookUrl, start, end)
                    }
                }
                IntentAction.remove -> {
                    CacheAudio.removeDownload(intent.getStringExtra("bookUrl"))
                }
                IntentAction.stop -> {
                    CacheAudio.stopAll()
                    stopSelf()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        CacheAudio.stopAll()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
        // 通知界面变 Start 按钮
        postEvent(EventBus.UP_DOWNLOAD_STATE, "")
    }

    fun updateNotification(content: String) {
        if (content == notificationContent) return
        notificationContent = content
        val notification = notificationBuilder.setContentText(content).build()
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        startForeground(NotificationId.CacheAudioService, notification)
    }
}
