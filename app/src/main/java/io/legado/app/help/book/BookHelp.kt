package io.legado.app.help.book

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.JaccardSimilarity
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File
        get() = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    val Book.cacheFolderName: String
        get() = if (this.isAudio) io.legado.app.model.AudioCache.cacheFolderName else BookHelp.cacheFolderName
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    fun clearCache() {
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, book.cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    fun updateCacheFolder(oldBook: Book, newBook: Book) {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName == newFolderName) return
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            oldBook.cacheFolderName,
            oldFolderName
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            newBook.cacheFolderName,
            newFolderName
        )
        FileUtils.move(oldFolderPath, newFolderPath)
    }

    /**
     * 自动迁移和章节缓存重命名
     * 当章节目录更新（插入、删除导致 index 偏移）时，根据新旧章节的 URL 对应关系，
     * 将旧的缓存文件精确重命名为新 index 的文件名。
     * 第一次降级：使用旧目录 md5 匹配
     * 第二次降级：使用本地文件MD5匹配
     */
    fun migrateTocCache(book: Book, oldToc: List<BookChapter>, newToc: List<BookChapter>) {
        if (oldToc.isEmpty() || newToc.isEmpty() || book.isLocalTxt) {
            return
        }

        val cacheFolder = FileUtils.getPath(downloadDir, book.cacheFolderName, book.getFolderName())
        val cacheDir = File(cacheFolder)

        if (!cacheDir.exists()) {
            return
        }
        val files = cacheDir.listFiles()
        if (files.isNullOrEmpty()) {
            return
        }

        // 仅处理符合缓存命名规则的文件，如 00010-a1b2c3d4e5f6g7h8.nb 或 .nr
        val isAudio = book.isAudio
        val prefixPattern = if (isAudio) Regex("^[0-9]{5}_.*$") else Regex("^[0-9]{5}-[a-fA-F0-9]{16}$")

        // md5或者有声书章节名
        val getIdentifier: (String) -> String = if (isAudio) {
            { title -> io.legado.app.model.AudioCache.getCleanChapterName(title) }
        } else {
            { title -> MD5Utils.md5Encode16(title) }
        }

        // 文件名 prefix 格式
        val formatPrefix: (Int, String) -> String = if (isAudio) {
            { index, identifier -> String.format("%05d_%s", index, identifier) }
        } else {
            { index, identifier -> String.format("%05d-%s", index, identifier) }
        }

        // 建立 本地实际缓存文件（MD5/CleanTitle）到 旧缓存前缀 的映射（应对 oldToc 中章节丢失但缓存还在的情况）
        val localCacheIdentifierToPrefixes = HashMap<String, MutableList<String>>()
        val prefixToLastModified = HashMap<String, Long>() // 记录每个前缀对应文件的修改时间
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex > 0) {
                val prefix = name.substring(0, dotIndex)
                if (prefix.matches(prefixPattern)) {
                    prefixToLastModified[prefix] = file.lastModified()
                    val identifier = prefix.substring(6) // 提取出特征部分(MD5或标题)
                    localCacheIdentifierToPrefixes.getOrPut(identifier) { ArrayList() }.add(prefix)
                }
            }
        }

        // 建立 URL 和 Identifier 到 旧缓存前缀 的映射
        val oldUrlToPrefix = HashMap<String, String>()
        val oldIdentifierToPrefixes = HashMap<String, MutableList<String>>()
        val oldPrefixToUrl = HashMap<String, String>()
        val oldPrefixToTitle = HashMap<String, String>()
        for (chapter in oldToc) {
            val identifier = getIdentifier(chapter.title)
            val prefix = formatPrefix(chapter.index, identifier)
            oldUrlToPrefix[chapter.url] = prefix
            oldIdentifierToPrefixes.getOrPut(identifier) { ArrayList() }.add(prefix)
            oldPrefixToUrl[prefix] = chapter.url
            oldPrefixToTitle[prefix] = chapter.title
        }

        val renameMap = HashMap<String, String>()
        val usedOldPrefixes = HashSet<String>() // 记录已被匹配走的旧前缀，防止被多个新章节重复占用

        for (newChapter in newToc) {
            val newIdentifier = getIdentifier(newChapter.title)
            val newPrefix = formatPrefix(newChapter.index, newIdentifier)

            //先进行url匹配
            var matchMode = "URL匹配"
            var oldPrefix = oldUrlToPrefix[newChapter.url]

            if (oldPrefix != null && usedOldPrefixes.contains(oldPrefix)) {
                // 如果 URL 匹配到的旧前缀已经被其他章节占用（可能是网站URL重复或混乱），则放弃此匹配
                oldPrefix = null
            }

            // 辅助函数：在同 Identifier 的旧前缀列表中寻找文件时间最早且未被占用的前缀
            val findOldestPrefix: (MutableList<String>?) -> String? = { prefixes ->
                var bestPrefix: String? = null
                var minTime = Long.MAX_VALUE
                prefixes?.forEach { p ->
                    if (!usedOldPrefixes.contains(p)) {
                        val time = prefixToLastModified[p] ?: Long.MAX_VALUE
                        if (time < minTime) {
                            minTime = time
                            bestPrefix = p
                        }
                    }
                }
                bestPrefix
            }

            // 降级使用标题 Identifier 来匹配（url变了标题没变）
            if (oldPrefix == null) {
                oldPrefix = findOldestPrefix(oldIdentifierToPrefixes[newIdentifier])
                if (oldPrefix != null) matchMode = "旧目录特征降级匹配"
            }

            // 再次降级：直接从本地未关联的缓存文件中按 Identifier 匹配（没有旧目录了，直接从文件中匹配）
            if (oldPrefix == null) {
                oldPrefix = findOldestPrefix(localCacheIdentifierToPrefixes[newIdentifier])
                if (oldPrefix != null) matchMode = "本地文件特征降级匹配"
            }

            if (oldPrefix != null) {
                usedOldPrefixes.add(oldPrefix) // 标记为已使用
                if (oldPrefix != newPrefix) {
                    renameMap[oldPrefix] = newPrefix
                    val oldUrl = oldPrefixToUrl[oldPrefix] ?: "未知(本地缓存)"
                    val oldTitle = oldPrefixToTitle[oldPrefix] ?: "未知(本地缓存)"
                    // 输出重命名迁移的调试日志，增加详细信息
                    if (matchMode == "URL匹配") {
                        AppLog.put("缓存迁移(${book.name}): URL未变，正常[$matchMode]。\n旧前缀: $oldPrefix -> 新前缀: $newPrefix\n旧章节名: $oldTitle -> 新章节名: ${newChapter.title}\n旧URL: $oldUrl\n新URL: ${newChapter.url}")
                    } else {
                        AppLog.put("缓存迁移(${book.name}): URL发生改变，触发[$matchMode]。\n旧前缀: $oldPrefix -> 新前缀: $newPrefix\n旧章节名: $oldTitle -> 新章节名: ${newChapter.title}\n旧URL: $oldUrl\n新URL: ${newChapter.url}")
                    }
                }
            } else {
                // 没有找到可迁移的旧章节，属于纯粹的新章节
                AppLog.put("缓存迁移(${book.name}): 发现全新章节，无旧缓存可迁移。\n章节名: ${newChapter.title}\n新URL: ${newChapter.url}\n新前缀: $newPrefix")
            }
        }

        // 执行重命名迁移
        if (renameMap.isNotEmpty()) {
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                val dotIndex = name.lastIndexOf('.')
                if (dotIndex > 0) {
                    val prefix = name.substring(0, dotIndex)
                    if (prefix.matches(prefixPattern)) {
                        val suffix = name.substring(dotIndex)
                        renameMap[prefix]?.let { newPrefix ->
                            val newFile = File(cacheDir, newPrefix + suffix)
                            if (!newFile.exists()) {
                                file.renameTo(newFile)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 清除已删除书的缓存 解压缓存
     */
    suspend fun clearInvalidCache() {
        withContext(IO) {
            val bookFolderNames = hashSetOf<String>()
            val originNames = hashSetOf<String>()
            appDb.bookDao.all.forEach {
                clearComicCache(it)
                bookFolderNames.add(it.getFolderName())
                if (it.isEpub) originNames.add(it.originName)
            }
            arrayOf(cacheFolderName, io.legado.app.model.AudioCache.cacheFolderName).forEach { folder ->
                downloadDir.getFile(folder)
                    .listFiles()?.forEach { bookFile ->
                        if (!bookFolderNames.contains(bookFile.name)) {
                            FileUtils.delete(bookFile.absolutePath)
                        }
                    }
            }
            downloadDir.getFile(cacheEpubFolderName)
                .listFiles()?.forEach { epubFile ->
                    if (!originNames.contains(epubFile.name)) {
                        FileUtils.delete(epubFile.absolutePath)
                    }
                }
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete("$filesDir/shareBookSource.json")
            FileUtils.delete("$filesDir/shareRssSource.json")
            FileUtils.delete("$filesDir/books.json")
        }
    }

    //清除已经看过的漫画数据
    private fun clearComicCache(book: Book) {
        //只处理漫画
        //为0的时候，不清除已缓存数据
        if (!book.isImage || AppConfig.imageRetainNum == 0) {
            return
        }
        //向前保留设定数量，向后保留预下载数量
        val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
        val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)
        val imgNames = hashSetOf<String>()
        //获取需要保留章节的图片信息
        chapterList.forEach {
            val content = getContent(book, it)
            if (content != null) {
                val matcher = AppPattern.imgPattern.matcher(content)
                while (matcher.find()) {
                    val src = matcher.group(1) ?: continue
                    val mSrc = NetworkUtils.getAbsoluteURL(it.url, src)
                    imgNames.add("${MD5Utils.md5Encode16(mSrc)}.${getImageSuffix(mSrc)}")
                }
            }
        }
        downloadDir.getFile(
            book.cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        ).listFiles()?.forEach { imgFile ->
            if (!imgNames.contains(imgFile.name)) {
                imgFile.delete()
            }
        }
    }

    suspend fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        try {
            saveText(book, bookChapter, content)
            //saveImages(bookSource, book, bookChapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
        }
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        if (content.isEmpty()) return
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            book.cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName(),
        ).writeText(content)
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
                emit(mSrc)
            }
        }
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        if (isImageExist(book, src)) {
            return
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (isImageExist(book, src)) {
                return
            }
            val analyzeUrl = AnalyzeUrl(
                src, source = bookSource, coroutineContext = currentCoroutineContext()
            )
            val bytes = analyzeUrl.getByteArrayAwait()
            //某些图片被加密，需要进一步解密
            runScriptWithContext {
                ImageUtils.decode(
                    src, bytes, isCover = false, bookSource, book
                )
            }?.let {
                if (!checkImage(it)) {
                    // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                    // 所以无论如何都要将数据写入到文件里
                    // throw NoStackTraceException("数据异常")
                    AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                }
                writeImage(book, src, it)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val msg = "${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}"
            AppLog.put(msg, e)
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    fun getImage(book: Book, src: String): File {
        return downloadDir.getFile(
            book.cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName,
            "${MD5Utils.md5Encode16(src)}.${getImageSuffix(src)}"
        )
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        getImage(book, src).createFileIfNotExist().writeBytes(bytes)
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                LocalBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(book.cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        if (book.isAudio) {
            return io.legado.app.model.AudioCache.isCached(book, bookChapter)
        }
        else{
            return if (book.isLocalTxt ||
                (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
            ) {
                true
            } else {
                downloadDir.exists(
                    cacheFolderName,
                    book.getFolderName(),
                    bookChapter.getFileName()
                )
            }
        }
    }

    /**
     * 检测图片是否下载
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        var ret = true
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        getContent(book, bookChapter)?.let {
            val matcher = AppPattern.imgPattern.matcher(it)
            while (matcher.find()) {
                val src = matcher.group(1)!!
                val image = getImage(book, src)
                if (!image.exists()) {
                    ret = false
                    continue
                }
                BitmapFactory.decodeFile(image.absolutePath, op)
                if (op.outWidth < 1 && op.outHeight < 1) {
                    if (SvgUtils.getSize(image.absolutePath) != null) {
                        continue
                    }
                    ret = false
                    image.delete()
                }
            }
        }
        return ret
    }

    private fun checkImage(bytes: ByteArray): Boolean {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            return SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
        }
        return true
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val file = downloadDir.getFile(
            book.cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            downloadDir,
            book.cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        ).delete()
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                book.cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                book.cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            book.cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    private val jaccardSimilarity by lazy {
        JaccardSimilarity()
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = jaccardSimilarity.apply(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int {
        return oldBook.run {
            getDurChapter(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

    private val chapterNamePattern1 by lazy {
        Pattern.compile(
            ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
        )
    }

    @Suppress("RegExpSimplifiable")
    private val chapterNamePattern2 by lazy {
        Pattern.compile(
            "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
        )
    }

    private val regexA by lazy {
        return@lazy "\\s".toRegex()
    }

    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
        return StringUtils.stringToInt(
            (
                    chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                        ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                    )?.group(1)
                ?: "-1"
        )
    }

    private val regexOther by lazy {
        // 所有非字母数字中日韩文字 CJK区+扩展A-F区
        @Suppress("RegExpDuplicateCharacterInClass")
        return@lazy "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
        return@lazy "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    private val regexC by lazy {
        //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
        return@lazy "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    private fun getPureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }

}
