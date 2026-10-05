package com.dsh.bilimerge.core.merge

import android.os.SystemClock
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.dsh.bilimerge.core.fs.Storage
import com.dsh.bilimerge.core.model.BiliItem

/**
 * 合并执行器：把一条缓存里的视频流与音频流**无损**装进一个 mp4。
 *
 * 关键点：
 *  - 全程 `-c copy`，不做任何解码/编码，速度等于磁盘拷贝速度（几 GB 的视频通常几秒完成）；
 *  - 输入输出都用 ffmpeg-kit 的 `saf:` 协议（进程内 fd），不落地任何临时文件，零拷贝；
 *  - 默认**不加** `+faststart`：那需要把整个 mdat 再搬一遍，对本地播放毫无收益，
 *    只在用户明确需要网络串流时才开启。
 */
class MergeEngine(private val storage: Storage) {

    class Outcome(
        val ok: Boolean,
        val cancelled: Boolean,
        val message: String,
        val elapsedMs: Long,
        val outputLabel: String,
    )

    /**
     * 构造 ffmpeg 参数。返回 null 表示输入不完整（连视频都没有）。
     * 之所以不在参数里做任何 shell 转义：调用方用 executeWithArguments 数组形式，
     * 每个参数原样传给 ffmpeg，路径里有空格、括号、中文都不会出问题。
     */
    fun buildArgs(item: BiliItem, outputUrl: String, fastStart: Boolean): Array<String>? {
        val videoUrls = item.videos.mapNotNull { storage.ffmpegUrl(it, forWrite = false) }
        val audioUrls = item.audios.mapNotNull { storage.ffmpegUrl(it, forWrite = false) }

        if (videoUrls.isEmpty() && audioUrls.isEmpty()) return null

        val args = ArrayList<String>(20)
        args += "-hide_banner"
        args += "-loglevel"; args += "error"
        args += "-nostdin"
        args += "-y"

        when {
            videoUrls.isNotEmpty() && audioUrls.isNotEmpty() -> {
                args += "-i"; args += videoUrls[0]
                args += "-i"; args += audioUrls[0]
                args += "-map"; args += "0:v:0"
                args += "-map"; args += "1:a:0"
            }
            videoUrls.isNotEmpty() -> {
                // 老缓存里的 flv/blv 是音视频复用的，-map 0 整体保留
                args += "-i"; args += videoUrls[0]
                args += "-map"; args += "0"
            }
            else -> {
                args += "-i"; args += audioUrls[0]
                args += "-map"; args += "0:a:0"
            }
        }

        args += "-c"; args += "copy"
        args += "-map_metadata"; args += "0"
        args += "-metadata"; args += "title=${item.displayTitle}"
        if (fastStart) {
            args += "-movflags"; args += "+faststart"
        }
        args += outputUrl
        return args.toTypedArray()
    }

    /**
     * 同步执行一次合并（调用方负责放在 IO 线程与并发控制里）。
     *
     * @param onProgress 进度回调（0f..1f），未知总时长时不会被调用
     * @param onSessionReady 把 session 暴露给取消逻辑
     */
    fun run(
        item: BiliItem,
        output: PreparedOutput,
        fastStart: Boolean,
        onProgress: (Float, Double) -> Unit,
        onSessionReady: (FFmpegSession) -> Unit = {},
    ): Outcome {
        val args = buildArgs(item, output.url, fastStart)
            ?: return Outcome(false, false, "没有可用的视频/音频流", 0L, output.label)

        val errorLog = StringBuilder()
        val totalSec = item.durationMs / 1000.0
        val started = SystemClock.elapsedRealtime()

        val logCallback = LogCallback { log ->
            // log.level 是 Level 枚举，不是 int，取 value 比较
            if (log != null && log.level.value <= Level.AV_LOG_ERROR.value) {
                synchronized(errorLog) {
                    if (errorLog.length < 8192) errorLog.append(log.message).append('\n')
                }
            }
        }
        val statsCallback = StatisticsCallback { st ->
            if (st != null && totalSec > 0) {
                val p = (st.time / totalSec).coerceIn(0.0, 1.0).toFloat()
                onProgress(p, st.speed)
            }
        }

        val session = FFmpegSession.create(args, null, logCallback, statsCallback)
        onSessionReady(session)

        var failure: String? = null
        try {
            FFmpegKitConfig.ffmpegExecute(session)
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
        }

        val elapsed = SystemClock.elapsedRealtime() - started
        val rc = session.returnCode
        val ok = failure == null && ReturnCode.isSuccess(rc)
        val cancelled = ReturnCode.isCancel(rc)

        if (ok) {
            // 暂存模式下 commit 还要把文件搬进目标，这里可能因为空间不足等原因失败
            if (output.commit()) {
                return Outcome(true, false, "完成", elapsed, output.label)
            }
            output.abort()
            val why = if (output.staged) "搬运到目标位置失败（可能剩余空间不足）" else "目标文件写入失败"
            return Outcome(false, false, why, elapsed, output.label)
        }

        output.abort()
        val msg = failure
            ?: extractError(errorLog.toString())
            ?: if (cancelled) "已取消" else "ffmpeg 退出码 ${rc?.value ?: -1}"
        return Outcome(false, cancelled, msg, elapsed, output.label)
    }

    /** 从 ffmpeg 的 stderr 里挑出最有信息量的一行给用户看 */
    private fun extractError(raw: String): String? {
        if (raw.isBlank()) return null
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        val preferred = lines.lastOrNull { l ->
            l.contains("Error", true) || l.contains("Invalid", true) ||
                l.contains("could not", true) || l.contains("failed", true) ||
                l.contains("Permission denied", true)
        }
        val picked = preferred ?: lines.last()
        // ffmpeg 的报错常带前缀，去掉后更干净
        return picked.substringAfter("] ", picked).take(300)
    }
}
