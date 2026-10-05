package com.dsh.bilimerge.core.merge

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegSession
import com.dsh.bilimerge.core.fs.Storage
import com.dsh.bilimerge.core.model.BiliItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用级合并调度器。
 *
 * 刻意不挂在 Activity 上：合并期间用户可能切走或转屏，Activity 重建不应该中断任务。
 * UI 只通过 [revision] 这个单调递增的计数器来被通知刷新，
 * 进度回调本身做了节流，避免成百上千个条目每秒触发几十次重绘。
 */
class MergeManager(private val context: Context) {

    enum class Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    class Task(val item: BiliItem) {
        @Volatile var status: Status = Status.PENDING
        @Volatile var progress: Float = 0f
        @Volatile var speed: Double = 0.0
        @Volatile var message: String = ""
        @Volatile var outputLabel: String = ""
        @Volatile var outputUri: android.net.Uri? = null
        @Volatile var elapsedMs: Long = 0L
        @Volatile var session: FFmpegSession? = null

        val key: String get() = item.key
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskList = CopyOnWriteArrayList<Task>()
    private val revisionCounter = AtomicLong(0)
    private val lastProgressBump = AtomicLong(0)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    @Volatile private var job: Job? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    val tasks: List<Task> get() = taskList

    /**
     * 是否还有任务在跑。
     *
     * 刻意以**任务状态**为准，而不是协程是否存活。原因：万一某个 ffmpeg 会话卡在
     * native 层迟迟不返回，协程会一直挂着，但任务本身早已进入终态。若用 `job.isActive`
     * 判断，「开始合并」会被永久禁用——用户除了手动点「取消」之外没有任何出路。
     * 按任务状态判断则不会：终态就是终态，UI 立刻恢复可用。
     */
    val isRunning: Boolean
        get() = taskList.any { it.status == Status.PENDING || it.status == Status.RUNNING }

    /** 协程是否仍在运行。仅用于诊断：正常情况下它应与 [isRunning] 同步归零 */
    val jobActive: Boolean get() = job?.isActive == true

    val finishedCount: Int
        get() = taskList.count { it.status == Status.DONE || it.status == Status.FAILED || it.status == Status.CANCELLED }

    val successCount: Int get() = taskList.count { it.status == Status.DONE }

    fun start(
        items: List<BiliItem>,
        storage: Storage,
        output: OutputTarget,
        concurrency: Int,
        fastStart: Boolean,
        forceStage: Boolean = false,
        deleteSource: Boolean = false,
        format: OutputFormat = OutputFormat.MP4,
    ): Boolean {
        if (isRunning) return false
        if (items.isEmpty()) return false

        // 上一次的协程有可能还挂着（任务已经终态但协程没退出，例如某个 ffmpeg
        // 会话在 native 层未返回）。既然这次要开新的，就顺手把它收掉，避免协程堆积。
        if (job?.isActive == true) {
            Log.w(TAG, "previous job still active while starting a new one, cancelling it")
            job?.cancel()
        }

        taskList.clear()
        items.forEach { taskList += Task(it) }
        bump()

        job = scope.launch {
            Log.i(TAG, "job start: tasks=${taskList.size} concurrency=$concurrency")
            acquireWakeLock()
            try {
                val engine = MergeEngine(storage)
                val permits = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
                coroutineScope {
                    for ((index, task) in taskList.withIndex()) {
                        launch {
                            Log.d(TAG, "task[$index] begin")
                            permits.withPermit {
                                runOne(engine, task, output, format, fastStart, forceStage, deleteSource)
                            }
                            Log.d(TAG, "task[$index] end")
                        }
                    }
                }
                Log.i(TAG, "coroutineScope returned")
                // 全部任务结束后统一清掉空目录（含条目之间的中间层），
                // 逐个清理时并发判断不可靠，放这里一次后序遍历最稳
                if (deleteSource) {
                    runCatching { engine.pruneEmptyDirs(storage.root()) }
                }
            } finally {
                Log.i(TAG, "job finally, stillRunning=$isRunning")
                releaseWakeLock()
                bump()
            }
        }
        return true
    }

    fun cancelAll() {
        taskList.forEach { it.session?.cancel() }
        taskList.forEach {
            if (it.status == Status.PENDING || it.status == Status.RUNNING) {
                it.status = Status.CANCELLED
                it.message = "已取消"
            }
        }
        job?.cancel()
        bump()
    }

