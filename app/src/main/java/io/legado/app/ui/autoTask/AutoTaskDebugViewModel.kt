/**
 * 定时任务调试 ViewModel
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskDebugViewModel.kt
 * 作用：管理定时任务调试执行，输出日志。
 *
 * 主要功能：
 * - 加载任务规则
 * - 执行 JS 脚本
 * - 处理执行结果并输出日志
 */
package io.legado.app.ui.autoTask

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.model.AutoTaskProtocol
import io.legado.app.model.Debug
import io.legado.app.utils.stackTraceStr

class AutoTaskDebugViewModel(application: Application) : BaseViewModel(application),
    Debug.Callback {

    // 当前调试的任务
    var task: AutoTaskRule? = null

    // 日志回调
    private var callback: ((Int, String) -> Unit)? = null

    /**
     * 初始化任务数据
     * @param id 任务 ID
     * @param finally 初始化完成回调
     */
    fun init(id: String?, finally: () -> Unit) {
        execute {
            task = AutoTask.getRules().firstOrNull { it.id == id }
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 观察调试日志
     */
    fun observe(callback: (Int, String) -> Unit) {
        this.callback = callback
    }

    /**
     * 开始调试执行
     * @param start 开始回调
     * @param error 错误回调（任务不存在时）
     */
    fun startDebug(start: (() -> Unit)? = null, error: (() -> Unit)? = null) {
        if (task == null) {
            error?.invoke()
            return
        }

        execute {
            val rule = task ?: return@execute
            val source = AutoTask.buildSource(rule)

            // 注册调试回调
            Debug.callback = this@AutoTaskDebugViewModel
            Debug.startSimpleDebug(source.getKey())
            Debug.log(source.getKey(), "︾开始执行")

            // 规范化脚本
            val script = normalizeScript(rule.script)
            if (script.isBlank()) {
                Debug.log(source.getKey(), "脚本为空", state = -1)
                return@execute
            }

            // 执行脚本
            runCatching {
                source.evalJS(script)
            }.onSuccess { result ->
                // 使用 AutoTaskProtocol 处理结果
                AutoTaskProtocol.handle(result, context, rule.name) { msg ->
                    Debug.log(source.getKey(), msg, showTime = false)
                }

                val detail = result?.toString()?.take(200)
                if (!detail.isNullOrBlank()) {
                    Debug.log(source.getKey(), detail, showTime = false)
                }
                Debug.log(source.getKey(), "︽执行完成", state = 1000)
            }.onFailure { err ->
                Debug.log(source.getKey(), err.stackTraceStr, state = -1)
            }
        }.onStart {
            start?.invoke()
        }.onError {
            error?.invoke()
        }
    }

    // Debug.Callback
    override fun printLog(state: Int, msg: String) {
        callback?.invoke(state, msg)
    }

    override fun onCleared() {
        super.onCleared()
        Debug.cancelDebug(true)
    }

    /**
     * 规范化脚本
     */
    private fun normalizeScript(script: String): String {
        return AutoTask.normalizeScript(script)
    }
}