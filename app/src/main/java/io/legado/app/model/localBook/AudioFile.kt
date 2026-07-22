package io.legado.app.model.localBook

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.list

/**
 * 本地有声书文件解析器
 * 负责处理将本地音频文件夹解析为一本多章节的书籍，或将单音频文件解析为一本书。
 */
object AudioFile {

    /**
     * 根据本地书籍的 URI 获取章节列表
     * @param book 书籍对象
     * @return 章节列表（包含每一集音频的信息）
     */
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val uri = book.getLocalUri()

        //通过后缀判断是否是 Dir
        val isDir = !book.originName.matches(AppPattern.audioFileRegex)
        val fileDoc = FileDoc.fromUri(uri, isDir)
        val chapterList = ArrayList<BookChapter>()

        if (fileDoc.isDir) {
            // 如果是文件夹（多集有声书），过滤出所有符合格式的音频文件
            val docList = fileDoc.list { item ->
                !item.name.startsWith(".") && !item.isDir && item.name.matches(AppPattern.audioFileRegex)
            } ?: emptyList()

            // 对音频文件进行自然的字母数字排序，确保 01, 02.. 10 等顺序正确
            val sortedDocs = docList.sortedWith(compareBy(AlphanumComparator) { it.name })

            // 将每个音频文件转换成一个书籍章节（BookChapter）
            sortedDocs.forEachIndexed { index, doc ->
                val chapter = BookChapter(
                    bookUrl = book.bookUrl,
                    title = doc.name.substringBeforeLast("."), // 使用不带后缀的文件名作为章节名
                    url = doc.toString(),
                    index = index
                )
                chapterList.add(chapter)
            }
        } else {
            // 如果是单文件有声书，直接将文件本身作为唯一章节
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                title = book.name,
                url = book.bookUrl,
                index = 0
            )
            chapterList.add(chapter)
        }

        return chapterList
    }
}
