/**
 * 定时任务编辑界面
 *
 * 文件位置：legado/app/src/main/java/io/legado/app/ui/autoTask/AutoTaskEditActivity.kt
 * 作用：编辑定时任务规则的各个字段，包括名称、Cron 表达式、脚本等。
 *
 * 主要功能：
 * - 编辑任务名称、Cron 表达式、脚本内容等
 * - 支持登录配置（loginUrl、loginUi、loginCheckJs）
 * - 保存后可跳转调试界面
 * - 支持复制/粘贴任务配置
 */
package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.book.source.edit.BookSourceEditAdapter
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.ui.widget.text.FieldNavController
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AutoTaskEditActivity :
    VMBaseActivity<ActivityAutoTaskEditBinding, AutoTaskEditViewModel>(),
    KeyboardToolPop.CallBack,
    CodeDialog.Callback {

    companion object {
        /**
         * 创建启动 Intent
         * @param context 上下文
         * @param id 任务 ID（可选，用于编辑现有任务）
         */
        fun startIntent(context: Context, id: String? = null): Intent {
            return Intent(context, AutoTaskEditActivity::class.java).apply {
                if (!id.isNullOrBlank()) {
                    putExtra("id", id)
                }
            }
        }
    }

    override val binding by viewBinding(ActivityAutoTaskEditBinding::inflate)
    override val viewModel by viewModels<AutoTaskEditViewModel>()

    // Web 编辑请求映射
    private val webEditRequests = linkedMapOf<String, EditEntity>()

    // 字段编辑适配器
    private val adapter by lazy { BookSourceEditAdapter() }

    // 字段映射
    private val fieldMap = linkedMapOf<String, EditEntity>()
    private val entities: ArrayList<EditEntity> = ArrayList()

    // 当前任务
    private var task: AutoTaskRule? = null

    // 原始任务（用于判断是否有修改）
    private var originTask: AutoTaskRule? = null

    // 软键盘工具
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    // 顶部快捷导航
    private val fieldNavController by lazy {
        FieldNavController(this, binding.fieldNavScroll, binding.fieldNavGroup, binding.recyclerView)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            task = it
            upView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        val loginUrl = getFieldValue("loginUrl")
        menu.findItem(R.id.menu_login)?.let {
            it.isVisible = true
            it.isEnabled = loginUrl.isNotBlank()
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> {
                val rule = buildTask() ?: return true
                viewModel.save(rule) {
                    originTask = rule.copy()
                    setResult(RESULT_OK)
                    finish()
                }
            }
            R.id.menu_debug_task -> {
                val rule = buildTask() ?: return true
                viewModel.save(rule) {
                    originTask = rule.copy()
                    startActivity(AutoTaskDebugActivity.startIntent(this, rule.id))
                }
            }
            R.id.menu_login -> openLogin()
            R.id.menu_copy_source -> sendToClip(GSON.toJson(buildTaskDraft()))
            R.id.menu_paste_source -> viewModel.pasteSource { upView(it) }
            R.id.menu_help -> showHelpDialog()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 初始化视图
     */
    private fun initView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    /**
     * 更新视图
     */
    private fun upView(rule: AutoTaskRule) {
        originTask = rule.copy()
        binding.cbEnable.isChecked = rule.enable
        binding.cbCookie.isChecked = rule.enabledCookieJar

        fieldMap.clear()
        entities.clear()

        // 添加字段
        addField("name", rule.name, R.string.name)
        addField("cron", rule.cron?.ifBlank { AutoTask.DEFAULT_CRON }, R.string.auto_task_cron)
        addField("comment", rule.comment, R.string.auto_task_comment)
        addField("script", rule.script, R.string.auto_task_script)
        addField("header", rule.header, R.string.auto_task_header)
        addField("jsLib", rule.jsLib, R.string.auto_task_jslib)
        addField("concurrentRate", rule.concurrentRate, R.string.auto_task_concurrent_rate)
        addField("loginUrl", rule.loginUrl, R.string.login_url)
        addField("loginUi", rule.loginUi, R.string.login_ui)
        addField("loginCheckJs", rule.loginCheckJs, R.string.login_check_js)

        adapter.editEntities = entities
        fieldNavController.updateFieldNav(entities)
    }

    /**
     * 添加字段
     */
    private fun addField(key: String, value: String?, hintRes: Int) {
        val entity = EditEntity(key, value, hintRes)
        fieldMap[key] = entity
        entities.add(entity)
    }

    /**
     * 获取字段值
     */
    private fun getFieldValue(key: String): String {
        return fieldMap[key]?.value?.trim().orEmpty()
    }

    /**
     * 构建任务对象
     */
    private fun buildTask(): AutoTaskRule? {
        val name = getFieldValue("name")
        if (name.isBlank()) {
            toastOnUi(getString(R.string.auto_task_name_required))
            return null
        }

        val cron = getFieldValue("cron").ifBlank { AutoTask.DEFAULT_CRON }
        if (CronSchedule.parse(cron) == null) {
            toastOnUi(getString(R.string.auto_task_cron_invalid))
            return null
        }

        val script = getFieldValue("script")
        if (script.isBlank()) {
            toastOnUi(getString(R.string.auto_task_script_empty))
            return null
        }

        val rule = task ?: AutoTaskRule()
        rule.name = name
        rule.cron = cron
        rule.comment = getFieldValue("comment").ifBlank { null }
        rule.script = script
        rule.header = getFieldValue("header").ifBlank { null }
        rule.jsLib = getFieldValue("jsLib").ifBlank { null }
        rule.concurrentRate = getFieldValue("concurrentRate").ifBlank { null }
        rule.loginUrl = getFieldValue("loginUrl").ifBlank { null }
        rule.loginUi = getFieldValue("loginUi").ifBlank { null }
        rule.loginCheckJs = getFieldValue("loginCheckJs").ifBlank { null }
        rule.enable = binding.cbEnable.isChecked
        rule.enabledCookieJar = binding.cbCookie.isChecked

        task = rule
        return rule
    }

    /**
     * 构建任务草稿（用于复制）
     */
    private fun buildTaskDraft(): AutoTaskRule {
        val base = originTask ?: task ?: AutoTaskRule()
        return base.copy(
            name = getFieldValue("name"),
            cron = getFieldValue("cron").ifBlank { AutoTask.DEFAULT_CRON },
            comment = getFieldValue("comment").ifBlank { null },
            script = getFieldValue("script"),
            header = getFieldValue("header").ifBlank { null },
            jsLib = getFieldValue("jsLib").ifBlank { null },
            concurrentRate = getFieldValue("concurrentRate").ifBlank { null },
            loginUrl = getFieldValue("loginUrl").ifBlank { null },
            loginUi = getFieldValue("loginUi").ifBlank { null },
            loginCheckJs = getFieldValue("loginCheckJs").ifBlank { null },
            enable = binding.cbEnable.isChecked,
            enabledCookieJar = binding.cbCookie.isChecked
        )
    }

    /**
     * 打开登录界面
     */
    private fun openLogin() {
        val rule = buildTask() ?: return
        val loginUrl = rule.loginUrl.orEmpty()
        if (loginUrl.isBlank()) {
            toastOnUi(getString(R.string.source_no_login))
            return
        }
        viewModel.save(rule) {
            startActivity<SourceLoginActivity> {
                putExtra("type", "autoTask")
                putExtra("key", rule.id)
            }
        }
    }

    /**
     * 显示帮助对话框
     */
    private fun showHelpDialog() {
        showHelp("autoTaskHelp")
    }

    // ==================== KeyboardToolPop.CallBack ====================

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf()
    }

    override fun onHelpActionSelect(action: String) {
    }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else if (start > end) {
                edit.replace(end, start, text)
            } else {
                edit.replace(start, end, text)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    // ==================== CodeDialog.Callback ====================

    override fun onCodeSave(code: String, requestId: String?) {
        val entity = requestId?.let { webEditRequests.remove(it) } ?: return
        entity.value = code
        val index = entities.indexOf(entity)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
        }
    }

    // ==================== KeyboardToolPop.CallBack - 撤销/重做 ====================

    override fun onUndoClicked() {
        // 定时任务编辑暂不支持撤销，空实现
    }

    override fun onRedoClicked() {
        // 定时任务编辑暂不支持重做，空实现
    }
}