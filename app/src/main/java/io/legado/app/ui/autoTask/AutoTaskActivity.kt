/**
 * 定时任务列表界面
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskActivity.kt
 * 作用：显示定时任务列表，支持搜索、批量操作、导入导出等功能。
 *
 * 主要功能：
 * - 显示所有定时任务规则列表
 * - 搜索过滤任务
 * - 批量启用/禁用/删除任务
 * - 导入/导出任务规则
 * - 跳转到任务编辑界面
 */
package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.applyTint
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.ACache
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutoTaskActivity : VMBaseActivity<ActivityAutoTaskBinding, AutoTaskViewModel>(),
    AutoTaskAdapter.CallBack,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SearchView.OnQueryTextListener {

    override val viewModel: AutoTaskViewModel by viewModels()
    override val binding: ActivityAutoTaskBinding by viewBinding(ActivityAutoTaskBinding::inflate)

    // 任务列表适配器
    private val adapter: AutoTaskAdapter by lazy { AutoTaskAdapter(this, this) }

    // 搜索视图
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    // 拖拽回调
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }

    // 导入记录缓存键
    private val importRecordKey = "autoTaskRecordKey"

    // 所有任务规则缓存
    private var allRules: List<AutoTaskRule> = emptyList()

    // 导入文件契约
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportAutoTaskDialog(uri.toString()))
        }
    }

    // 导出文件契约
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initSearchView()
        initSelectActionBar()
        observeData()
        bindImportResult()
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    /**
     * 创建选项菜单
     */
    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task, menu)
        return true
    }

    /**
     * 处理选项菜单点击
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity(AutoTaskEditActivity.startIntent(this))
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 初始化视图
     */
    private fun initView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(VerticalDivider(this@AutoTaskActivity))

        // 配置拖拽选择
        val dragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(recyclerView)
        dragSelectTouchHelper.activeSlideSelect()

        // 配置拖拽排序
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(recyclerView)
    }

    /**
     * 初始化搜索视图
     */
    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(this)
    }

    /**
     * 初始化选择操作栏
     */
    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.auto_task_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        upCountView()
    }

    /**
     * 观察数据变化
     */
    private fun observeData() {
        lifecycleScope.launch {
            viewModel.rulesFlow.collectLatest {
                allRules = it
                applyFilter()
            }
        }
    }

    /**
     * 应用搜索过滤
     */
    private fun applyFilter() {
        val query = searchView.query?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            allRules
        } else {
            allRules.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.setItems(filtered, adapter.diffItemCallBack)
        invalidateOptionsMenu()
        upCountView()
    }

    /**
     * 绑定导入结果监听
     */
    private fun bindImportResult() {
        supportFragmentManager.setFragmentResultListener(
            ImportAutoTaskDialog.RESULT_KEY,
            this
        ) { _, _ ->
            viewModel.refresh()
        }
    }

    /**
     * 显示在线导入对话框
     */
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()

        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportAutoTaskDialog(it))
                }
            }
            cancelButton()
        }
    }

    // ==================== SearchView.OnQueryTextListener ====================

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        applyFilter()
        return false
    }

    /**
     * 显示批量设置 Cron 对话框
     */
    private fun showBatchCronDialog() {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.auto_task_cron)
        }
        alert(titleResource = R.string.auto_task_batch_cron) {
            customView { alertBinding.root }
            okButton {
                val cron = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (cron.isNotBlank() && CronSchedule.parse(cron) != null) {
                    viewModel.updateCron(adapter.selection.map { it.id }, cron)
                } else {
                    toastOnUi(R.string.auto_task_cron_invalid)
                }
            }
            cancelButton()
        }
    }

    // ==================== AutoTaskAdapter.CallBack ====================

    override fun edit(task: AutoTaskRule) {
        startActivity(AutoTaskEditActivity.startIntent(this, task.id))
    }

    override fun delete(task: AutoTaskRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.auto_task_delete) + "\n" + task.name)
            noButton()
            yesButton { viewModel.delete(task) }
        }
    }

    override fun toggle(task: AutoTaskRule, enabled: Boolean) {
        viewModel.save(task.copy(enable = enabled))
    }

    override fun showLog(task: AutoTaskRule) {
        showDialogFragment(AutoTaskLogDialog(task.id, task.name))
    }

    override fun upOrder(items: List<AutoTaskRule>) {
        viewModel.saveOrder(items)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    // ==================== SelectActionBar.CallBack ====================

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        if (adapter.selection.isEmpty()) return
        alert(R.string.draw, R.string.sure_del) {
            yesButton { viewModel.delete(adapter.selection.map { it.id }) }
            noButton()
        }
    }

    // ==================== PopupMenu.OnMenuItemClickListener ====================

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, true)
            R.id.menu_disable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, false)
            R.id.menu_export_selection -> viewModel.exportSelection(adapter.selection.map { it.id }) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "autoTaskSelection.json",
                        file,
                        "application/json"
                    )
                }
            }
            R.id.menu_batch_cron -> showBatchCronDialog()
        }
        return true
    }
}