    /** 清空已完成的任务展示 */
    fun clear() {
        if (isRunning) return
        taskList.clear()
        bump()
    }

    /** 应用退出时释放协程作用域 */
    fun shutdown() {
        cancelAll()
        runCatching { scope.cancel() }
    }

    // ------------------------------------------------------------------

    private suspend fun runOne(
        engine: MergeEngine,
        task: Task,
        output: OutputTarget,
        format: OutputFormat,
        fastStart: Boolean,
        forceStage: Boolean,
        deleteSource: Boolean,
    ) {
        Log.d(TAG, "runOne enter: ${task.item.outputBaseName}")
        if (task.status == Status.CANCELLED) return
        task.status = Status.RUNNING
        bump()

        var attempt = 0
        var lastOutcome: MergeEngine.Outcome? = null
        var preparedUri: android.net.Uri? = null
        var useFastStart = fastStart
        var useStage = forceStage

        while (attempt < MAX_ATTEMPTS) {
            if (!currentCoroutineContext().isActive) {
                task.status = Status.CANCELLED
                task.message = "已取消"
                bump()
                return
            }

            val prepared = output.prepare(task.item.outputBaseName, format, staged = useStage)
            if (prepared == null) {
                task.status = Status.FAILED
                task.message = "无法创建输出文件（目录不可写、空间不足或格式不被接受）"
                bump()
                return
            }
            preparedUri = prepared.uri

            val outcome = engine.run(
                item = task.item,
                output = prepared,
                format = format,
                fastStart = useFastStart,
                onProgress = { p, sp ->
                    task.progress = p
                    task.speed = sp
                    bumpThrottled()
                },
                onSessionReady = { task.session = it },
            )
            task.session = null
            lastOutcome = outcome

            if (outcome.ok || outcome.cancelled) break
            attempt++

            // 逐级降级重试：先去掉 faststart（省掉一次全量搬运），再改成暂存后搬运。
            // 遇到不可 seek 的 provider 时，只有暂存这条路能走通；
            // 若目标本身就是真实路径（LegacyFileOutput），暂存没有意义，直接收手。
            when {
                useFastStart -> useFastStart = false
                !useStage && output.supportsStaging -> useStage = true
                else -> break
            }
        }

        val o = lastOutcome
        when {
            o == null -> {
                task.status = Status.FAILED
                task.message = "未知错误"
            }
            o.ok -> {
                task.status = Status.DONE
                task.progress = 1f
                val cost = com.dsh.bilimerge.core.util.Fmt.cost(o.elapsedMs)
                // 清理放在这里而不是 engine 内部：只有 outcome.ok 才意味着成品已经 commit 成功，
                // 此刻缓存才真正可以丢
                task.message = if (deleteSource) {
                    val r = engine.cleanupItem(task.item)
                    when {
                        !r.ok -> "完成 · $cost · 清理失败（${r.reason}）"
                        r.keptRootDir -> "完成 · $cost · 缓存已清空"
                        else -> "完成 · $cost · 缓存已删除"
                    }
                } else {
                    "完成 · $cost"
                }
                task.outputLabel = o.outputLabel
                task.outputUri = preparedUri
                task.elapsedMs = o.elapsedMs
            }
            o.cancelled -> {
                task.status = Status.CANCELLED
                task.message = "已取消"
            }
            else -> {
                task.status = Status.FAILED
                task.message = o.message
            }
        }
        bump()
        Log.d(TAG, "runOne exit: ${task.item.outputBaseName} status=${task.status}")
    }

    // ------------------------------------------------------------------

    private fun bump() {
        _revision.value = revisionCounter.incrementAndGet()
    }

    /** 进度回调可能非常密集，节流到约 8 次/秒 */
    private fun bumpThrottled() {
        val now = SystemClock.elapsedRealtime()
        val last = lastProgressBump.get()
        if (now - last >= PROGRESS_INTERVAL_MS && lastProgressBump.compareAndSet(last, now)) {
            bump()
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BiliMerge:merge")
            lock.setReferenceCounted(false)
            lock.acquire(MAX_WAKELOCK_MS)
            wakeLock = lock
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private companion object {
        const val TAG = "BiliMerge"
        const val MAX_CONCURRENCY = 8
        const val PROGRESS_INTERVAL_MS = 120L
        const val MAX_WAKELOCK_MS = 60L * 60L * 1000L
        /** 最多三次尝试：直写 → 去掉 faststart → 退化到暂存搬运 */
        const val MAX_ATTEMPTS = 3
    }
}
