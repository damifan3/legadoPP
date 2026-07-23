package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isLocalAudio
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AudioPlayService
import io.legado.app.model.SourceCallBack
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import splitties.init.appCtx
import kotlin.text.trim

@SuppressLint("StaticFieldLeak")
@Suppress("unused")
object AudioPlay : CoroutineScope by MainScope() {
    /**
     * 播放模式枚举
     */
    enum class PlayMode(val iconRes: Int) {
        LIST_END_STOP(R.drawable.ic_play_mode_list_end_stop),
        SINGLE_LOOP(R.drawable.ic_play_mode_single_loop),
        RANDOM(R.drawable.ic_play_mode_random),
        LIST_LOOP(R.drawable.ic_play_mode_list_loop);

        fun next(): PlayMode {
            return when (this) {
                LIST_END_STOP -> SINGLE_LOOP
                SINGLE_LOOP -> RANDOM
                RANDOM -> LIST_LOOP
                LIST_LOOP -> LIST_END_STOP
            }
        }
    }

    var playMode = PlayMode.LIST_END_STOP
    var status = Status.STOP
    private var playJob: io.legado.app.help.coroutine.Coroutine<*>? = null
    private var activityContext: Context? = null
    private var serviceContext: Context? = null
    private val context: Context get() = activityContext ?: serviceContext ?: appCtx
    var callback: CallBack? = null
    var book: Book? = null
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var durChapter: BookChapter? = null
    var durPlayUrl = ""
    var durLyric: String? = null
    var durAudioSize = 0
    var inBookshelf = false
    var bookSource: BookSource? = null
    val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    var readStartTime: Long = System.currentTimeMillis()
    val executor = globalExecutor

    fun changePlayMode() {
        playMode = playMode.next()
        book?.setPlayMode(playMode.ordinal)
        postEvent(EventBus.PLAY_MODE_CHANGED, playMode)
    }

