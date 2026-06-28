package io.legado.app.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * 定时任务规则实体类
 *
 * 用于存储定时任务的配置信息，包括执行时间、脚本内容、执行状态等。
 * 存储在 Room 数据库的 auto_task_rules 表中。
 *
 * 主要字段说明：
 * - id: 唯一标识符，使用 UUID
 * - name: 任务名称，显示在列表中
 * - enable: 是否启用该任务
 * - cron: Cron 表达式，定义执行时间（如 "每30分钟" 表示定时执行）
 * - script: 要执行的 JavaScript 脚本内容
 *
 * @property id 主键，UUID 格式
 * @property name 任务名称
 * @property enable 是否启用
 * @property cron Cron 表达式（分 时 日 月 周）
 * @property loginUrl 登录地址，用于需要认证的任务
 * @property loginUi 登录界面 JSON 配置
 * @property loginCheckJs 登录验证 JS 脚本
 * @property comment 脚本注释/说明
 * @property script 要执行的 JavaScript 脚本
 * @property header 请求头 JSON 配置
 * @property jsLib JS 库代码，会在脚本执行前加载
 * @property concurrentRate 并发限制，格式如 "10/1000" 表示1秒内最多10次请求
 * @property enabledCookieJar 是否启用 Cookie 管理
 * @property lastRunAt 上次执行时间戳（毫秒）
 * @property lastResult 上次执行结果摘要
 * @property lastError 上次执行错误信息
 * @property lastLog 上次执行日志
 */
@Entity(tableName = "auto_task_rules")
data class AutoTaskRule(
    @PrimaryKey
    @SerializedName("id")
    var id: String = UUID.randomUUID().toString(),

    @SerializedName("name")
    var name: String = "",

    @SerializedName("enable")
    var enable: Boolean = true,

    // Cron 表达式，默认每30分钟执行一次
    @SerializedName("cron")
    var cron: String? = "*/30 * * * *",

    // 登录相关配置（用于需要认证的网站）
    @SerializedName("loginUrl")
    var loginUrl: String? = null,

    @SerializedName("loginUi")
    var loginUi: String? = null,

    @SerializedName("loginCheckJs")
    var loginCheckJs: String? = null,

    // 脚本说明/注释
    @SerializedName("comment")
    var comment: String? = null,

    // 要执行的 JavaScript 脚本
    @SerializedName("script")
    var script: String = "",

    // HTTP 请求头配置（JSON 格式）
    @SerializedName("header")
    var header: String? = null,

    // JS 库代码，会在脚本执行前预加载
    @SerializedName("jsLib")
    var jsLib: String? = null,

    // 并发限制，格式 "次数/毫秒"，如 "10/1000" 表示1秒内最多10次
    @SerializedName("concurrentRate")
    var concurrentRate: String? = null,

    // 是否启用 Cookie 管理
    @SerializedName("enabledCookieJar")
    var enabledCookieJar: Boolean = true,

    // 执行状态记录
    @SerializedName("lastRunAt")
    var lastRunAt: Long = 0L,

    @SerializedName("lastResult")
    var lastResult: String? = null,

    @SerializedName("lastError")
    var lastError: String? = null,

    @SerializedName("lastLog")
    var lastLog: String? = null
)