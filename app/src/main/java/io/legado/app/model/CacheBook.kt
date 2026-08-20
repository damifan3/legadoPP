package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isAudio
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object CacheBook {

    val cacheBookMap = ConcurrentHashMap<String, CacheBookModel>()

    private val workingState = MutableStateFlow(true)
    private val mutex = Mutex()

    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[bookUrl] = cacheBook
        return cacheBook
    }

    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel {
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[book.bookUrl] = cacheBook
        return cacheBook
    }

    private fun updateBookSource(newBookSource: BookSource) {
        cacheBookMap.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (!book.isLocal) {
            if (book.isAudio) {
                CacheAudio.start(context, book, start, end)
            } else {
                context.startService<CacheBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("start", start)
                    putExtra("end", end)
                }
            }
        }
    }

    fun remove(context: Context, bookUrl: String) {
        context.startService<CacheBookService> {
            action = IntentAction.remove
            putExtra("bookUrl", bookUrl)
        }
    }

    fun stop(context: Context) {
        if (CacheBookService.isRun) {
            context.startService<CacheBookService> {
                action = IntentAction.stop
            }
        }
        if (io.legado.app.service.CacheAudioService.isRun) {
            context.startService<io.legado.app.service.CacheAudioService> {
                action = IntentAction.stop
            }
        }
    }

    fun close() {
        cacheBookMap.forEach { it.value.stop() }
        cacheBookMap.clear()
        //成功：数量归零
        successDownloadSet.clear()
        errorDownloadMap.clear()
    }

    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    //mutex.withLock：使用协程互斥锁（Mutex），确保在同一时刻只能有一个 startProcessJob 任务在运行，避免多个任务实例引发的并发冲突。
    suspend fun startProcessJob(context: CoroutineContext) = mutex.withLock {
        //setWorkingState(true)：将当前缓存队列的工作状态标记为“运行中”。
        setWorkingState(true)
        //通过 flow 构建了一个数据流，用来源源不断地“提供”需要下载的书籍
        flow {
            //只要当前协程没有被取消（isActive），且缓存队列 (cacheBookMap) 中还有书，就持续循环
            while (currentCoroutineContext().isActive && cacheBookMap.isNotEmpty()) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    //目录已经加载完毕，且有待下载任务
                    if (!model.isLoading() && model.waitCount > 0) {
                        emit(model)
                        emitted = true
                    }
                    //workingState 应该是一个状态流（如 StateFlow），它会挂起并等待直到接收到 true 的状态。如果用户暂停了下载（外部将状态设为 false），这里的遍历也会随之挂起，实现“暂停下载”的功能。
                    workingState.first { it }
                }
                //如果在一次完整遍历中，所有的书都在加载中而没有派发任何新任务（!emitted），则 delay(1000) 休眠 1 秒钟后再重试，避免 while 循环过度消耗 CPU 资源。
                if (!emitted) {
                    delay(1000)
                }
            }
        }.onStart {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.onEachParallel(AppConfig.threadCount) {
            coroutineScope {
                //开始下载
                it.download(this, context)
            }
        }.onCompletion {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.collect()
    }


    val downloadSummary: String
        get() {
            return "正在下载:${onDownloadCount}|等待中:${waitCount}|失败:${errorDownloadMap.count()}|成功:${successDownloadSet.size}"
        }

    val isRun: Boolean
        get() {
            if (io.legado.app.service.CacheAudioService.isRun) return true
            cacheBookMap.forEach {
                if (it.value.isRun()) {
                    return true
                }
            }
            return false
        }

    private val waitCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.waitCount
            }
            return count
        }

    val onDownloadCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.onDownloadCount
            }
            return count
        }

    val successDownloadSet = linkedSetOf<String>()
    val errorDownloadMap = hashMapOf<String, Int>()

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        private val waitDownloadSet = linkedSetOf<Int>()
        private val onDownloadSet = linkedSetOf<Int>()
        private val tasks = CompositeCoroutine()
        private var isStopped = false
        private var waitingRetry = false
        private var isLoading = false

        val waitCount get() = waitDownloadSet.size
        val onDownloadCount get() = onDownloadSet.size

        init {
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        @Synchronized
        fun isRun(): Boolean {
            return waitDownloadSet.isNotEmpty() || onDownloadSet.isNotEmpty() || isLoading
        }

        @Synchronized
        fun isStop(): Boolean {
            return isStopped || (!isRun() && !waitingRetry)
        }

        @Synchronized
        fun isLoading(): Boolean {
            return isLoading
        }

        @Synchronized
        fun setLoading() {
            isLoading = true
        }

        @Synchronized
        fun stop() {
            //等待中：数量归零
            waitDownloadSet.clear()
            onDownloadSet.clear() // [Bug Fix]: 显式清理正在下载队列，防止 cancel 回调异步执行期间发生内存泄漏
            // 清理任务
            tasks.clear()
            isStopped = true
            isLoading = false
            onFinally()
        }

        /*
        当服务成功拉取到目录存入数据库，并计算好需要下载哪些章节后，
        会调用 addDownload 将具体的章节编号放入待下载队列中，此时同步将 isLoading = false
         */
        @Synchronized
        fun addDownload(start: Int, end: Int) {
            isStopped = false
            for (i in start..end) {
                if (!onDownloadSet.contains(i)) {
                    waitDownloadSet.add(i)
                }
            }
            cacheBookMap[book.bookUrl] = this
            isLoading = false
            onFinally()
        }

        @Synchronized
        private fun onSuccess(chapter: BookChapter) {
            onDownloadSet.remove(chapter.index)
            successDownloadSet.add(chapter.primaryStr())
            errorDownloadMap.remove(chapter.primaryStr())
        }

        @Synchronized
        private fun onPreError(chapter: BookChapter, error: Throwable) {
            waitingRetry = true
            if (error !is ConcurrentException) {
                errorDownloadMap[chapter.primaryStr()] =
                    (errorDownloadMap[chapter.primaryStr()] ?: 0) + 1
            }
            onDownloadSet.remove(chapter.index)
        }

        @Synchronized
        private fun onPostError(chapter: BookChapter, error: Throwable) {
            //重试3次
            if ((errorDownloadMap[chapter.primaryStr()] ?: 0) < 3 && !isStopped) {
                waitDownloadSet.add(chapter.index)
            } else {
                AppLog.put(
                    "下载${book.name}-${chapter.title}失败\n${error.localizedMessage}",
                    error
                )
            }
            waitingRetry = false
        }

        @Synchronized
        private fun onError(chapter: BookChapter, error: Throwable) {
            onPreError(chapter, error)
            onPostError(chapter, error)
        }

        /**
         * 1. 一个特殊情况：如果进入正文，缓存没完成就退出了，
         *  需要此函数将章节从 onDownloadSet 移动到 waitDownloadSet
         */
        @Synchronized
        private fun onCancel(index: Int) {
            onDownloadSet.remove(index)
            //该标志位会在 CacheBookModel.stop 函数中置位 true，比如手动点击通知栏或者缓存列表取消时
            if (!isStopped) waitDownloadSet.add(index)
        }

        /**
         * 统一的清理与状态同步出口
         * 当待下载集合和正在下载集合均为空时，说明该书的全部下载任务（或同步跳过的任务）均已完成。
         * 此时需要确保将其从全局的缓存任务池 cacheBookMap 中移除，否则会导致 CacheBookService 无法停止，出现通知卡在“正在下载0，等待中0”的Bug。
         * 加入 !isLoading 校验，是为了防止在第一次获取目录期间（isLoading = true）被误清理。
         */
        @Synchronized
        fun onFinally() {
            if (isStopped && tasks.isEmpty && waitDownloadSet.isEmpty() && onDownloadSet.isNotEmpty()) {
                io.legado.app.constant.AppLog.put("ZombieTask Log: onFinally triggered while stopped, tasks empty?: ${tasks.isEmpty}, but onDownloadSet NOT empty! (Size: ${onDownloadSet.size})", null, false)
            }
            if (!isLoading && waitDownloadSet.isEmpty() && onDownloadSet.isEmpty()) {
                cacheBookMap.remove(book.bookUrl)
            }
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        /**
         * 从待下载列表内取第一条下载
         */
        @Synchronized
        fun download(scope: CoroutineScope, context: CoroutineContext) {
            val chapterIndex = waitDownloadSet.firstOrNull()
            /*
            如果没有取到（chapterIndex == null），说明当前待下载队列为空。
            此时如果这本书没有在加载章节列表isLoading==false，并且也没有正在下载的章节（onDownloadSet.isEmpty()），那么说明整本书的下载任务已完成，将其从全局的缓存任务池 cacheBookMap 中移除，结束方法。
            */
            if (chapterIndex == null) {
                onFinally()
                return
            }

            //去重保护：如果这个章节已经在正在下载集合 (onDownloadSet) 中，说明由于某种原因重复触发了，直接将其从待下载集合中剔除并忽略。
            if (onDownloadSet.contains(chapterIndex)) {
                waitDownloadSet.remove(chapterIndex)
                onFinally()
                return
            }
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: let {
                waitDownloadSet.remove(chapterIndex)
                onFinally()
                return
            }
            //过滤分卷
            if (chapter.isVolume) {
                /** 修正下载计数 */
                postEvent(EventBus.SAVE_CONTENT, Pair(book, chapter))
                waitDownloadSet.remove(chapterIndex)
                onFinally()
                return
            }

            //过滤图片章节：如果判断出该章节是已处理过的图片类型（hasImageContent），则跳过下载。
            if (BookHelp.hasImageContent(book, chapter)) {
                waitDownloadSet.remove(chapterIndex)
                onFinally()
                return
            }

            //经过上述校验，确认需要处理，正式将该章节从“待下载”移入“正在下载”队列。
            waitDownloadSet.remove(chapterIndex)
            onDownloadSet.add(chapterIndex)
            
            //分支一：本地已有正文，仅下载/处理图片
            if (BookHelp.hasContent(book, chapter)) {
                Coroutine.async(scope, context, start = CoroutineStart.LAZY, executeContext = context) {
                    BookHelp.getContent(book, chapter)?.let {
                        BookHelp.saveImages(bookSource, book, chapter, it, 1)
                    }
                }.onSuccess {
                    onSuccess(chapter)
                }.onError {
                    onPreError(chapter, it)
                    //出现错误等待一秒后重新加入待下载列表
                    delay(1000)
                    onPostError(chapter, it)
                }.onCancel {
                    onCancel(chapterIndex)
                //also 函数：返回值 = 传入的对象的本身
                }.also { coroutine ->
                    tasks.add(coroutine)
                    coroutine.onFinally {
                        // [Bug Fix]: 之前使用 tasks.remove 会联动调用 coroutine.cancel()，
                        // 导致 onCancel 回调被执行，从而把该章节重新塞回 waitDownloadSet 产生死循环。
                        // 这里应使用 delete，只从集合中移除记录而不主动 cancel。
                        tasks.delete(coroutine)
                        onFinally()
                    }
                }.start()
                return
            }

            //分支二：本地无正文，发起网络请求下载
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                context = context,
                //启动模式为 LAZY 懒加载，底层 Kotlin 协程 launch(start = CoroutineStart.LAZY) 创建后会停留在初始创建状态（New），不会立即调度运行。
                start = CoroutineStart.LAZY,
                executeContext = context
            ).onSuccess { content ->
                onSuccess(chapter)
                downloadFinish(chapter, content)
            }.onError {
                onPreError(chapter, it)
                //出现错误等待一秒后重新加入待下载列表
                delay(1000)
                onPostError(chapter, it)
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}")
            }.onCancel {
                onCancel(chapterIndex)
            }.also { coroutine ->
                tasks.add(coroutine)
                coroutine.onFinally {
                    // [Bug Fix]: 之前使用 tasks.remove 会联动调用 coroutine.cancel()，
                    // 导致 onCancel 回调被执行，从而把该章节重新塞回 waitDownloadSet 产生死循环。
                    // 这里应使用 delete，只从集合中移除记录而不主动 cancel。
                    tasks.delete(coroutine)
                    onFinally()
                }
            }.start()
        }

        suspend fun downloadAwait(chapter: BookChapter): String {
            synchronized(this) {
                onDownloadSet.add(chapter.index)
                waitDownloadSet.remove(chapter.index)
            }
            try {
                val content = WebBook.getContentAwait(bookSource, book, chapter)
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                return content
            } catch (e: Exception) {
                if (e is CancellationException) {
                    onCancel(chapter.index)
                }
                onError(chapter, e)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                return "获取正文失败\n${e.localizedMessage}"
            } finally {
                onFinally()
            }
        }

        @Synchronized
        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false
        ) {
            if (onDownloadSet.contains(chapter.index)) {
                return
            }
            onDownloadSet.add(chapter.index)
            waitDownloadSet.remove(chapter.index)
            WebBook.getContent(
                scope,
                bookSource,
                book,
                chapter,
                start = CoroutineStart.LAZY,
                executeContext = IO,
                semaphore = semaphore
            ).onSuccess { content ->
                onSuccess(chapter)
                ReadBook.downloadedChapters.add(chapter.index)
                ReadBook.downloadFailChapters.remove(chapter.index)
                downloadFinish(chapter, content, resetPageOffset)
            }.onError {
                onError(chapter, it)
                ReadBook.downloadFailChapters[chapter.index] =
                    (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
                downloadFinish(chapter, "获取正文失败\n${it.localizedMessage}", resetPageOffset)
            }.onCancel {
                onCancel(chapter.index)
                downloadFinish(chapter, "download canceled", resetPageOffset, true)
            }.onFinally {
                    onFinally()
            }.start()
        }

        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false
        ) {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled
                )
            }
        }

    }

}