    fun upData(book: Book) {
        AudioPlay.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            stopPlay()
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            durPlayUrl = ""
            durLyric = null
            durAudioSize = 0
        }
        upDurChapter()
    }

    fun resetData(book: Book) {
        stop()
        AudioPlay.book = book
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        bookSource = book.getBookSource()
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        PlayMode.entries.getOrNull(book.getPlayMode())?.let{
            playMode = it
            postEvent(EventBus.PLAY_MODE_CHANGED, it)
        }
        val playSpeed = book.getPlaySpeed()
        AudioPlayService.playSpeed = playSpeed
        postEvent(EventBus.AUDIO_SPEED, playSpeed)
        durPlayUrl = ""
        durLyric = null
        durAudioSize = 0
        upDurChapter()
        SourceCallBack.callBackBook(SourceCallBack.START_READ, bookSource, book, durChapter)
        postEvent(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    }

    fun upReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        executor.execute {
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    private fun addLoading(index: Int): Boolean {
        synchronized(this) {
            if (loadingChapters.contains(index)) return false
            loadingChapters.add(index)
            return true
        }
    }

    private fun removeLoading(index: Int) {
        synchronized(this) {
            loadingChapters.remove(index)
        }
    }

    fun loadOrUpPlayUrl() {
        if (durPlayUrl.isEmpty()) {
            loadPlayUrl()
        } else {
            upPlayUrl()
        }
    }

    /**
     * 加载播放URL
     */
    private fun loadPlayUrl() {
        val index = durChapterIndex
        if (addLoading(index)) {
            val book = book
            
            if (book != null && book.isLocalAudio) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    return
                }
                //跳过不能播放的卷几
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                upLoading(true)

                //chapter 的 url 就是文件路径，
                // 详见 io.legado.app.model.localBook.AudioFile.getChapterList
                contentLoadFinish(chapter, chapter.url)
                upLoading(false)
                removeLoading(index)
                return
            }

            val bookSource = bookSource
            if (book != null && bookSource != null) {
                upDurChapter()
                val chapter = durChapter
                if (chapter == null) {
                    removeLoading(index)
                    return
                }
                if (chapter.isVolume) {
                    skipTo(index + 1)
                    removeLoading(index)
                    return
                }
                upLoading(true)
                
                // 离线缓存拦截
                val cachedFile = io.legado.app.model.AudioCache.getCachedFile(book, chapter)
                if (cachedFile != null) {
                    contentLoadFinish(chapter, android.net.Uri.fromFile(cachedFile).toString())
                    upLoading(false)
                    removeLoading(index)
                    return
                }

                WebBook.getContent(this, bookSource, book, chapter, needSave = false)
                    .onSuccess { content ->
                        val content = content.trim()
                        if (content.isEmpty()) {
                            appCtx.toastOnUi("未获取到资源链接")
                        } else {
                            contentLoadFinish(chapter, content)
                        }
                    }.onError {
                        AppLog.put("获取资源链接出错\n$it", it, true)
                        upLoading(false)
                    }.onCancel {
                        removeLoading(index)
                    }.onFinally {
                        callback?.upLyric(durLyric)
                        removeLoading(index)
                    }
            } else {
                removeLoading(index)
                appCtx.toastOnUi("book or source is null")
            }
        }
    }

    /**
     * 加载完成
     */
    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index == durChapterIndex) {
            durPlayUrl = content
            durLyric = chapter.getVariable("lyric")
            upPlayUrl()
        }
    }

    private fun upPlayUrl() {
        if (isPlayToEnd()) {
            playNew()
        } else {
            play()
        }
    }

    /**
     * 播放当前章节
     */
    fun play() {
        context.startService<AudioPlayService> {
            action = IntentAction.play
        }
    }

    /**
     * 从头播放新章节
     */
    private fun playNew() {
        context.startService<AudioPlayService> {
            action = IntentAction.playNew
        }
    }

    /**
     * 根据 durChapterIndex 更新当前章节 durChapter
     */
    fun upDurChapter() {
        val book = book ?: return
        durChapter = appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)
        durAudioSize = durChapter?.end?.toInt() ?: 0
        // 提前获取本地音频时长，使得在未点击播放前即可拖动进度条
        if (durAudioSize <= 0 && book.isLocalAudio) {
            durChapter?.let { chapter ->
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(appCtx, android.net.Uri.parse(chapter.url))
                    val durationStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (durationStr != null) {
                        durAudioSize = durationStr.toLong().toInt()
                        AppLog.put("获取本地音频时长成功\n${durAudioSize}")
                    }
                    mmr.release()
                } catch (e: Exception) {
                    AppLog.put("获取本地音频时长失败\n${e.localizedMessage}", e)
                }
            }
        }
        val title = durChapter?.title ?: appCtx.getString(R.string.data_loading)
        postEvent(EventBus.AUDIO_SUB_TITLE, title)
        postEvent(EventBus.AUDIO_SIZE, durAudioSize)
        postEvent(EventBus.AUDIO_PROGRESS, durChapterPos)
    }

    fun pause(context: Context) {
        if (AudioPlayService.isRun) {
            readStartTime = System.currentTimeMillis()
            context.startService<AudioPlayService> {
                action = IntentAction.pause
            }
        }
    }

    fun resume(context: Context) {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.resume
            }
        }
    }

    fun stop() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stop
            }
        }
    }

    fun setSpeed(speed: Float) {
        if (AudioPlayService.isRun) {
            book?.setPlaySpeed(speed)
            val clampedSpeed = speed.coerceIn(0.5f, 3.0f)
            context.startService<AudioPlayService> {
                action = IntentAction.setSpeed
                putExtra("speed", clampedSpeed)
            }
        }
    }

     

    fun adjustProgress(position: Int) {
        durChapterPos = position
        saveRead()
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.adjustProgress
                putExtra("position", position)
            }
        }
    }

    fun skipTo(index: Int) {
        if (index in 0..<simulatedChapterSize) {
            playJob?.cancel()
            stopPlay()
            durChapterIndex = index
            durChapterPos = 0
            durPlayUrl = ""
            durLyric = null
            // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
            upDurChapter()
            playJob = Coroutine.async {
                saveRead()
                loadPlayUrl()
            }
        }
    }

    fun prev() {
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex > 0) {
                    playJob?.cancel()
                    stopPlay()
                    durChapterIndex -= 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    durLyric = null
                    // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                    upDurChapter()
                    playJob = Coroutine.async {
                        saveRead()
                        loadPlayUrl()
                    }
                } else {
                    appCtx.toastOnUi("已经是第一章了")
                }
            }

            PlayMode.SINGLE_LOOP -> {
                playJob?.cancel()
                stopPlay()
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter（单曲循环 index 不变，但仍需刷新状态）
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.RANDOM -> {
                playJob?.cancel()
                stopPlay()
                if (simulatedChapterSize > 0) {
                    durChapterIndex = (0 until simulatedChapterSize).random()
                }
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.LIST_LOOP -> {
                playJob?.cancel()
                stopPlay()
                if (simulatedChapterSize > 0) {
                    durChapterIndex = (durChapterIndex - 1 + simulatedChapterSize) % simulatedChapterSize
                }
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }
        }
    }

    fun next() {
        upReadTime()
        when (playMode) {
            PlayMode.LIST_END_STOP -> {
                if (durChapterIndex + 1 < simulatedChapterSize) {
                    playJob?.cancel()
                    stopPlay()
                    durChapterIndex += 1
                    durChapterPos = 0
                    durPlayUrl = ""
                    durLyric = null
                    // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                    upDurChapter()
                    playJob = Coroutine.async {
                        saveRead()
                        loadPlayUrl()
                    }
                } else {
                    // 提示用户已经是最后一章，避免用户不知道发生了什么
                    appCtx.toastOnUi("已经是最后一章了")
                    // 只停止播放，不调用 stop() 关闭整个服务
                    // 这样可以保留通知栏播放器状态（显示为暂停），以便用户可以进行重新播放或上一章等操作
                    stopPlay()
                }
            }

            PlayMode.SINGLE_LOOP -> {
                playJob?.cancel()
                stopPlay()
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter（单曲循环 index 不变，但仍需刷新状态）
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.RANDOM -> {
                playJob?.cancel()
                stopPlay()
                durChapterIndex = (0 until simulatedChapterSize).random()
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }

            PlayMode.LIST_LOOP -> {
                playJob?.cancel()
                stopPlay()
                durChapterIndex = (durChapterIndex + 1) % simulatedChapterSize
                durChapterPos = 0
                durPlayUrl = ""
                durLyric = null
                // 立即同步更新 durChapter，确保后续通知刷新使用新章节标题
                upDurChapter()
                playJob = Coroutine.async {
                    saveRead()
                    loadPlayUrl()
                }
            }
        }
    }

    fun setTimer(minute: Int) {
        if (AudioPlayService.isRun) {
            val intent = Intent(context, AudioPlayService::class.java)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startService(intent)
        } else {
            AudioPlayService.timeMinute = minute
            postEvent(EventBus.AUDIO_DS, minute)
        }
    }

    fun addTimer() {
        val intent = Intent(context, AudioPlayService::class.java)
        intent.action = IntentAction.addTimer
        context.startService(intent)
    }

    fun stopPlay() {
        if (AudioPlayService.isRun) {
            context.startService<AudioPlayService> {
                action = IntentAction.stopPlay
            }
        }
    }

    fun saveRead(first: Boolean = false) {
        val book = book ?: return
        Coroutine.async {
            book.lastCheckCount = 0
            val durTime = System.currentTimeMillis()
            book.durChapterTime = durTime
            val chapterChanged = book.durChapterIndex != durChapterIndex
            book.durChapterIndex = durChapterIndex
            book.durChapterPos = durChapterPos
            if (first || chapterChanged) {
                appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)?.let {
                    book.durChapterTitle = it.getDisplayTitle(
                        ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                        book.getUseReplaceRule(),
                        replaceBook = book.toReplaceBook()
                    )
                    SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it,durTime.toString())
                }
            }
            book.update()
        }
    }

    /**
     * 保存章节长度
     */
    fun saveDurChapter(audioSize: Long) {
        val chapter = durChapter ?: return
        Coroutine.async {
            durAudioSize = audioSize.toInt()
            chapter.end = audioSize
            chapter.update()
        }
    }

    fun playPositionChanged(position: Int) {
        durChapterPos = position
        saveRead()
    }

    fun upLoading(loading: Boolean) {
        callback?.upLoading(loading)
    }

    private fun isPlayToEnd(): Boolean {
        return durChapterIndex + 1 == simulatedChapterSize
                && durChapterPos == durAudioSize
    }

    fun register(context: Context) {
        activityContext = context
        callback = context as CallBack
    }

    fun unregister(context: Context) {
        if (activityContext === context) {
            activityContext = null
            callback = null
        }
        coroutineContext.cancelChildren()
    }

    fun registerService(context: Context) {
        serviceContext = context
    }

    fun unregisterService() {
        serviceContext = null
    }

    interface CallBack {

        fun upLoading(loading: Boolean)
        fun upLyric(lyric: String?)
        fun upLyricP(position: Int)
    }

}
