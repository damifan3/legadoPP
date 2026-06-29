/**
 * 定时任务协议处理器
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/model/AutoTaskProtocol.kt
 * 作用：解析 JS 脚本返回的结果，执行对应的动作（刷新目录、发送通知、自动购买等）。
 *
 * 支持的动作类型：
 * - refreshToc: 刷新书籍目录，检测新章节，可选自动购买新章节
 * - notify: 发送自定义通知
 *
 * 自动购买逻辑（新增功能）：
 * 当 refreshToc 动作配置了 purchase.enable = true 时，
 * 仅对【新增章节】执行购买操作，不会遍历所有未购买章节。
 */
package io.legado.app.model

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.api.controller.BookController
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isTrue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoTaskProtocol {

    /**
     * 处理结果封装类
     * @param handled 是否成功处理
     * @param summary 汇总摘要
     * @param details 详细信息列表
     */
    data class HandleResult(
        val handled: Boolean,
        val summary: String? = null,
        val details: List<String> = emptyList()
    )

    /**
     * 处理 JS 脚本返回的结果
     * @param result JS 返回值（可以是 String 或对象）
     * @param context Android Context
     * @param taskName 任务名称（用于通知）
     * @param logger 日志回调（可选）
     * @return 处理结果
     */
    suspend fun handle(
        result: Any?,
        context: Context,
        taskName: String? = null,
        logger: ((String) -> Unit)? = null
    ): HandleResult {
        // 解析动作列表
        val actions = parseActions(result) ?: return HandleResult(false)
        val summaries = mutableListOf<String>()

        // 依次执行每个动作
        for (action in actions) {
            //summary 是 handleAction 执行完每个动作返回的结果
            val summary = handleAction(action, context, taskName)
            if (summary.isNotBlank()) {
                summaries.add(summary)
                logger?.invoke(summary)
            }
        }

        val merged = summaries.joinToString(" | ").ifBlank { null }
        //本身 summary 就是 handleAction 执行完每个动作返回的结果
        //此处又将其送入 HandleResult 二次处理
        return HandleResult(true, merged, summaries)
    }

    /**
     * 处理单个动作
     * @param action 动作配置（键值对）
     * @param context Android Context
     * @param taskName 任务名称
     * @return 执行结果摘要
     */
    private fun handleAction(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val type = getString(action, "type")?.lowercase(Locale.ROOT).orEmpty()
        return when (type) {
            "refreshtoc" -> handleRefreshToc(action, context)
            "notify" -> handleNotify(action, context, taskName)
            else -> throw IllegalArgumentException("未知动作: $type")
        }
    }

    /**
     * 处理刷新目录动作
     *
     * 功能说明：
     * 1. 刷新指定书籍的章节目录
     * 2. 检测新增章节数量
     * 3. 可选：发送更新通知
     * 4. 可选：缓存新章节内容
     * 5. 可选：自动购买新增的 VIP 章节（仅新增章节，不遍历全部）
     *
     * @param action 动作配置
     * @param context Android Context
     * @return 执行结果摘要
     */
    private fun handleRefreshToc(action: Map<String, Any?>, context: Context): String {
        // 获取书籍 URL
        val bookUrl = getString(action, "bookUrl")?.trim().orEmpty()
        if (bookUrl.isBlank()) return "refreshToc 缺少 bookUrl"

        // 查找书籍
        val book = appDb.bookDao.getBook(bookUrl) ?: return "refreshToc 未找到书籍"

        // 记录刷新前的章节列表（用于后续计算新增章节）
        val beforeList = appDb.bookChapterDao.getChapterList(bookUrl)
        val beforeUrls = beforeList.map { it.url }.toSet()

        // 执行目录刷新
        val refresh = BookController.refreshToc(mapOf("url" to listOf(bookUrl)))
        if (!refresh.isSuccess) {
            return "《${book.name}》更新失败: ${refresh.errorMsg}"
        }

        // 获取刷新后的章节列表
        val afterList = appDb.bookChapterDao.getChapterList(bookUrl)
        
        // 找出新增的章节（本次刷新新出现的章节）
        val newChapters = afterList.filter { it.url !in beforeUrls }
        val newCount = newChapters.size

        // 解析通知配置
        val notifyObj = getMap(action, "notify")
        val notifyEnabled = notifyObj?.let { getBoolean(it, "enable", defaultValue = true) } ?: false
        val notifyMin = notifyObj?.let { getInt(it, "minCount") } ?: 1

        // 解析缓存配置
        val cacheObj = getMap(action, "cache")
        val cacheEnabled = cacheObj?.let { getBoolean(it, "enable", defaultValue = false) } ?: false

        // 解析自动购买配置（新增功能）
        val purchaseObj = getMap(action, "purchase")
        val purchaseEnabled = purchaseObj?.let { getBoolean(it, "enable", defaultValue = false) } ?: false
        val purchaseMaxCount = purchaseObj?.let { getInt(it, "maxCount") } ?: 10

        // 判断是否需要执行各项操作
        //如果更新章节数大于最小通知数 且 大于0，才应该通知
        val shouldNotify = notifyEnabled && newCount >= notifyMin && newCount > 0
        val shouldCache = cacheEnabled && newCount > 0
        val shouldPurchase = purchaseEnabled && newCount > 0

        // 获取最新章节标题
        val latestTitle = getLatestChapterTitle(afterList)
        val titleTpl = notifyObj?.let { getString(it, "title") }
        val contentTpl = notifyObj?.let { getString(it, "content") }

        // 自动购买新增章节（仅新增章节，不遍历全部未购买章节）
        var purchaseCount = 0
        var purchaseFailCount = 0
        if (shouldPurchase) {
            // 获取书源（Book.origin 字段存储书源URL，见 Book.kt 第46-48行注释）
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            val payAction = source?.getContentRule()?.payAction

            if (!payAction.isNullOrBlank()) {
                // 找出新增的VIP且未购买章节
                val purchaseChapters = newChapters
                    .filter { it.isVip && !it.isPay }   // 仅 VIP 且未购买的
                    .take(purchaseMaxCount)              // 限制最大购买数量

                // 遍历新增章节执行购买
                for (chapter in purchaseChapters) {
                    try {
                        // 执行书源的支付动作 JS（使用 BaseSource.evalJS）
                        val result = source.evalJS(payAction) {
                            this["book"] = book
                            this["chapter"] = chapter
                            this["title"] = chapter.title
                            this["baseUrl"] = chapter.url
                        }

                        // 检查购买结果（evalJS 返回 Any?，需要转为 String 再判断）
                        if (result?.toString().isTrue()) {
                            // 更新章节状态为已购买
                            appDb.bookChapterDao.update(chapter.copy(isPay = true))
                            purchaseCount++
                        } else {
                            purchaseFailCount++
                        }
                    } catch (e: Exception) {
                        purchaseFailCount++
                        AppLog.put("自动购买失败: ${chapter.title}", e)
                    }
                }
            }

            if (purchaseCount > 0) {
                // 购买后再次刷新目录（防止购买前后章节名不同）
                val refreshAgain = BookController.refreshToc(mapOf("url" to listOf(bookUrl)))
                if (!refreshAgain.isSuccess) {
                    AppLog.put("《${book.name}》购买后更新目录失败: ${refreshAgain.errorMsg}")
                }
            }
        }

        // 缓存新章节
        var cacheCount = 0
        if (shouldCache && !book.isLocal && newChapters.isNotEmpty()) {
            val indices = newChapters.map { it.index }.sorted()
            var start = indices.first()
            var end = start
            for (i in 1 until indices.size) {
                val current = indices[i]
                if (current == end + 1) {
                    end = current
                } else {
                    CacheBook.start(context, book, start, end)
                    start = current
                    end = current
                }
            }
            CacheBook.start(context, book, start, end)
            cacheCount = newChapters.size
        }

        // 发送更新及购买结果通知
        if (shouldNotify) {
            notifyBookUpdate(
                context = context,
                book = book,
                newCount = newCount,
                latestTitle = latestTitle,
                titleTpl = titleTpl,
                contentTpl = contentTpl,
                purchaseCount = purchaseCount,
                purchaseFailCount = purchaseFailCount
            )
        }

        return buildSummary(book, newCount, shouldNotify, cacheCount, purchaseCount, purchaseFailCount)
    }

    /**
     * 处理通知动作
     * @param action 动作配置
     * @param context Android Context
     * @param taskName 任务名称
     * @return 执行结果摘要
     */
    private fun handleNotify(
        action: Map<String, Any?>,
        context: Context,
        taskName: String?
    ): String {
        val titleTpl = getString(action, "title")
        val contentTpl = getString(action, "content")
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

        val title = formatCommonTemplate(
            titleTpl ?: context.getString(R.string.auto_task_notify_title),
            taskName,
            time
        )
        val content = formatCommonTemplate(
            contentTpl ?: context.getString(R.string.auto_task_notify_content, taskName ?: ""),
            taskName,
            time
        )

        // 解析通知优先级
        val level = getString(action, "level")?.lowercase(Locale.ROOT).orEmpty()
        val priority = when (level) {
            "high", "error", "fail", "failed" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        // 计算通知 ID
        val notifyId = getInt(action, "id")?.let {
            NotificationId.AutoTaskNotifyBase + (it and 0x7fff)
        } ?: run {
            val key = "${taskName.orEmpty()}|$title|$content"
            NotificationId.AutoTaskNotifyBase + (key.hashCode() and 0x7fff)
        }

        // 构建并发送通知
        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(priority)
            .build()
        NotificationManagerCompat.from(context).notify(notifyId, notification)

        return "通知: $title"
    }

    /**
     * 发送书籍更新通知
     */
    private fun notifyBookUpdate(
        context: Context,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        titleTpl: String?,
        contentTpl: String?,
        purchaseCount: Int = 0,
        purchaseFailCount: Int = 0
    ) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

        val defaultTitle = context.getString(R.string.auto_task_book_update_title, book.name)
        val defaultContent = if (latestTitle.isNullOrBlank()) {
            context.getString(R.string.auto_task_book_update_content_count, newCount)
        } else {
            context.getString(R.string.auto_task_book_update_content, newCount, latestTitle)
        }

        val title = formatTemplate(titleTpl ?: defaultTitle, book, newCount, latestTitle, time, purchaseCount, purchaseFailCount)
        var content = formatTemplate(contentTpl ?: defaultContent, book, newCount, latestTitle, time, purchaseCount, purchaseFailCount)

        //增加的购买信息必须放在 contentTpl ?: defaultContent 后边，不然会被用户定义模板取代。
//        if (purchaseCount > 0 ) {
//            content += "\n自动购买成功: ${purchaseCount}章"
//        }
//        if (purchaseFailCount > 0 ) {
//            content += "\n自动购买失败: ${purchaseFailCount}章"
//        }

        // 计算通知 ID（基于书籍 URL 哈希）
        val notifyId = NotificationId.AutoTaskBookUpdateBase +
            (book.bookUrl.hashCode() and 0x7fffffff) % 10000

        val notification = NotificationCompat.Builder(context, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notifyId, notification)
    }

    /**
     * 构建执行结果摘要
     * 包含：书籍名称、新增章节数、通知状态、缓存数量、购买成功/失败数量
     */
    private fun buildSummary(
        book: Book,
        newCount: Int,
        notified: Boolean,
        cacheCount: Int,
        purchaseCount: Int,
        purchaseFailCount: Int
    ): String {
        val name = if (book.name.isBlank()) book.bookUrl else book.name
        val parts = mutableListOf("《$name》")

        if (newCount > 0) {
            parts.add("+$newCount")
        } else {
            parts.add("无更新")
        }

        // 添加购买结果
        if (purchaseCount > 0) {
            parts.add("购买成功$purchaseCount")
        }
        if (purchaseFailCount > 0) {
            parts.add("购买失败$purchaseFailCount")
        }

        if (notified) parts.add("通知")
        if (cacheCount > 0) parts.add("缓存$cacheCount")

        return parts.joinToString(" ")
    }

    /**
     * 获取最新章节标题（排除卷名）
     */
    private fun getLatestChapterTitle(list: List<BookChapter>): String? {
        return list.lastOrNull { !it.isVolume }?.title ?: list.lastOrNull()?.title
    }

    // ==================== JSON 解析辅助方法 ====================

    /**
     * 解析动作列表
     */
    private fun parseActions(result: Any?): List<Map<String, Any?>>? {
        if (result == null) return null
        return when (result) {
            is String -> parseActionsFromJson(result)
            else -> {
                val json = runCatching { GSON.toJson(result) }.getOrNull()
                if (json.isNullOrBlank()) null else parseActionsFromJson(json)
            }
        }
    }

    /**
     * 从 JSON 字符串解析动作列表
     */
    private fun parseActionsFromJson(text: String): List<Map<String, Any?>>? {
        val trimmed = text.trim()
        return when {
            //对应这种格式 JSON数组
            // [{type:"refreshtoc", cache: {enable: true}}, {type:"refreshtoc"}]
            trimmed.isJsonArray() -> {
                val list = GSON.fromJsonArray<Map<String, Any?>>(trimmed).getOrNull()
                list?.mapNotNull { ensureStringKeyMap(it) }
            }
            /*
            对应这两种格式：
            结构 B（返回包含多个动作的 JSON 对象）：
            json
            {
              "actions": [
                { "type": "refreshtoc", "bookUrl": "..." },
                { "type": "notify", "content": "..." }
              ]
            }
            结构 C（仅返回单一动作的 JSON 对象）：
            json
            { "type": "refreshtoc", "bookUrl": "..." }
             */
            trimmed.isJsonObject() -> {
                val map = GSON.fromJsonObject<Map<String, Any?>>(trimmed).getOrNull()
                mapToActions(map)
            }
            else -> null
        }
    }

    /**
     * 将根对象转换为动作列表
     */
    private fun mapToActions(root: Map<String, Any?>?): List<Map<String, Any?>>? {
        if (root == null) return null
        val normalized = ensureStringKeyMap(root) ?: return null

        //将 actions 字段取出
        val actionsValue = normalized["actions"]
        return when (actionsValue) {
            //包含 actions 字段的分支
            is List<*> -> actionsValue.mapNotNull { ensureStringKeyMap(it) }
            //不包含 actions 字段的分支，直接就是一个动作（包含 type 字段）
            else -> if (normalized.containsKey("type")) listOf(normalized) else null
        }
    }

    /**
     * 确保 Map 的键为 String 类型
     */
    private fun ensureStringKeyMap(value: Any?): Map<String, Any?>? {
        return when (value) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                value.forEach { (k, v) ->
                    if (k != null) out[k.toString()] = v
                }
                out
            }
            else -> null
        }
    }

    // ==================== 类型获取辅助方法 ====================

    private fun getString(map: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = getValueIgnoreCase(map, key)
            val str = value?.toString()?.trim()
            if (!str.isNullOrBlank()) return str
        }
        return null
    }

    private fun getInt(map: Map<String, Any?>, vararg keys: String): Int? {
        for (key in keys) {
            val value = getValueIgnoreCase(map, key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun getBoolean(
        map: Map<String, Any?>,
        key: String,
        defaultValue: Boolean
    ): Boolean {
        val value = getValueIgnoreCase(map, key)
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isTrue(defaultValue)
            else -> defaultValue
        }
    }

    private fun getMap(map: Map<String, Any?>, key: String): Map<String, Any?>? {
        val value = getValueIgnoreCase(map, key)
        return ensureStringKeyMap(value)
    }

    private fun getValueIgnoreCase(map: Map<String, Any?>, key: String): Any? {
        map[key]?.let { return it }
        return map.entries.firstOrNull { it.key.equals(key, true) }?.value
    }

    // ==================== 模板格式化方法 ====================

    /**
     * 格式化书籍更新模板，将用户脚本中的变量替换为真实值
     * 支持变量：{book}, {author}, {newCount}, {chapter}, {time}
     */
    private fun formatTemplate(
        template: String,
        book: Book,
        newCount: Int,
        latestTitle: String?,
        time: String,
        purchaseCount: Int,
        purchaseFailCount: Int
    ): String {
        return template
            .replace("{book}", book.name)
            .replace("{author}", book.author)
            .replace("{newCount}", newCount.toString())
            .replace("{chapter}", latestTitle.orEmpty())
            .replace("{time}", time)
            .replace("{purchaseCount}", purchaseCount.toString())
            .replace("{purchaseFailCount}", purchaseFailCount.toString())
    }

    /**
     * 格式化通用模板
     * 支持变量：{task}, {time}
     */
    private fun formatCommonTemplate(
        template: String,
        taskName: String?,
        time: String
    ): String {
        return template
            .replace("{task}", taskName.orEmpty())
            .replace("{time}", time)
    }
}