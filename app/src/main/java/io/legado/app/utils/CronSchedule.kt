package io.legado.app.utils

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cron 表达式解析器
 *
 * 用于解析标准的 5 字段 Cron 表达式（分 时 日 月 周），
 * 并计算给定时间之后的下次执行时间。
 *
 * 支持的 Cron 格式：
 * - 字段顺序：分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-7, 0和7都表示周日)
 * - 支持通配符：星号 表示任意值，问号 表示任意值（主要用于日和周字段）
 * - 支持范围：`1-5` 表示 1 到 5
 * - 支持步长：星号/5 表示每隔 5 个单位，`1-10/2` 表示 1 到 10 每隔 2 个单位
 * - 支持列表：`1,3,5` 表示 1、3、5
 *
 * 示例：
 * - 每 30 分钟执行
 * - 每 2 小时执行
 * - 周一到周五每天 9 点执行
 * - 每月 1 号零点执行
 *
 * @property minutes 分钟字段，BooleanArray[0-59]，true 表示该分钟匹配
 * @property hours 小时字段，BooleanArray[0-23]，true 表示该小时匹配
 * @property daysOfMonth 日期字段，BooleanArray[1-31]，true 表示该日期匹配
 * @property months 月份字段，BooleanArray[1-12]，true 表示该月份匹配
 * @property daysOfWeek 周字段，BooleanArray[0-7]，true 表示该周几匹配
 * @property domAny 日字段是否为通配符
 * @property dowAny 周字段是否为通配符
 */
class CronSchedule private constructor(
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val daysOfMonth: BooleanArray,
    private val months: BooleanArray,
    private val daysOfWeek: BooleanArray,
    private val domAny: Boolean,
    private val dowAny: Boolean
) {

    /**
     * 计算给定时间之后的下次执行时间
     *
     * @param fromEpochMs 起始时间戳（毫秒）
     * @param zoneId 时区，默认使用系统时区
     * @return 下次执行时间戳（毫秒），如果一年内没有匹配时间则返回 null
     */
    fun nextTimeAfter(fromEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        // 从起始时间的下一分钟开始检查
        var time = Instant.ofEpochMilli(fromEpochMs)
            .atZone(zoneId)
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)

        // 最多检查一年的分钟数，避免无限循环
        repeat(MAX_MINUTES) {
            if (matches(time)) {
                return time.toInstant().toEpochMilli()
            }
            time = time.plusMinutes(1)
        }
        return null
    }

    /**
     * 检查给定时间是否匹配 Cron 表达式
     *
     * @param time 待检查的时间
     * @return 是否匹配
     */
    private fun matches(time: ZonedDateTime): Boolean {
        // 检查月份
        if (!months[time.monthValue]) return false
        // 检查小时
        if (!hours[time.hour]) return false
        // 检查分钟
        if (!minutes[time.minute]) return false

        // 检查日期和周几的组合匹配
        // Cron 的日期和周几是"或"关系：当两个字段都不是通配符时，满足任一即可
        val domMatch = daysOfMonth[time.dayOfMonth]
        val dow = time.dayOfWeek.value % 7  // 将周一(1)到周日(7)转换为 1-0 格式
        val dowMatch = daysOfWeek[dow]

        val dayMatch = when {
            domAny && dowAny -> true           // 两个都是通配符，任意日期都匹配
            domAny -> dowMatch                  // 只有日是通配符，按周几匹配
            dowAny -> domMatch                  // 只有周是通配符，按日期匹配
            else -> domMatch || dowMatch        // 都不是通配符，满足任一即可
        }
        return dayMatch
    }

    companion object {
        // 最大检查分钟数：一年的分钟数，用于防止无限循环
        private const val MAX_MINUTES = 366 * 24 * 60

        /**
         * 解析 Cron 表达式
         *
         * @param expression 5 字段 Cron 表达式字符串
         * @return 解析成功的 CronSchedule 对象，解析失败返回 null
         */
        fun parse(expression: String): CronSchedule? {
            // 按空白字符分割，过滤空字符串
            val parts = expression.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            // 必须正好 5 个字段
            if (parts.size != 5) return null

            // 依次解析各字段
            val minute = parseField(parts[0], 0, 59) ?: return null
            val hour = parseField(parts[1], 0, 23) ?: return null
            val dom = parseField(parts[2], 1, 31) ?: return null
            val month = parseField(parts[3], 1, 12) ?: return null
            // 周字段特殊处理：7 表示周日，需要映射为 0
            val dow = parseField(parts[4], 0, 7, mapSundayToZero = true) ?: return null

            return CronSchedule(
                minute.allowed,
                hour.allowed,
                dom.allowed,
                month.allowed,
                dow.allowed,
                dom.any,
                dow.any
            )
        }

        /**
         * 字段解析结果
         * @property allowed 布尔数组，索引对应字段值，true 表示该值匹配
         * @property any 是否为通配符（`*` 或 `?`）
         */
        private data class Field(val allowed: BooleanArray, val any: Boolean)

        /**
         * 解析单个 Cron 字段
         *
         * @param field 字段字符串
         * @param min 最小值
         * @param max 最大值
         * @param mapSundayToZero 是否将周日(7)映射为 0，仅用于周字段
         * @return 解析成功的 Field 对象，解析失败返回 null
         */
        private fun parseField(
            field: String,
            min: Int,
            max: Int,
            mapSundayToZero: Boolean = false
        ): Field? {
            val text = field.trim()
            if (text.isEmpty()) return null

            // 处理通配符 `*` 或 `?`
            if (text == "*" || text == "?") {
                val allowed = BooleanArray(max + 1) { idx -> idx in min..max }
                return Field(allowed, true)
            }

            // 初始化匹配数组
            val allowed = BooleanArray(max + 1)

            // 按逗号分割处理列表（如 "1,3,5"）
            val parts = text.split(",")
            for (part in parts) {
                if (part.isBlank()) return null

                // 处理步长（如 "*/5" 或 "1-10/2"）
                val stepSplit = part.split("/", limit = 2)
                val base = stepSplit[0]
                val step = if (stepSplit.size == 2) stepSplit[1].toIntOrNull() else 1
                if (step == null || step <= 0) return null

                // 确定范围
                val range = when {
                    base == "*" || base == "?" -> min..max
                    base.contains("-") -> {
                        // 处理范围（如 "1-5"）
                        val rangeSplit = base.split("-", limit = 2)
                        val start = rangeSplit[0].toIntOrNull() ?: return null
                        val end = rangeSplit[1].toIntOrNull() ?: return null
                        if (start > end) return null
                        start..end
                    }
                    else -> {
                        // 单个值
                        val value = base.toIntOrNull() ?: return null
                        value..value
                    }
                }

                // 按步长遍历范围，标记匹配值
                for (value in range step step) {
                    var v = value
                    // 周字段特殊处理：将 7（周日）映射为 0
                    if (mapSundayToZero && v == 7) v = 0
                    if (v < min || v > max) return null
                    allowed[v] = true
                }
            }

            // 检查是否有有效匹配
            if (allowed.none { it }) return null
            return Field(allowed, false)
        }
    }
}