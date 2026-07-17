package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileUtils
import splitties.init.appCtx
import java.io.File
import java.util.regex.Pattern
import io.legado.app.utils.externalFiles

object AudioCache {

    //属性求值时机从类加载时init推迟到了被实际调用时Runtime，方式NPE
    val downloadDir: File
        get() = appCtx.externalFiles
    const val cacheFolderName = "audio_cache"

    val cachePath: String
        get() = FileUtils.getPath(downloadDir, cacheFolderName)

    /**
     * 获取有声书专属的缓存目录，防冲突
     */
    fun getCachePath(book: Book): String {
        return FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
    }

    /**
     * 判断该章节是否已离线缓存（本地是否存在合法的音频文件）
     */
    fun isCached(book: Book, chapter: BookChapter): Boolean {
        return getCachedFile(book, chapter) != null
    }

    /**
     * 获取章节具体的缓存文件（如果存在）
     */
    fun getCachedFile(book: Book, chapter: BookChapter): File? {
        val chapterName = getCleanChapterName(chapter.title)
        val indexStr = String.format("%05d", chapter.index)
        
        val prefix = "${indexStr}_$chapterName"
        val folder = File(getCachePath(book))
        
        if (!folder.exists() || !folder.isDirectory) return null
        
        val files = folder.listFiles { _, name ->
            name.startsWith(prefix) && (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".ts"))
        }
        
        return files?.firstOrNull()
    }

    /**
     * 获取清理非法字符后的章节名，用于生成文件名
     */
    fun getCleanChapterName(title: String): String {
        val pattern = Pattern.compile("[\\\\/:*?\"<>|]")
        return pattern.matcher(title).replaceAll("")
    }

    /**
     * 获取已缓存的章节前缀集合（序号_清理后的标题），用于 UI 高效且精准地判定缓存状态
     */
    fun getCachedChapterNames(book: Book): HashSet<String> {
        val names = hashSetOf<String>()
        val folder = File(getCachePath(book))
        if (!folder.exists() || !folder.isDirectory) return names
        folder.list()?.forEach { name ->
            // 提取 00001_ChapterName，去掉扩展名
            val prefix = name.substringBeforeLast(".")
            names.add(prefix)
        }
        return names
    }

    fun clearCache() {
        FileUtils.delete(FileUtils.getPath(downloadDir, cacheFolderName))
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }
}
