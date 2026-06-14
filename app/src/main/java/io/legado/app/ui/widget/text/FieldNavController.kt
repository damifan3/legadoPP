package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.utils.getCompatColor

/**
 * 字段导航控制器 (Field Navigation Controller)
 *
 * 用于在复杂的表单编辑界面（如“编辑书源”、“编辑自动任务”等）顶部提供一个快捷跳转的横向导航栏。
 * 实现两大核心联动功能：
 * 1. 点击导航项：控制下方的 [RecyclerView] 平滑滚动并定位到对应字段。
 * 2. 列表滚动：监听下方 [RecyclerView] 的滚动，自动将顶部的导航栏焦点更新并高亮为当前屏幕可见的第一个字段。
 *
 * @param context Android 上下文
 * @param scrollView 导航栏最外层的横向滚动容器，用于在选项过多时左右滑动
 * @param container 导航栏内部真正用来承载所有 [TextView] 导航按钮的容器
 * @param recyclerView 承载所有表单编辑字段的列表控件
 */
class FieldNavController(
    private val context: Context,
    private val scrollView: HorizontalScrollView,
    private val container: LinearLayout,
    private val recyclerView: RecyclerView
) {

    /** 记录当前处于高亮（选中）状态的导航项索引 */
    private var selectedNavIndex = 0

    /** 
     * 标记当前是否是因为点击了导航项而触发的程序滚动。
     * 作用：防止“点击导航项 -> 列表滚动 -> 触发滚动监听 -> 再次尝试高亮导航项”的循环调用。
     */
    private var navClickScrolling = false

    init {
        // 注册 RecyclerView 的滚动监听，用于实现“滑动列表 -> 自动高亮顶部对应导航项”
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    navClickScrolling = false
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                //如果检测到标志位 ture，证明是用户点击触发的，啥也不干防止循环滚动
                if (navClickScrolling) return

                //如果用户真的在滚动滑动，才执行下方代码
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible >= 0 && firstVisible != selectedNavIndex) {
                    highlightNavItem(firstVisible)
                }
            }
        })
    }

    /**
     * 根据当前页面的数据源，动态生成顶部的导航按钮。
     * 在页面初始化，或者数据源发生变动（例如通过 Tab 切换不同的字段分组）时调用。
     *
     * @param entities 当前需要展示和编辑的数据实体列表 [EditEntity]
     */
    fun updateFieldNav(entities: List<EditEntity>) {
        container.removeAllViews()

        entities.forEachIndexed { index, entity ->
            // 从 hint 中过滤掉括号及其内容作为简短的导航标签（例如把 "书源名称(选填)" 变成 "书源名称"）
            val label = entity.hint.replace(Regex("[（(].+?[）)]"), "").trim()
            val tv = TextView(context).apply {
                text = label
                textSize = 12f
                setPadding(24, 8, 24, 8)
                setBackgroundResource(R.drawable.bg_field_nav_item)
                setTextColor(context.getCompatColor(R.color.primaryText))
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 6
                layoutParams = lp
                
                // 导航按钮的点击事件：触发下方的 RecyclerView 平滑滚动
                setOnClickListener {
                    navClickScrolling = true
                    val lm = recyclerView.layoutManager as? LinearLayoutManager
                    if (lm != null) {
                        // 使用自定义的 LinearSmoothScroller，使目标 item 滚动到屏幕顶部 (SNAP_TO_START)
                        val scroller = object : LinearSmoothScroller(context) {
                            override fun getVerticalSnapPreference() = SNAP_TO_START
                            override fun calculateDtToFit(
                                viewStart: Int, viewEnd: Int,
                                boxStart: Int, boxEnd: Int,
                                snapPreference: Int
                            ): Int {
                                val offset = (context.resources.displayMetrics.density * 4).toInt()
                                return boxStart - viewStart + offset
                            }
                        }
                        scroller.targetPosition = index
                        lm.startSmoothScroll(scroller)
                    }
                    highlightNavItem(index)
                }
            }
            container.addView(tv)
        }
        // 默认初始化时高亮第一个元素
        highlightNavItem(0)
    }

    /**
     * 高亮指定的导航按钮，并让其对应的横向 ScrollView 自动滚动到合适的位置。
     *
     * @param index 要高亮的导航按钮在容器中的索引
     */
    private fun highlightNavItem(index: Int) {
        // 1. 取消旧选中项的高亮状态
        if (selectedNavIndex in 0 until container.childCount) {
            val prev = container.getChildAt(selectedNavIndex) as? TextView
            prev?.isSelected = false
            prev?.setTextColor(context.getCompatColor(R.color.primaryText))
        }
        
        // 2. 设置新选中项的高亮状态
        if (index in 0 until container.childCount) {
            val curr = container.getChildAt(index) as? TextView
            curr?.isSelected = true
            curr?.setTextColor(Color.WHITE)
            
            // 3. 将新选中的按钮平滑滚动到用户可见范围内（左侧留出一点 16px 的边距）
            curr?.let {
                scrollView.smoothScrollTo(
                    (it.left - 16).coerceAtLeast(0), 0
                )
            }
        }
        selectedNavIndex = index
    }
}
