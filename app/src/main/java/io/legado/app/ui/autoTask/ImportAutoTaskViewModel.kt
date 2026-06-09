/**
 * 定时任务导入 ViewModel
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/ImportAutoTaskViewModel.kt
 * 作用：处理定时任务的导入逻辑，支持 URL、文件、JSON 文本导入。
 */
package io.legado.app.ui.autoTask

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import splitties.init.appCtx

class ImportAutoTaskViewModel(app: Application) : BaseViewModel(app) {

    /** 错误信息 LiveData */
    val errorLiveData = MutableLiveData<String>()

    /** 成功导入数量 LiveData */
    val successLiveData = MutableLiveData<Int>()

    /** 所有待导入任务列表 */
    val allTasks = arrayListOf<AutoTaskRule>()

    /** 本地已存在的任务（用于对比显示） */
    val checkTasks = arrayListOf<AutoTaskRule?>()

    /** 每个任务的选择状态 */
    val selectStatus = arrayListOf<Boolean>()

    /** 是否全选 */
    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    /** 已选数量 */
    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    /**
     * 导入选中的任务
     * @param finally 完成回调
     */
    fun importSelect(finally: () -> Unit) {
        execute {
            // 筛选选中的任务
            val selectTasks = arrayListOf<AutoTaskRule>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    selectTasks.add(allTasks[index])
                }
            }
            // 获取本地任务列表
            val localTasks = AutoTask.getRules().toMutableList()
            // 建立索引映射，用于判断是新增还是更新
            val indexMap = localTasks.mapIndexed { index, task ->
                task.id to index
            }.toMap(LinkedHashMap())
            // 处理每个选中任务
            selectTasks.forEach { task ->
                val index = indexMap[task.id]
                if (index == null) {
                    // 新增任务
                    localTasks.add(task)
                    indexMap[task.id] = localTasks.lastIndex
                } else {
                    // 更新已有任务
                    localTasks[index] = task
                }
            }
            // 保存到数据库
            AutoTask.saveRules(localTasks)
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入任务源
     * @param text URL、文件路径或 JSON 文本
     */
    fun importSource(text: String?) {
        val sourceText = text?.trim().orEmpty()
        if (sourceText.isBlank()) {
            errorLiveData.postValue("ImportError:${context.getString(R.string.wrong_format)}")
            return
        }
        allTasks.clear()
        checkTasks.clear()
        selectStatus.clear()
        execute {
            importSourceAwait(sourceText)
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    /**
     * 更新指定位置的任务
     * @param index 任务索引
     * @param task 新任务数据
     */
    fun updateTaskAt(index: Int, task: AutoTaskRule) {
        if (index < 0 || index >= allTasks.size) return
        allTasks[index] = task
        val localTask = AutoTask.getRules().firstOrNull { it.id == task.id }
        if (index < checkTasks.size) {
            checkTasks[index] = localTask
        }
        if (index < selectStatus.size) {
            selectStatus[index] = localTask == null || task != localTask
        }
    }

    /**
     * 异步导入任务源
     * @param text URL、文件路径或 JSON 文本
     */
    private suspend fun importSourceAwait(text: String) {
        when {
            // JSON 对象格式
            text.isJsonObject() -> {
                GSON.fromJsonObject<AutoTaskRule>(text).getOrThrow().let {
                    allTasks.add(it)
                }
            }

            // JSON 数组格式
            text.isJsonArray() -> GSON.fromJsonArray<AutoTaskRule>(text).getOrThrow()
                .let { items ->
                    allTasks.addAll(items)
                }

            // URL 格式
            text.isAbsUrl() -> {
                importSourceUrl(text)
            }

            // 文件 URI 格式
            text.isUri() -> {
                importSourceAwait(text.toUri().readText(appCtx))
            }

            // 其他格式报错
            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    /**
     * 从 URL 导入任务源
     * @param url 远程 URL
     */
    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                // 不带 UA 请求
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text().let {
            importSourceAwait(it)
        }
    }

    /**
     * 对比本地任务，设置选择状态
     */
    private fun comparisonSource() {
        execute {
            val localMap = AutoTask.getRules().associateBy { it.id }
            allTasks.forEach {
                val local = localMap[it.id]
                checkTasks.add(local)
                // 新任务或与本地不同的任务默认选中
                selectStatus.add(local == null || it != local)
            }
            successLiveData.postValue(allTasks.size)
        }
    }
}