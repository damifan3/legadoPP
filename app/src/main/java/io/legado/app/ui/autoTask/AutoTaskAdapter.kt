/**
 * 定时任务列表适配器
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskAdapter.kt
 * 作用：为定时任务列表提供 RecyclerView 适配器，支持多选、拖拽排序。
 *
 * 主要功能：
 * - 显示任务名称、启用状态、Cron 表达式、上次执行结果
 * - 支持多选和全选
 * - 支持拖拽排序
 * - 提供编辑、删除、查看日志等操作菜单
 */
package io.legado.app.ui.autoTask

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemAutoTaskBinding
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.startActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<AutoTaskRule, ItemAutoTaskBinding>(context),
    ItemTouchCallback.Callback {

    // 时间格式化器
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 选中的任务 ID 集合
    private val selectedIds = linkedSetOf<String>()

    // 当前选中的任务列表
    val selection: List<AutoTaskRule>
        get() {
            return getItems().filter { selectedIds.contains(it.id) }
        }

    // 差异比较回调，用于高效更新列表
    val diffItemCallBack = object : DiffUtil.ItemCallback<AutoTaskRule>() {
        override fun areItemsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: AutoTaskRule, newItem: AutoTaskRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name) {
                payload.putBoolean("name", true)
            }
            if (oldItem.enable != newItem.enable) {
                payload.putBoolean("enabled", true)
            }
            if (oldItem.lastRunAt != newItem.lastRunAt ||
                oldItem.lastError != newItem.lastError ||
                oldItem.cron != newItem.cron
            ) {
                payload.putBoolean("summary", true)
            }
            return if (payload.isEmpty) null else payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemAutoTaskBinding {
        return ItemAutoTaskBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAutoTaskBinding,
        item: AutoTaskRule,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            // 完整绑定
            binding.cbTask.text = item.name.ifBlank { item.id }
            binding.swtEnabled.isChecked = item.enable
            binding.titleDesc.text = buildSummary(item)
            binding.cbTask.isChecked = selectedIds.contains(item.id)
        } else {
            // 增量更新
            for (i in payloads.indices) {
                val bundle = payloads[i] as? Bundle ?: continue
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> binding.cbTask.text = item.name.ifBlank { item.id }
                        "enabled" -> binding.swtEnabled.isChecked = item.enable
                        "summary" -> binding.titleDesc.text = buildSummary(item)
                    }
                }
            }
            binding.cbTask.isChecked = selectedIds.contains(item.id)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAutoTaskBinding) {
        // 复选框点击监听
        binding.cbTask.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                getItem(holder.layoutPosition)?.let { task ->
                    if (isChecked) {
                        selectedIds.add(task.id)
                    } else {
                        selectedIds.remove(task.id)
                    }
                    callBack.upCountView()
                }
            }
        }

        // 启用开关点击监听
        binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            val item = getItem(holder.layoutPosition) ?: return@setOnCheckedChangeListener
            if (buttonView.isPressed) {
                callBack.toggle(item, isChecked)
            }
        }

        // 编辑按钮点击监听
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }

        // 更多菜单按钮点击监听
        binding.ivMenuMore.setOnClickListener { view ->
            getItem(holder.layoutPosition)?.let { showMenu(view, it) }
        }

        // 根布局点击监听（进入编辑）
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }
    }

    override fun onCurrentListChanged() {
        // 清理已删除任务的选中状态
        val currentIds = getItems().map { it.id }.toHashSet()
        val iterator = selectedIds.iterator()
        while (iterator.hasNext()) {
            if (!currentIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
        callBack.upCountView()
    }

    /**
     * 全选
     */
    fun selectAll() {
        getItems().forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    /**
     * 反选
     */
    fun revertSelection() {
        val allIds = getItems().map { it.id }.toHashSet()
        val newSelection = allIds - selectedIds
        selectedIds.clear()
        selectedIds.addAll(newSelection)
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    // ==================== ItemTouchCallback.Callback ====================
    // 问题：swap 返回类型应为 Boolean（接口定义），onMove 不在接口中，onClearView 需要参数
    // 解决：修正 swap 返回类型，删除多余的 onMove，修正 onClearView 签名

    override fun swap(fromPosition: Int, toPosition: Int): Boolean {
        // 拖拽交换由 ItemTouchCallback.onMove 内部通过循环调用 swap 处理
        val items = getItems().toMutableList()
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        setItems(items)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        callBack.upOrder(getItems())
    }

    // 拖拽选择回调
    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(DragSelectTouchHelper.AdvanceCallback.Mode.ToggleAndReverse) {
            override fun currentSelectedId(): Set<String> {
                return selectedIds
            }

            override fun getItemId(position: Int): String {
                return getItem(position)!!.id
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let { task ->
                    if (isSelected) {
                        selectedIds.add(task.id)
                    } else {
                        selectedIds.remove(task.id)
                    }
                    notifyItemChanged(position, bundleOf(Pair("selected", null)))
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    /**
     * 构建任务摘要文本
     */
    private fun buildSummary(task: AutoTaskRule): String {
        val cron = task.cron?.trim().orEmpty().ifBlank { "-" }
        val status = when {
            !task.lastError.isNullOrBlank() ->
                context.getString(R.string.auto_task_last_error, task.lastError)
            task.lastRunAt > 0L ->
                context.getString(
                    R.string.auto_task_last_run,
                    timeFormat.format(Date(task.lastRunAt))
                )
            else -> context.getString(R.string.auto_task_not_run)
        }
        return context.getString(R.string.auto_task_item_summary, cron, status)
    }

    /**
     * 显示任务操作菜单
     */
    private fun showMenu(view: View, task: AutoTaskRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.auto_task_item)
        popupMenu.menu.findItem(R.id.menu_login)?.isVisible = !task.loginUrl.isNullOrBlank()
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_login -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("sourceUrl", AutoTask.SOURCE_KEY + ":" + task.id)
                }
                R.id.menu_log -> callBack.showLog(task)
                R.id.menu_delete -> callBack.delete(task)
            }
            true
        }
        popupMenu.show()
    }

    /**
     * 回调接口
     */
    interface CallBack {
        fun edit(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun showLog(task: AutoTaskRule)
        fun upOrder(items: List<AutoTaskRule>)
        fun upCountView()
    }
}