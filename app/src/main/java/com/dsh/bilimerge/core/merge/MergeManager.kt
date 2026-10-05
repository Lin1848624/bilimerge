package com.dsh.bilimerge.core.merge

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
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
    val isRunning: Boolean get() = job?.isActive == true

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
    ): Boolean {
        if (isRunning) return false
        if (items.isEmpty()) return false

        taskList.clear()
        items.forEach { taskList += Task(it) }
        bump()

        job = scope.launch {
            acquireWakeLock()
            try {
                val engine = MergeEngine(storage)
                val permits = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
                coroutineScope {
                    for (task in taskList) {
                        launch { permits.withPermit { runOne(engine, task, output, fastStart, forceStage) } }
                    }
                }
            } finally {
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
        fastStart: Boolean,
        forceStage: Boolean,
    ) {
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

            val prepared = output.prepare(task.item.outputBaseName, staged = useStage)
            if (prepared == null) {
                task.status = Status.FAILED
                task.message = "无法创建输出文件（目录不可写或空间不足）"
                bump()
                return
            }
            preparedUri = prepared.uri

            val outcome = engine.run(
                item = task.item,
                output = prepared,
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
                task.message = "完成 · ${com.dsh.bilimerge.core.util.Fmt.cost(o.elapsedMs)}"
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
        const val MAX_CONCURRENCY = 8
        const val PROGRESS_INTERVAL_MS = 120L
        const val MAX_WAKELOCK_MS = 60L * 60L * 1000L
        /** 最多三次尝试：直写 → 去掉 faststart → 退化到暂存搬运 */
        const val MAX_ATTEMPTS = 3
    }
}
