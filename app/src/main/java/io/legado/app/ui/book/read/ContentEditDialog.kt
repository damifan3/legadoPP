package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogContentEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内容编辑
 */
class ContentEditDialog : BaseDialogFragment(R.layout.dialog_content_edit) {

    val binding by viewBinding(DialogContentEditBinding::bind)
    val viewModel by viewModels<ContentEditViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = ReadBook.curTextChapter?.title
        initMenu()
        binding.toolBar.setOnClickListener {
            lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter = withContext(IO) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                } ?: return@launch
                editTitle(chapter)
            }
        }
        viewModel.loadStateLiveData.observe(viewLifecycleOwner) {
            if (it) {
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
            }
        }
        viewModel.initContent { text ->
            binding.contentView.setText(text)
            binding.contentView.post {
                binding.contentView.apply {
                    val startPos = arguments?.getInt("chapterPos") ?: ReadBook.durChapterPos
                    var targetOffset = startPos
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null) {
                        val page = textChapter.getPageByReadPos(startPos)
                        // 找出真正包含该光标的排版行（条件改为查找“起始位置小于等于光标的最后一行”，这才是真正包含当前光标的正确行）
                        val textLine = page?.lines?.lastOrNull { line ->
                            line.chapterPosition <= startPos
                        } ?: page?.lines?.firstOrNull()

                        if (textLine != null) {
                            //正在读的段落（排版层）
                            val pNum = textLine.paragraphNum
                            //通过自然段落号在整个 textChapter.pages 找出该段的第一行排版数据 firstLineOfP
                            val firstLineOfP = textChapter.pages.flatMap { it.lines }.firstOrNull { it.paragraphNum == pNum }
                            //段落内偏移
                            val offsetInP = if (firstLineOfP != null) {
                                startPos - firstLineOfP.chapterPosition
                            } else 0

                            // 计算排版层中属于标题的段落数（如果有标题通常为1，没有则是0）
                            val titlePCount = textChapter.pages.flatMap { it.lines }
                                .filter { it.isTitle }
                                .map { it.paragraphNum }
                                .distinct().count()
                            // 真正的目标跳过次数 = 排版层段落号 - 标题段落数
                            val targetPNum = pNum - titlePCount

                            if (targetPNum > 0) {
                                var currentPNum = 1
                                var offset = 0
                                //1. 第1个\n（从1开始）是第2段落（从1开始）的开始。要定位第十个段落（pNum=10），就要找到第9个\n，
                                //2. 用 <, 循环结束时currentPNum（从1开始）正好=10=pNum，循环体执行了9次，\n找到了第9个
                                //3. 但是正文中有标题（pNum=1），源码编辑中没有这一段落，所以要减去标题行数
                                while (currentPNum < targetPNum && offset < text.length) {
                                    val nextLineBreak = text.indexOf('\n', offset)
                                    if (nextLineBreak == -1) break
                                    offset = nextLineBreak + 1
                                    currentPNum++
                                }
                                targetOffset = offset + maxOf(0, offsetInP)
                            } else {
                                targetOffset = maxOf(0, offsetInP)
                            }

                            if (targetOffset > text.length) {
                                targetOffset = text.length
                            }
                        }
                    }
                    val lineIndex = layout.getLineForOffset(targetOffset)
                    var lineHeight = layout.getLineTop(lineIndex)
                    // 计算允许的最大滚动距离 (文字总排版高度 - 控件实际展示高度)
                    val maxScroll = maxOf(0, layout.height - (height - compoundPaddingTop - compoundPaddingBottom))
                    // 限制 lineHeight 不能超出最大滚动边界
                    lineHeight = minOf(lineHeight, maxScroll)
                    scrollTo(0, lineHeight)
                }
            }
        }
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.content_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    save()
                    dismiss()
                }
                R.id.menu_reset -> viewModel.initContent(true) { content ->
                    binding.contentView.setText(content)
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
                R.id.menu_copy_all -> requireContext()
                    .sendToClip("${binding.toolBar.title}\n${binding.contentView.text}")
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun editTitle(chapter: BookChapter) {
        alert {
            setTitle(R.string.edit)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater)
            alertBinding.editView.setText(chapter.title)
            setCustomView(alertBinding.root)
            okButton {
                chapter.title = alertBinding.editView.text.toString()
                lifecycleScope.launch {
                    withContext(IO) {
                        chapter.update()
                    }
                    binding.toolBar.title = chapter.getDisplayTitle()
                    ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = binding.contentView.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            ReadBook.loadContent(ReadBook.durChapterIndex, resetPageOffset = false)
        }
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var content: String? = null

        fun initContent(reset: Boolean = false, success: (String) -> Unit) {
            execute {
                val book = ReadBook.book ?: return@execute null
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val content = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                success.invoke(it ?: "")
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

    }

}