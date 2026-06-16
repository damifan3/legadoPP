/**
 * 定时任务管理工具类
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/model/AutoTask.kt
 * 作用：提供定时任务的启动、停止、调度刷新等管理功能，
 *       以及任务规则的 CRUD 操作封装。
 *
 * 主要功能：
 * - start/stop: 启动/停止定时任务服务
 * - refreshSchedule: 刷新任务调度时间表
 * - buildSource: 为 JS 执行构建虚拟书源
 * - getRules/saveRules/upsert/delete: 任务规则的增删改查
 */
package io.legado.app.model

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.service.AutoTaskService
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

object AutoTask {

    // 书源 URL 前缀，用于标识定时任务虚拟书源
    const val SOURCE_KEY = "auto_task"

    // 书籍更新任务 ID 前缀
    private const val BOOK_TASK_PREFIX = "book_update:"

    // 旧版缓存键（用于数据迁移）
    private const val KEY_RULES = "autoTaskRules"

    // 默认 Cron 表达式：每 30 分钟执行一次
    const val DEFAULT_CRON = "*/30 * * * *"

    /**
     * 根据书籍 URL 生成对应的定时任务 ID
     * @param bookUrl 书籍 URL
     * @return 任务 ID（格式：book_update:MD5哈希）
     */
    fun bookTaskId(bookUrl: String): String {
        return BOOK_TASK_PREFIX + MD5Utils.md5Encode16(bookUrl)
    }

    /**
     * 规范化脚本内容
     * 移除 @js: 或 <js></js> 包装，返回纯 JS 代码
     * @param script 原始脚本
     * @return 规范化后的 JS 代码
     */
    fun normalizeScript(script: String): String {
        val trimmed = script.trim()
        return when {
            // 处理 @js: 前缀
            trimmed.startsWith("@js:", true) -> trimmed.substring(4).trim()
            // 处理 <js>...</js> 包装
            trimmed.startsWith("<js>", true) && trimmed.contains("</") ->
                trimmed.substring(4, trimmed.lastIndexOf("<")).trim()
            else -> trimmed
        }
    }

    /**
     * 启动定时任务服务
     * @param context Android Context
     */
    fun start(context: Context) {
        AutoTaskService.start(context)
    }

    /**
     * 停止定时任务服务
     * @param context Android Context
     */
    fun stop(context: Context) {
        AutoTaskService.stop(context)
    }

    /**
     * 刷新任务调度时间表
     * 如果服务未启用则不执行任何操作
     * @param context Android Context（默认使用应用 Context）
     */
    fun refreshSchedule(context: Context = appCtx) {
        // 检查用户是否启用了定时任务服务
        if (!context.getPrefBoolean(PreferKey.autoTaskService)) return
        AutoTaskService.refresh(context)
    }

    /**
     * 为 JS 执行构建虚拟书源
     * 定时任务执行 JS 时需要一个书源对象来提供请求配置
     * @param task 定时任务规则
     * @return 配置好的虚拟书源对象
     */
    fun buildSource(task: AutoTaskRule): BookSource {
        return BookSource(
            bookSourceUrl = "${SOURCE_KEY}:${task.id}",
            bookSourceName = task.name
        ).apply {
            // 继承任务规则中的配置
            jsLib = task.jsLib
            header = task.header
            loginUrl = task.loginUrl
            loginUi = task.loginUi
            loginCheckJs = task.loginCheckJs
        }
    }

    /**
     * 获取所有定时任务规则
     * 如果数据库为空，尝试从旧版缓存迁移数据
     * @return 任务规则列表（可变）
     */
    @Synchronized
    fun getRules(): MutableList<AutoTaskRule> {
        val rules = appDb.autoTaskRuleDao.all().toMutableList()
        if (rules.isEmpty()) {
            // 尝试从旧 CacheManager 迁移数据
            migrateFromCache()?.let { rules.addAll(it) }
        }
        return rules
    }

    /**
     * 保存任务规则列表（覆盖式保存）
     * 保存后自动刷新调度时间表
     * @param list 要保存的任务规则列表
     * @param refresh 是否刷新调度（默认 true）
     */
    @Synchronized
    fun saveRules(list: List<AutoTaskRule>, refresh: Boolean = true) {
        // 先清空现有数据
        appDb.autoTaskRuleDao.deleteAll()
        // 插入新数据
        if (list.isNotEmpty()) {
            appDb.autoTaskRuleDao.insert(*list.toTypedArray())
        }
        // 清除旧缓存中的副本（如果存在）
        io.legado.app.help.CacheManager.delete(KEY_RULES)
        if (refresh) {
            refreshSchedule()
        }
    }

    /**
     * 插入或更新单个任务规则
     * 根据 ID 判断是插入还是更新
     * @param rule 要保存的任务规则
     */
    @Synchronized
    fun upsert(rule: AutoTaskRule) {
        val existing = appDb.autoTaskRuleDao.getById(rule.id)
        if (existing != null) {
            appDb.autoTaskRuleDao.update(rule)
        } else {
            appDb.autoTaskRuleDao.insert(rule)
        }
        refreshSchedule()
    }

    /**
     * 删除指定 ID 的任务规则
     * @param ids 要删除的任务 ID 列表
     */
    @Synchronized
    fun delete(vararg ids: String) {
        ids.forEach { appDb.autoTaskRuleDao.delete(it) }
        refreshSchedule()
    }

    /**
     * 更新指定 ID 的任务规则
     * @param id 任务 ID
     * @param updater 更新函数，接收旧规则返回新规则
     * @return 更新后的规则，如果任务不存在则返回 null
     */
    @Synchronized
    fun update(id: String, updater: (AutoTaskRule) -> AutoTaskRule): AutoTaskRule? {
        val existing = appDb.autoTaskRuleDao.getById(id) ?: return null
        val updated = updater(existing)
        appDb.autoTaskRuleDao.update(updated)
        return updated
    }

    /**
     * 从旧版 CacheManager 迁移数据到 Room 数据库
     * 用于兼容旧版本用户数据
     * @return 迁移成功则返回规则列表，否则返回 null
     */
    private fun migrateFromCache(): MutableList<AutoTaskRule>? {
        val json = io.legado.app.help.CacheManager.get(KEY_RULES) ?: return null
        val rules = GSON.fromJsonArray<AutoTaskRule>(json).getOrNull()?.toMutableList()
            ?: return null
        // 写入数据库
        appDb.autoTaskRuleDao.insert(*rules.toTypedArray())
        // 删除旧缓存
        io.legado.app.help.CacheManager.delete(KEY_RULES)
        return rules
    }
}