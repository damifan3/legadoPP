/**
 * 定时任务编辑 ViewModel
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskEditViewModel.kt
 * 作用：管理定时任务编辑界面的数据状态，提供初始化、保存、粘贴等功能。
 *
 * 主要功能：
 * - 初始化任务数据（新建或编辑现有任务）
 * - 保存任务规则
 * - 从剪贴板粘贴任务配置
 */
package io.legado.app.ui.autoTask

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi

class AutoTaskEditViewModel(app: Application) : BaseViewModel(app) {

    // 当前编辑的任务
    var task: AutoTaskRule? = null

    /**
     * 初始化数据
     * @param intent 启动 Intent，可能包含任务 ID
     * @param finally 初始化完成回调
     */
    fun initData(intent: Intent, finally: (AutoTaskRule) -> Unit) {
        execute {
            val id = intent.getStringExtra("id")
            // 根据 ID 查找现有任务，或创建新任务
            task = AutoTask.getRules().firstOrNull { it.id == id } ?: AutoTaskRule()
        }.onFinally {
            task?.let { finally(it) }
        }
    }

    /**
     * 保存任务规则
     * @param rule 要保存的任务规则
     * @param success 保存成功回调
     */
    fun save(rule: AutoTaskRule, success: () -> Unit) {
        execute {
            AutoTask.upsert(rule)
        }.onSuccess {
            success()
        }.onError {
            context.toastOnUi("save error, ${it.localizedMessage}")
        }
    }

    /**
     * 从剪贴板粘贴任务配置
     * @param onSuccess 粘贴成功回调
     */
    fun pasteSource(onSuccess: (AutoTaskRule) -> Unit) {
        execute {
            val text = context.getClipText()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            }
            when {
                text.isJsonObject() -> {
                    // 解析单个任务 JSON
                    GSON.fromJsonObject<AutoTaskRule>(text).getOrThrow()
                }
                text.isJsonArray() -> {
                    throw NoStackTraceException("剪贴板包含多个任务，请使用导入功能")
                }
                else -> throw NoStackTraceException("剪贴板内容格式不正确")
            }
        }.onSuccess {
            onSuccess(it)
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "Error")
            it.printOnDebug()
        }
    }
}