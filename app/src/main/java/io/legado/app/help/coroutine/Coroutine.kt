package io.legado.app.help.coroutine

import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/**
 * 链式协程
 * 注意：如果协程太快完成，回调会不执行
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class Coroutine<T>(
    private val scope: CoroutineScope,
    context: CoroutineContext = Dispatchers.IO,
    private val startOption: CoroutineStart = CoroutineStart.DEFAULT,
    private val executeContext: CoroutineContext = Dispatchers.Main,
    private val semaphore: Semaphore? = null,
    block: suspend CoroutineScope.() -> T
) {

    companion object {

        private val DEFAULT = MainScope()

        fun <T> async(
            scope: CoroutineScope = DEFAULT,
            context: CoroutineContext = Dispatchers.IO,
            start: CoroutineStart = CoroutineStart.DEFAULT,
            executeContext: CoroutineContext = Dispatchers.Main,
            semaphore: Semaphore? = null,
            block: suspend CoroutineScope.() -> T
        ): Coroutine<T> {
            return Coroutine(scope, context, start, executeContext, semaphore, block)
        }

    }

    private val job: Job

    private var start: VoidCallback? = null
    private var success: Callback<T>? = null
    private var error: Callback<Throwable>? = null
    private var finally: VoidCallback? = null
    private var cancel: VoidCallback? = null

    private var timeMillis: Long? = null
    private var errorReturn: Result<T>? = null

    val isCancelled: Boolean
        get() = job.isCancelled

    val isActive: Boolean
        get() = job.isActive

    val isCompleted: Boolean
        get() = job.isCompleted

    init {
        this.job = executeInternal(context, block)
    }

    fun timeout(timeMillis: () -> Long): Coroutine<T> {
        this.timeMillis = timeMillis()
        return this@Coroutine
    }

    fun timeout(timeMillis: Long): Coroutine<T> {
        this.timeMillis = timeMillis
        return this@Coroutine
    }

    fun onErrorReturn(value: () -> T?): Coroutine<T> {
        this.errorReturn = Result(value())
        return this@Coroutine
    }

    fun onErrorReturn(value: T?): Coroutine<T> {
        this.errorReturn = Result(value)
        return this@Coroutine
    }

    fun onStart(
        context: CoroutineContext? = null,
        block: (suspend CoroutineScope.() -> Unit)
    ): Coroutine<T> {
        this.start = VoidCallback(context, block)
        return this@Coroutine
    }

    fun onSuccess(
        context: CoroutineContext? = null,
        block: suspend CoroutineScope.(T) -> Unit
    ): Coroutine<T> {
        this.success = Callback(context, block)
        return this@Coroutine
    }

    fun onError(
        context: CoroutineContext? = null,
        block: suspend CoroutineScope.(Throwable) -> Unit
    ): Coroutine<T> {
        this.error = Callback(context, block)
        return this@Coroutine
    }

    /**
     * 如果协程被取消，不执行
     */
    fun onFinally(
        context: CoroutineContext? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Coroutine<T> {
        this.finally = VoidCallback(context, block)
        return this@Coroutine
    }

    fun onCancel(
        context: CoroutineContext? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Coroutine<T> {
        //这里仅仅是注册回调而不是真的触发 cancel 回调
        //io.legado.app.constant.AppLog.put("ZombieTask Log: Coroutine.onCancel() called. ", null, false)
        this.cancel = VoidCallback(context, block)
        job.invokeOnCompletion {
            if (it is CancellationException && it !is ActivelyCancelException) {
                // 不能被 if (!scope.isActive) 拦截，所以不使用 cancel 方法
                // 但是又不能删除 cancel 的防御机制，之前内存泄漏就会对已经死去的线程错误调用。
                io.legado.app.constant.AppLog.put("ZombieTask Log: invokeOnCompletion called with CancellationException 应该是突然退出正文等不一般的取消情景", null, false)
                val cancelCallback = cancel ?: return@invokeOnCompletion
                DEFAULT.launch(executeContext) {
                    if (null == cancelCallback.context) {
                        cancelCallback.block.invoke(this)
                    } else {
                        withContext(cancelCallback.context) {
                            cancelCallback.block.invoke(this)
                        }
                    }
                }
            }
        }
        return this@Coroutine
    }

    //取消当前任务
    fun cancel(cause: ActivelyCancelException = ActivelyCancelException()) {
        if (job.isCompleted) {
            io.legado.app.constant.AppLog.put("ZombieTask Log: Coroutine.Cancel() 错误调用. job.isCompleted 但是触发了 onCancel", null, false)
            return
        }
        val trace = android.util.Log.getStackTraceString(Throwable())
        io.legado.app.constant.AppLog.put("ZombieTask Log: Coroutine.cancel() called. job.isCancelled=${job.isCancelled}\nStack trace:\n$trace", null, false)
        //必须同步，及时发出取消信号
        if (!job.isCancelled) {
            //其实是抛出一个 CancellationException 异常
            // 这一句执行后，底层的 isCancelled 就会立刻变为 true
            job.cancel(cause)
        }
        cancel?.let {
            io.legado.app.constant.AppLog.put("ZombieTask Log: Executing onCancel block on executeContext", null, false)
            //异步，回调善后工作要异步
            //业界标准的 “快速失败（Fast-Fail）+ 异步清理（Async Cleanup）” 架构
            DEFAULT.launch(executeContext) {
                if (null == it.context) {
                    it.block.invoke(this)
                } else {
                    withContext(it.context) {
                        //传进来的回调 { onCancel(chapterIndex)}
                        it.block.invoke(this)
                    }
                }
            }
        } ?: io.legado.app.constant.AppLog.put("ZombieTask Log: No onCancel block found", null, false)
    }

    fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle {
        return job.invokeOnCompletion(handler)
    }

    fun start() {
        job.start()
    }

    //执行外部代码的地方
    private fun executeInternal(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): Job {
        return (scope.plus(executeContext)).launch(start = startOption) {
            semaphore?.acquire()
            try {
                start?.let { dispatchVoidCallback(this, it) }
                ensureActive()
                val value = executeBlock(this, context, timeMillis ?: 0L, block)
                ensureActive()
                success?.let { dispatchCallback(this, value, it) }
            } catch (e: Throwable) {
                e.printOnDebug()
                val consume: Boolean = errorReturn?.value?.let { value ->
                    success?.let { dispatchCallback(this, value, it) }
                    true
                } ?: false
                if (!consume) {
                    io.legado.app.constant.AppLog.put("ZombieTask Log: Coroutine.dispatchCallback of ERROR callback will be called.", null, false)
                    error?.let { dispatchCallback(this, e, it) }
                }
            } finally {
                try {
                    finally?.let { dispatchVoidCallback(this, it) }
                } finally {
                    semaphore?.release()
                }
            }
        }
    }

    private suspend inline fun dispatchVoidCallback(scope: CoroutineScope, callback: VoidCallback) {
        if (null == callback.context) {
            callback.block.invoke(scope)
        } else {
            withContext(callback.context) {
                callback.block.invoke(this)
            }
        }
    }

    private suspend inline fun <R> dispatchCallback(
        scope: CoroutineScope,
        value: R,
        callback: Callback<R>
    ) {
        io.legado.app.constant.AppLog.put("ZombieTask Log: Coroutine.dispatchCallback called.", null, false)
        //在协程被主动取消（比如用户退出了界面）后，阻止后续的回调（如更新 UI/ onError）被触发
        // isActive 并不是开发者自己维护的布尔变量，而是 Kotlin 官方协程库 (kotlinx.coroutines) 内部管理的一个核心状态属性。
        if (!scope.isActive) {
            io.legado.app.constant.AppLog.put("ZombieTask Log: dispatchCallback skipped because !scope.isActive (value=$value)", null, false)
            return
        }
        if (null == callback.context) {
            callback.block.invoke(scope, value)
        } else {
            withContext(callback.context) {
                callback.block.invoke(this, value)
            }
        }
    }

    private suspend inline fun executeBlock(
        scope: CoroutineScope,
        context: CoroutineContext,
        timeMillis: Long,
        noinline block: suspend CoroutineScope.() -> T
    ): T {
        return withContext(context) {
            if (timeMillis > 0L) withTimeout(timeMillis) {
                block()
            } else {
                block()
            }
        }
    }

    private data class Result<out T>(val value: T?)

    private class VoidCallback(
        val context: CoroutineContext?,
        val block: suspend CoroutineScope.() -> Unit
    )

    private class Callback<VALUE>(
        val context: CoroutineContext?,
        val block: suspend CoroutineScope.(VALUE) -> Unit
    )
}
