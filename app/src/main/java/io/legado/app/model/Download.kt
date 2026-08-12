package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.utils.startService

object Download {

    /**
     * 启动下载服务
     *
     * @param context 上下文
     * @param url 原始下载 URL
     * @param fileName 下载文件名
     * @param mirrorUrls 可选的镜像 URL 列表，下载失败时按顺序自动重试
     */
    fun start(context: Context, url: String, fileName: String, mirrorUrls: List<String>? = null) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
            // 传递镜像 URL 列表，用于 GitHub 下载失败时的自动重试
            mirrorUrls?.let { putStringArrayListExtra("mirrorUrls", ArrayList(it)) }
        }
    }

}