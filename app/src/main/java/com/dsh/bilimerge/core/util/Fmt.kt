package com.dsh.bilimerge.core.util

import java.util.Locale

object Fmt {

    /** 人类可读体积 */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    /** 时长 mm:ss / h:mm:ss */
    fun duration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 毫秒耗时 */
    fun cost(ms: Long): String =
        if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)

    /** 速度：字节/秒 */
    fun speed(bytesPerSec: Double): String =
        if (bytesPerSec <= 0) "" else size(bytesPerSec.toLong()) + "/s"
}

object FileNames {

    /** Windows/Android 双端都不接受的字符，外加控制字符 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val SPACES = Regex("\\s+")

    fun sanitize(raw: String, maxLen: Int = 96): String {
        var s = ILLEGAL.replace(raw, "_")
        s = SPACES.replace(s, " ").trim()
        s = s.trimEnd('.', ' ')
        if (s.isEmpty()) s = "未命名"
        if (s.length > maxLen) s = s.substring(0, maxLen).trimEnd('.', ' ')
        return s.ifEmpty { "未命名" }
    }

    /** 生成 mp4 文件名 */
    fun mp4(baseName: String): String = sanitize(baseName) + ".mp4"
}
