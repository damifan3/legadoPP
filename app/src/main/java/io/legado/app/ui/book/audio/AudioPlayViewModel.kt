package io.legado.app.ui.book.audio

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.isLocalAudio
import io.legado.app.model.AudioPlay
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

class AudioPlayViewModel(application: Application) : BaseViewModel(application) {
    val titleData = MutableLiveData<String>()
    val coverData = MutableLiveData<String>()
    val customBtnListData = MutableLiveData<Boolean>()

    fun initData(intent: Intent, success: (() -> Unit)) = AudioPlay.apply {
        execute {
            inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            val bookUrl = intent.getStringExtra("bookUrl") ?: book?.bookUrl ?: return@execute
            val targetBook = appDb.bookDao.getBook(bookUrl) ?: run {
                inBookshelf = false
                book?.also { appDb.bookDao.insert(it) } ?: return@execute
            }
            initBook(targetBook)
        }.onSuccess {
            success.invoke()
        }.onFinally {
            saveRead(true)
        }
    }

    private suspend fun initBook(book: Book) {
        val isSameBook = AudioPlay.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            AudioPlay.upData(book)
        } else {
            AudioPlay.resetData(book)
        }
        customBtnListData.postValue(AudioPlay.bookSource?.customButton == true)
        titleData.postValue(book.name)
        coverData.postValue(book.getDisplayCover())
        
        // 重新进入界面时，主动推送一次当前内存中的时长和进度，避免因 LiveEventBus 粘性事件丢失导致界面显示 00:00
        /*
        if (AudioPlay.durAudioSize > 0) {
            postEvent(EventBus.AUDIO_SIZE, AudioPlay.durAudioSize)
            postEvent(EventBus.AUDIO_PROGRESS, AudioPlay.durChapterPos)
        }
        */
        
        if (book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            return
        }
        if (AudioPlay.chapterSize == 0 && !loadChapterList(book)) {
            return
        }
    }

    private suspend fun loadBookInfo(book: Book): Boolean {
        if (book.isLocalAudio) return true
        
        val bookSource = AudioPlay.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(bookSource, book)
            return true
        } catch (e: Exception) {
            AppLog.put("详情页出错: ${e.localizedMessage}", e, true)
            return false
        }
    }

    private suspend fun loadChapterList(book: Book): Boolean {
        //本地有声书
        if (book.isLocalAudio) {
            return kotlin.runCatching {
                io.legado.app.model.localBook.LocalBook.getChapterList(book).let {
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    appDb.bookDao.update(book)
                    AudioPlay.chapterSize = it.size
                    AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
                    AudioPlay.upDurChapter()
                }
                true
            }.onFailure {
                context.toastOnUi(R.string.error_load_toc)
            }.getOrDefault(false)
        }

        val bookSource = AudioPlay.bookSource ?: return true
        try {
            val oldBook = book.copy()
            val cList = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
            //以下四行标准范式：每个更新目录的地方都要加上，防止前面章节黑屋后，缓存失效
            val oldToc = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
            
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }

            BookHelp.migrateTocCache(book, oldToc, cList)
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            AudioPlay.chapterSize = cList.size
            AudioPlay.simulatedChapterSize = book.simulatedTotalChapterNum()
            AudioPlay.upDurChapter()
            return true
        } catch (_: Exception) {
            context.toastOnUi(R.string.error_load_toc)
            return false
        }
    }

    fun upSource() {
        execute {
            val book = AudioPlay.book ?: return@execute
            AudioPlay.bookSource = book.getBookSource()?.also{
                customBtnListData.postValue(it.customButton)
            }
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        execute {
            AudioPlay.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            AudioPlay.book?.delete()
            appDb.bookDao.insert(book)
            AudioPlay.book = book
            AudioPlay.bookSource = source
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            AudioPlay.upDurChapter()
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            AudioPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

}