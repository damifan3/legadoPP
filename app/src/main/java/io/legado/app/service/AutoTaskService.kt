/**
 * 定时任务后台服务
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/service/AutoTaskService.kt
 * 作用：后台定时执行任务规则，支持 Cron 表达式调度。
 *
 * 主要功能：
 * - 解析 Cron 表达式，计算下次执行时间
 * - 使用协程定时检查并执行到期任务
 * - 支持 Android 15 (API 35) 前台服务限制（使用 AlarmManager）
 * - 显示前台通知，保持服务存活
 * - 执行 JS 脚本并处理返回结果
 *
 * Android 15 适配说明：
 * - Android 15 (VANILLA_ICE_CREAM) 对前台服务有严格限制
 * - 使用 AlarmManager 设置精确闹钟，在任务到期时唤醒服务
 * - 服务类型使用 SHORT_SERVICE 或 DATA_SYNC
 */
package io.legado.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.EventBus
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskService : BaseService() {

    companion object {
        // AlarmManager 请求码
        private const val ALARM_REQUEST_CODE = 100108

        // 数据同步空闲检查间隔（毫秒）
        private const val DATA_SYNC_IDLE_CHECK_MS = 60_000L

        // 数据同步最小延迟（毫秒）
        private const val DATA_SYNC_MIN_DELAY_MS = 1_000L

        // 首次运行宽限时间（毫秒）- 用于处理 lastRunAt 为 0 的情况
        private const val FIRST_RUN_GRACE_MS = 5 * 60_000L

        // 日志分隔符
        private const val LOG_SEPARATOR = "\n====================\n"

        // 服务是否正在运行
        var isRun = false
            private set

        /**
         * 启动定时任务服务
         * @param context Android Context
         */
        fun start(context: Context) {
            dispatchForeground(context, IntentAction.start)
        }

        /**
         * 停止定时任务服务
         * @param context Android Context
         */
        fun stop(context: Context) {
            context.startService<AutoTaskService> {
                action = IntentAction.stop
            }
        }

        /**
         * 刷新任务调度时间表
         * @param context Android Context
         */
        fun refresh(context: Context) {
            dispatchForeground(context, IntentAction.refreshSchedule)
        }

        /**
         * 分发前台服务启动请求
         * Android 8.0+ 需要使用 startForegroundService
         */
        private fun dispatchForeground(
            context: Context,
            action: String
        ) {
            val intent = Intent(context, AutoTaskService::class.java).apply {
                this.action = action
            }
            context.startForegroundServiceCompat(intent)
        }
    }

    // 通知内容文本
    private var notificationContent = appCtx.getString(R.string.service_starting)

    // 时间格式化器
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 最大日志长度
    private val maxLogLength = 4000

    // 任务锁，防止并发执行
    private val taskLock = Mutex()

    // 数据同步循环任务
    private var dataSyncLoopJob: Job? = null

    // 通知构建器（懒加载）
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdAutoTask)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.auto_task_service))
            .setContentText(notificationContent)
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<AutoTaskService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    /**
     * 处理服务启动命令
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理停止命令
        if (intent?.action == IntentAction.stop) {
            putPrefBoolean(PreferKey.autoTaskService, false)
            dataSyncLoopJob?.cancel()
            dataSyncLoopJob = null
            cancelNextAlarm()
            postEvent(EventBus.AUTO_TASK, false)// [NEW] 发送实时状态通知
            stopSelf()
            return START_NOT_STICKY
        }

        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) {
            return result
        }

        // Android 15+ 使用 AlarmManager 模式
        if (useAlarmFgsMode()) {
            runDueAndReschedule(startId)
        } else {
            // 旧版本使用持续前台服务模式
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            cancelNextAlarm()
            runDueNoReschedule()
            ensureDataSyncLoopRunning()
        }
        return result
    }

    /**
     * 服务销毁时清理资源
     */
    override fun onDestroy() {
        isRun = false
        dataSyncLoopJob?.cancel()
        dataSyncLoopJob = null
        super.onDestroy()
        notificationManager.cancel(NotificationId.AutoTaskService)
    }

    /**
     * Android 15+ 模式：执行到期任务并调度下次运行
     */
    private fun runDueAndReschedule(startId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                cancelNextAlarm()
                stopSelfResult(startId)
                return@launch
            }
            taskLock.withLock {
                isRun = true
                try {
                    processDueTasks()
                    scheduleNextRunFromRules()
                } finally {
                    isRun = false
                }
            }
            stopSelfResult(startId)
        }
    }

    /**
     * 旧版本模式：仅执行到期任务（不调度，由循环处理）
     */
    private fun runDueNoReschedule() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!getPrefBoolean(PreferKey.autoTaskService)) {
                stopSelf()
                return@launch
            }
            taskLock.withLock {
                isRun = true
                try {
                    processDueTasks()
                } finally {
                    isRun = false
                }
            }
        }
    }

    /**
     * 确保数据同步循环正在运行
     */
    private fun ensureDataSyncLoopRunning() {
        if (dataSyncLoopJob?.isActive == true) return
        if (!hasEnabledRule()) {
            stopSelf()
            return
        }
        dataSyncLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            AppLog.put("AutoTask dataSync loop started")
            while (isActive && !useAlarmFgsMode()) {
                if (!getPrefBoolean(PreferKey.autoTaskService)) {
                    break
                }
                val rules = AutoTask.getRules()
                if (!hasEnabledRule(rules)) {
                    break
                }
                val delayMs = computeDataSyncDelayMs(rules)
                delay(delayMs)
                if (!getPrefBoolean(PreferKey.autoTaskService)) {
                    break
                }
                taskLock.withLock {
                    isRun = true
                    try {
                        processDueTasks()
                    } finally {
                        isRun = false
                    }
                }
            }
            AppLog.put("AutoTask dataSync loop stopped")
            dataSyncLoopJob = null
            if (!getPrefBoolean(PreferKey.autoTaskService) || !hasEnabledRule()) {
                stopSelf()
            }
        }
    }

    /**
     * 计算数据同步延迟时间
     */
    private fun computeDataSyncDelayMs(rules: List<AutoTaskRule>): Long {
        val nextRunAt = computeNextRunAt(rules) ?: return DATA_SYNC_IDLE_CHECK_MS
        val delayMs = nextRunAt - System.currentTimeMillis()
        return delayMs.coerceAtLeast(DATA_SYNC_MIN_DELAY_MS)
    }

    /**
     * 处理到期的任务
     */
    private suspend fun processDueTasks() {
        val rules = AutoTask.getRules()
        if (rules.isEmpty()) {
            notificationContent = getString(R.string.auto_task_no_task)
            upNotification()
            if (useAlarmFgsMode()) {
                cancelNextAlarm()
            }
            return
        }

        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) {
            notificationContent = getString(R.string.auto_task_no_enabled)
            upNotification()
            if (useAlarmFgsMode()) {
                cancelNextAlarm()
            }
            return
        }

        val now = System.currentTimeMillis()
        var hasDueTask = false

        enabled.forEach { task ->
            val cron = task.cron?.trim().orEmpty()
            if (cron.isBlank()) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }

            val schedule = CronSchedule.parse(cron)
            if (schedule == null) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }

            val baseTime = resolveBaseTime(task.lastRunAt, now)
            val nextRun = schedule.nextTimeAfter(baseTime)
            if (nextRun == null) {
                updateCronError(task, getString(R.string.auto_task_cron_invalid))
                return@forEach
            }

            // 检查任务是否到期
            if (nextRun <= now) {
                hasDueTask = true
                runTask(task)
            }
        }

        if (!hasDueTask) {
            notificationContent = getString(R.string.auto_task_running_state)
            upNotification()
        }
    }

    /**
     * 根据任务规则调度下次运行
     */
    private fun scheduleNextRunFromRules() {
        if (!useAlarmFgsMode()) return
        val nextRunAt = computeNextRunAt(AutoTask.getRules())
        if (nextRunAt == null) {
            cancelNextAlarm()
            return
        }
        scheduleNextAlarm(nextRunAt)
    }

    /**
     * 计算所有任务的最近下次运行时间
     */
    private fun computeNextRunAt(rules: List<AutoTaskRule>): Long? {
        val enabled = rules.filter { it.enable }
        if (enabled.isEmpty()) return null

        val now = System.currentTimeMillis()
        var nextRunAt: Long? = null

        enabled.forEach { task ->
            val cron = task.cron?.trim().orEmpty()
            if (cron.isBlank()) return@forEach

            val schedule = CronSchedule.parse(cron) ?: return@forEach
            val baseTime = resolveBaseTime(task.lastRunAt, now)
            val next = schedule.nextTimeAfter(baseTime) ?: return@forEach

            val current = nextRunAt
            nextRunAt = if (current == null) next else minOf(current, next)
        }
        return nextRunAt
    }

    /**
     * 使用 AlarmManager 调度下次运行（Android 15+）
     */
    private fun scheduleNextAlarm(triggerAt: Long) {
        if (!useAlarmFgsMode()) return

        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMs = triggerAt.coerceAtLeast(System.currentTimeMillis() + 1000L)
        val pendingIntent = buildAlarmPendingIntent()

        kotlin.runCatching {
            if (alarmManager.canScheduleExactAlarms()) {
                // 有精确闹钟权限，使用精确闹钟
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                // 无精确闹钟权限，使用非精确闹钟
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
            AppLog.put("AutoTask next run at ${timeFormat.format(Date(triggerAtMs))}")
        }.onFailure { error ->
            AppLog.put("AutoTask schedule alarm failed", error)
        }
    }

    /**
     * 取消下次闹钟
     */
    private fun cancelNextAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(buildAlarmPendingIntent())
    }

    /**
     * 判断是否使用 AlarmManager 前台服务模式
     * Android 15 (API 35) 及以上版本需要使用此模式
     */
    private fun useAlarmFgsMode(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    /**
     * 检查是否有启用的规则
     */
    private fun hasEnabledRule(rules: List<AutoTaskRule>): Boolean {
        return rules.any { it.enable }
    }

    private fun hasEnabledRule(): Boolean {
        return hasEnabledRule(AutoTask.getRules())
    }

    /**
     * 解析基准时间
     * 如果 lastRunAt > 0，使用它作为基准；否则使用当前时间减去宽限时间
     */
    private fun resolveBaseTime(lastRunAt: Long, now: Long): Long {
        return if (lastRunAt > 0L) lastRunAt else now - FIRST_RUN_GRACE_MS
    }

    /**
     * 构建 AlarmManager 的 PendingIntent
     */
    private fun buildAlarmPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, AutoTaskService::class.java).apply {
                action = IntentAction.refreshSchedule
            },
            flags
        )
    }

    /**
     * 执行单个任务
     */
    private suspend fun runTask(task: AutoTaskRule) {
        val script = normalizeScript(task.script)
        if (script.isBlank()) {
            val now = System.currentTimeMillis()
            AutoTask.update(task.id) {
                it.copy(
                    lastRunAt = now,
                    lastError = getString(R.string.auto_task_script_empty)
                )
            }
            return
        }

        val source = AutoTask.buildSource(task)
        notificationContent = getString(R.string.auto_task_running, task.name)
        upNotification()

        val startAt = System.currentTimeMillis()
        runCatching {
            source.evalJS(script)
        }.onSuccess { result ->
            try {
                val cost = System.currentTimeMillis() - startAt
                val logLines = mutableListOf<String>()

                // 使用 AutoTaskProtocol 处理脚本执行的结果
                AutoTaskProtocol.handle(result, this, task.name) { msg ->
                    AppLog.put("AutoTask[${task.id}] ${task.name}: $msg")
                    //logLines 其实是 handle 内部 summary；是 handleAction 执行完每个动作返回的结果
                    logLines.add(msg)
                }

                val detail = runCatching {
                    if (result is String) result else io.legado.app.utils.GSON.toJson(result)
                }.getOrNull()?.take(200) ?: result?.toString()?.take(200)
                val logMsg = if (detail.isNullOrBlank()) {
                    "AutoTask[${task.id}] ${task.name} done (${cost}ms)."
                } else {
                    "AutoTask[${task.id}] ${task.name} done (${cost}ms): $detail"
                }
                val msg = if (detail.isNullOrBlank()) {
                    "${task.name} done (${cost}ms)"
                } else {
                    "${task.name} done (${cost}ms): $detail"
                }
                val lastRun = System.currentTimeMillis()
                // detail 应该就是脚本部分执行的返回值
                //logLines 应该是每个动作的执行结果
                val newLog = buildLastLog(logLines, detail, cost, lastRun)

                //这是日志内容（不是右上角的调试）
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = lastRun,
                        lastError = null,
                        lastLog = mergeWithHistory(newLog, it.lastLog)
                    )
                }

                //这是通知栏的常驻通知内容
                notificationContent = msg
                upNotification()
                AppLog.put(logMsg)
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: e.toString()
                val errorLog = buildErrorLog(msg, e, System.currentTimeMillis())
                AutoTask.update(task.id) {
                    it.copy(
                        lastRunAt = System.currentTimeMillis(),
                        lastError = msg,
                        lastLog = mergeWithHistory(errorLog, it.lastLog)
                    )
                }
                notificationContent = getString(R.string.auto_task_failed, msg)
                upNotification()
                AppLog.put("AutoTask[${task.id}] ${task.name} post-process failed: $msg", e)
            }
        }.onFailure { error ->
            val msg = error.localizedMessage ?: error.toString()
            val errorLog = buildErrorLog(msg, error, System.currentTimeMillis())
            AutoTask.update(task.id) {
                it.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastError = msg,
                    lastLog = mergeWithHistory(errorLog, it.lastLog)
                )
            }
            notificationContent = getString(R.string.auto_task_failed, msg)
            upNotification()
            AppLog.put("AutoTask[${task.id}] ${task.name} failed: $msg", error)
        }
    }

    /**
     * 规范化脚本内容
     */
    private fun normalizeScript(script: String): String {
        return AutoTask.normalizeScript(script)
    }

    /**
     * 更新通知
     */
    private fun upNotification() {
        notificationBuilder.setContentText(notificationContent)
        notificationManager.notify(NotificationId.AutoTaskService, notificationBuilder.build())
    }

    /**
     * 更新 Cron 解析错误
     */
    private fun updateCronError(task: AutoTaskRule, message: String) {
        if (task.lastError == message) return
        AutoTask.update(task.id) {
            val now = System.currentTimeMillis()
            val errorLog = buildErrorLog(message, null, now)
            it.copy(lastError = message, lastLog = mergeWithHistory(errorLog, it.lastLog))
        }
    }

    /**
     * 合并新日志与历史日志（保留最近 N 条）
     */
    private fun mergeWithHistory(newLog: String, oldLogHistory: String?, maxCount: Int = 5): String {
        val oldLogs = oldLogHistory?.split(LOG_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
        val combinedLogs = (listOf(newLog) + oldLogs).take(maxCount)
        val resultString = combinedLogs.joinToString(LOG_SEPARATOR)
        return if (resultString.length > maxLogLength * maxCount) {
            resultString.take(maxLogLength * maxCount)
        } else {
            resultString
        }
    }

    /**
     * 构建成功日志
     */
    private fun buildLastLog(
        lines: List<String>,
        detail: String?,
        cost: Long,
        runAt: Long
    ): String {
        val time = formatLogTime(runAt)
        val sb = StringBuilder()
        sb.append("[OK] ").append(time).append('\n')
        sb.append("耗时: ").append(cost).append("ms")
        if (lines.isNotEmpty()) {
            sb.append('\n').append("动作:")
            lines.forEach { line ->
                sb.append('\n').append("- ").append(line)
            }
        }
        if (!detail.isNullOrBlank()) {
            sb.append('\n').append("脚本执行返回: ").append(detail)
        }
        val text = sb.toString().ifBlank { "执行完成" }
        return if (text.length > maxLogLength) text.take(maxLogLength) else text
    }

    /**
     * 构建错误日志
     */
    private fun buildErrorLog(msg: String, error: Throwable?, runAt: Long): String {
        val time = formatLogTime(runAt)
        val detail = error?.stackTraceStr.orEmpty()
        val sb = StringBuilder()
        sb.append("[FAIL] ").append(time).append('\n')
        sb.append("错误: ").append(msg)
        if (detail.isNotBlank()) {
            sb.append('\n').append("堆栈:").append('\n').append(detail)
        }
        val text = sb.toString()
        return if (text.length > maxLogLength) text.take(maxLogLength) else text
    }

    /**
     * 格式化日志时间
     */
    private fun formatLogTime(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    /**
     * 启动前台通知
     * Android 10+ 需要指定服务类型
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需要指定前台服务类型
            // 统一使用 DATA_SYNC，与 Manifest 声明一致
            // Android 15+ AlarmManager 模式下前台服务时间很短，dataSync 类型足够
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NotificationId.AutoTaskService, notification, serviceType)
        } else {
            startForeground(NotificationId.AutoTaskService, notification)
        }
    }
}