package io.legado.app.model

import android.content.Context
import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isAudio
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheAudioService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object CacheAudio {

    private val downloadJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isDownloading(bookUrl: String): Boolean {
        return downloadJobs.containsKey(bookUrl)
    }

    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (!book.isLocal && book.isAudio) {
            context.startService<CacheAudioService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("start", start)
                putExtra("end", end)
            }
        }
    }

    fun startDownload(context: Context, bookUrl: String, start: Int, end: Int) {
        if (downloadJobs.containsKey(bookUrl)) return
        val service = context as? CacheAudioService
        val job = scope.launch {
            val book = appDb.bookDao.getBook(bookUrl) ?: return@launch
            val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return@launch
            val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
            
            val endIdx = if (end < 0) chapters.size - 1 else minOf(end, chapters.size - 1)
            val chaptersToDownload = chapters.filter { it.index in start..endIdx }
            
            for (chapter in chaptersToDownload) {
                if (AudioCache.isCached(book, chapter)) continue
                
                service?.updateNotification("正在下载: ${book.name} - ${chapter.title}")
                
                var retryCount = 0
                var success = false
                while (retryCount < 3 && !success) {
                    try {
                        val result = withTimeoutOrNull(5 * 60 * 1000L) {
                            val playUrl = WebBook.getContentAwait(bookSource, book, chapter, needSave = false)
                            if (playUrl.isBlank()) throw Exception("音频直链为空")
                            
                            val chapterName = AudioCache.getCleanChapterName(chapter.title)
                            val indexStr = String.format("%05d", chapter.index)
                            
                            if (playUrl.contains(".m3u8")) {
                                downloadM3u8(book, bookSource, playUrl, "${indexStr}_$chapterName.ts")
                            } else {
                                // 动态嗅探拓展名，默认为 mp3
                                val ext = if (playUrl.contains(".m4a")) ".m4a" else ".mp3"
                                downloadDirect(book, bookSource, playUrl, "${indexStr}_$chapterName$ext")
                            }
                        }
                        if (result == null) {
                            throw Exception("章节下载超时(5分钟)")
                        } else if (!result) {
                            throw Exception("下载请求失败或文件不完整")
                        } else {
                            success = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.put("有声书缓存失败: ${e.localizedMessage}", e, true)
                        retryCount++
                        delay(2000L * retryCount) // 退避重试
                    }
                }
                
                if (success) {
                    postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                }
            }
            service?.updateNotification("下载完成: ${book.name}")
        }
        downloadJobs[bookUrl] = job
        postEvent(EventBus.UP_DOWNLOAD, bookUrl)
        job.invokeOnCompletion {
            downloadJobs.remove(bookUrl)
            postEvent(EventBus.UP_DOWNLOAD, bookUrl)
            if (downloadJobs.isEmpty()) {
                service?.stopSelf()
            }
        }
    }

    private suspend fun downloadDirect(book: Book, bookSource: io.legado.app.data.entities.BookSource, url: String, fileName: String): Boolean {
        val analyzeUrl = io.legado.app.model.analyzeRule.AnalyzeUrl(url, source = bookSource, ruleData = book)
        val response = analyzeUrl.getResponseAwait()
        if (!response.isSuccessful) return false
        
        val folder = File(AudioCache.getCachePath(book))
        if (!folder.exists()) folder.mkdirs()
        
        val file = File(folder, fileName)
        val tempFile = File(folder, "$fileName.tmp")
        
        try {
            val body = response.body
            if (body == null) return false
            val contentLength = body.contentLength()
            
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            if (contentLength > 0 && tempFile.length() != contentLength) {
                return false
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
            return true
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private suspend fun downloadM3u8(book: Book, bookSource: io.legado.app.data.entities.BookSource, url: String, fileName: String): Boolean {
        val analyzeUrl = io.legado.app.model.analyzeRule.AnalyzeUrl(url, source = bookSource, ruleData = book)
        val response = analyzeUrl.getResponseAwait()
        if (!response.isSuccessful) return false
        
        val m3u8Content = response.body?.string() ?: return false
        val lines = m3u8Content.split("\n")
        val tsUrls = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        
        val folder = File(AudioCache.getCachePath(book))
        if (!folder.exists()) folder.mkdirs()
        
        val file = File(folder, fileName)
        val tempFile = File(folder, "$fileName.tmp")
        
        try {
            FileOutputStream(tempFile).use { output ->
                for (tsPath in tsUrls) {
                    // 如果是相对路径，需要拼成绝对路径
                    val tsUrl = io.legado.app.utils.NetworkUtils.getAbsoluteURL(url, tsPath)
                    val tsAnalyzeUrl = io.legado.app.model.analyzeRule.AnalyzeUrl(tsUrl, source = bookSource, ruleData = book)
                    val tsResp = tsAnalyzeUrl.getResponseAwait()
                    if (!tsResp.isSuccessful) {
                        return false
                    }
                    val body = tsResp.body
                    if (body == null) {
                        return false
                    }
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
            return true
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun removeDownload(bookUrl: String?) {
        if (bookUrl != null) {
            downloadJobs[bookUrl]?.cancel()
            downloadJobs.remove(bookUrl)
            postEvent(EventBus.UP_DOWNLOAD, bookUrl)
        }
    }

    fun stopAll() {
        val keys = downloadJobs.keys.toList()
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        keys.forEach { postEvent(EventBus.UP_DOWNLOAD, it) }
    }
}
