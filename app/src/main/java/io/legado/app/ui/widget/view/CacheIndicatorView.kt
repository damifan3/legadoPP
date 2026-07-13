package io.legado.app.ui.widget.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.ColorUtils
import io.legado.app.help.book.isLocalTxt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 离线缓存进度可视化组件 (CacheIndicatorView)
 * 
 * 作用：在离线下载等对话框中，以色块条的形式直观展示哪些章节已下载，哪些未下载。
 * 核心优化：
 * 1. 动态适配 Legado 主题系统（明暗模式、自定义色无缝兼容）。
 * 2. 采用“连续块合并绘制算法”，上万章小说也能在毫秒级完成渲染，极其顺滑。
 */
class CacheIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 绘制已缓存章节的画笔 (使用主题高亮色)
    private val cachedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    // 绘制未缓存章节的背景画笔 (使用具有一定透明度的主题高亮色，以免刺眼)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 书籍的总章节数
    private var totalCount: Int = 0
    
    // 记录每一章缓存状态的布尔数组：true 表示已缓存，false 表示未缓存
    private var cachedIndices: BooleanArray? = null

    init {
        // 初始化时根据当前的主题配置更新画笔颜色
        updateColors()
    }

    /**
     * 外部调用的数据注入方法。
     * 当后台协程算完章节缓存状态后，将总章节数和对应的状态数组传给此 View 即可。
     */
    fun setCacheData(totalCount: Int, cachedIndices: BooleanArray) {
        this.totalCount = totalCount
        this.cachedIndices = cachedIndices
        
        // 数据更新时，顺带刷新一次颜色（防范在此期间用户切后台更改了主题）
        updateColors()
        
        // 触发 View 的重绘 (系统会调用底下的 onDraw)
        invalidate()
    }

    /**
     * 更新画笔颜色，适配 Legado 的全局主题系统
     */
    private fun updateColors() {
        // 获取用户当前配置的主题高亮色 (如暗黑模式下是柔和的色彩，白天是鲜艳色彩)
        val accentColor = ThemeStore.accentColor(context)
        
        // 已缓存部分：直接采用高亮色
        cachedPaint.color = accentColor
        
        // 未缓存部分(即底部槽)：取高亮色但赋予 20% 的透明度 (Alpha=0.2)
        // 这样既能看清未缓存区域，又不会喧宾夺主，保持整体美观
        backgroundPaint.color = ColorUtils.withAlpha(accentColor, 0.2f)
    }

    /**
     * 核心绘制逻辑
     * 注意：因为此函数在界面刷新时可能会被频繁调用，里面不要进行分配内存的操作(避免 new 对象)。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 提取到局部变量，避免由于多线程修改引发不一致
        val tCount = totalCount
        val indices = cachedIndices

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 画一层铺满整个控件的底色（也就是全部未缓存的状态）
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // 如果没有数据，画完底色直接结束
        if (tCount <= 0 || indices == null || indices.isEmpty()) {
            return
        }

        // 2. 使用“连续块合并绘制算法”绘制已缓存的色块
        // start 用来记录连续缓存章节的起点索引，-1 表示当前处于“未缓存”片段
        var start = -1
        
        for (i in 0 until tCount) {
            // 安全判定，防止数组越界，并判断第 i 章是否缓存
            if (i < indices.size && indices[i]) {
                // 如果当前章节已缓存，并且之前没有记录起点，则将起点设为当前章节 i
                if (start == -1) start = i
            } else {
                // 如果当前章节【未】缓存，且我们之前有一段连续的已缓存区 (start != -1)
                // 这意味着之前那一段缓存区到此结束了，把它一口气画出来！
                if (start != -1) {
                    // 左侧坐标：起点索引 / 总章节数 * 总宽度
                    val left = (start.toFloat() / tCount) * w
                    // 右侧坐标：当前断开的索引 / 总章节数 * 总宽度
                    val right = (i.toFloat() / tCount) * w
                    
                    // 画出一个长长的实心矩形块
                    canvas.drawRect(left, 0f, right, h, cachedPaint)
                    
                    // 画完后，重置起点，等待下一波连续缓存段
                    start = -1
                }
            }
        }
        
        // 3. 扫尾工作：如果到最后正好是以“已缓存”结尾，还没有被画出来，在这里补刀画完。
        if (start != -1) {
            val left = (start.toFloat() / tCount) * w
            val right = w // 既然是最后，右侧边界直接贴边
            canvas.drawRect(left, 0f, right, h, cachedPaint)
        }
    }
}

/**
 * 离线缓存对话框初始化可视化进度的通用扩展函数。
 * 抽离出来供多处（如书籍详情页、阅读页）复用。
 */
fun io.legado.app.databinding.DialogDownloadChoiceBinding.initCacheVisualizer(
    context: Context,
    book: io.legado.app.data.entities.Book,
    scope: kotlinx.coroutines.CoroutineScope
) {
    // 生命周期绑定到当前activity，窗口关闭任务立刻取消
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val total = book.totalChapterNum
        val cachedIndices = BooleanArray(total)
        var cachedCount = 0
        if (book.isLocalTxt) {
            cachedCount = total
            for (i in 0 until total) cachedIndices[i] = true
        } else {
            val cacheFileNames = io.legado.app.help.book.BookHelp.getChapterFiles(book)
            val chapters = io.legado.app.data.appDb.bookChapterDao.getChapterList(book.bookUrl)
            for (chapter in chapters) {
                if (chapter.isVolume || cacheFileNames.contains(chapter.getFileName())) {
                    if (chapter.index in 0 until total) {
                        cachedIndices[chapter.index] = true
                        cachedCount++
                    }
                }
            }
        }
        //切换到主UI线程，只有main可以修改界面
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            tvCacheSummary.text = context.getString(io.legado.app.R.string.cache_summary, cachedCount, total - cachedCount)
            tvCacheSummary.visibility = View.VISIBLE
            cacheIndicator.setCacheData(total, cachedIndices)
            cacheIndicator.visibility = View.VISIBLE
        }
    }
}
