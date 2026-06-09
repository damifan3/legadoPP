/**
 * 定时任务列表 ViewModel
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskViewModel.kt
 * 作用：管理定时任务列表的数据状态，提供 CRUD 操作。
 *
 * 主要功能：
 * - 刷新任务列表
 * - 保存/删除任务规则
 * - 批量更新启用状态和 Cron 表达式
 * - 导出任务规则到文件
 */
package io.legado.app.ui.autoTask

import android.app.Application
import com.google.gson.annotations.SerializedName
import io.legado.app.base.BaseViewModel
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.outputStream
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AutoTaskViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 导出用的任务规则数据类
     * 仅包含需要导出的字段
     */
    private data class AutoTaskExport(
        @SerializedName("id")
        val id: String,
        @SerializedName("name")
        val name: String,
        @SerializedName("enable")
        val enable: Boolean,
        @SerializedName("cron")
        val cron: String?,
        @SerializedName("loginUrl")
        val loginUrl: String?,
        @SerializedName("loginUi")
        val loginUi: String?,
        @SerializedName("loginCheckJs")
        val loginCheckJs: String?,
        @SerializedName("comment")
        val comment: String?,
        @SerializedName("script")
        val script: String,
        @SerializedName("header")
        val header: String?,
        @SerializedName("jsLib")
        val jsLib: String?,
        @SerializedName("autoPay")
        val autoPay: Boolean,
        @SerializedName("autoPayMaxCount")
        val autoPayMaxCount: Int
    )

    /**
     * 转换为导出格式
     */
    private fun AutoTaskRule.toExport(): AutoTaskExport {
        return AutoTaskExport(
            id = id,
            name = name,
            enable = enable,
            cron = cron,
            loginUrl = loginUrl,
            loginUi = loginUi,
            loginCheckJs = loginCheckJs,
            comment = comment,
            script = script,
            header = header,
            jsLib = jsLib,
            autoPay = autoPay,
            autoPayMaxCount = autoPayMaxCount
        )
    }

    // 任务规则列表状态流
    private val _rulesFlow = MutableStateFlow<List<AutoTaskRule>>(emptyList())
    val rulesFlow = _rulesFlow.asStateFlow()

    /**
     * 刷新任务列表
     */
    fun refresh() {
        execute {
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 保存任务规则（插入或更新）
     */
    fun save(rule: AutoTaskRule) {
        execute {
            AutoTask.upsert(rule)
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 删除单个任务规则
     */
    fun delete(rule: AutoTaskRule) {
        execute {
            AutoTask.delete(rule.id)
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 批量删除任务规则
     */
    fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        execute {
            AutoTask.delete(*ids.toTypedArray())
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 保存任务排序
     */
    fun saveOrder(items: List<AutoTaskRule>) {
        execute {
            AutoTask.saveRules(items, refresh = false)
        }
    }

    /**
     * 批量更新启用状态
     */
    fun updateEnabled(ids: List<String>, enabled: Boolean) {
        if (ids.isEmpty()) return
        execute {
            val idSet = ids.toHashSet()
            val updated = AutoTask.getRules().map {
                if (idSet.contains(it.id)) it.copy(enable = enabled) else it
            }
            AutoTask.saveRules(updated)
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 批量更新 Cron 表达式
     */
    fun updateCron(ids: List<String>, cron: String) {
        if (ids.isEmpty()) return
        execute {
            val idSet = ids.toHashSet()
            val updated = AutoTask.getRules().map {
                if (idSet.contains(it.id)) it.copy(cron = cron) else it
            }
            AutoTask.saveRules(updated)
            AutoTask.getRules()
        }.onSuccess {
            _rulesFlow.value = it
        }
    }

    /**
     * 导出所有任务到文件
     */
    fun exportToFile(success: (File) -> Unit) {
        execute {
            val path = "${context.filesDir}/exportAutoTask.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use {
                val tasks = AutoTask.getRules().map { rule -> rule.toExport() }
                GSON.writeToOutputStream(it, tasks)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    /**
     * 导出选中的任务到文件
     */
    fun exportSelection(ids: List<String>, success: (File) -> Unit) {
        if (ids.isEmpty()) return
        execute {
            val idSet = ids.toHashSet()
            val tasks = AutoTask.getRules()
                .filter { idSet.contains(it.id) }
                .map { rule -> rule.toExport() }
            val path = "${context.filesDir}/exportAutoTaskSelection.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, tasks)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }
}