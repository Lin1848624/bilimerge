package com.dsh.bilimerge.core.merge

/**
 * 输出容器格式。
 *
 * 合并全程 `-c copy`，所以这里选的是**容器**而不是编码——画面和声音保持原样，
 * 只是换一层外壳，画质零损失、速度也不变。B 站缓存的 H.264 / HEVC + AAC 组合
 * 在下面几种容器里都能直接装下。
 *
 * 刻意不提供 WebM：它只接受 VP8/VP9/AV1 + Vorbis/Opus，B 站的 H.264/AAC 塞不进去，
 * 列出来只会让人白试一次。
 */
enum class OutputFormat(
    val key: String,
    val ext: String,
    val mime: String,
    val label: String,
    /** 设置界面上给用户看的一句话说明 */
    val note: String,
    /**
     * `-movflags +faststart` 对该容器是否有意义。
     *
     * 它是 MP4/MOV 家族的私有选项。实测给 MKV/TS 加上既不会报错也不会警告，
     * ffmpeg 直接忽略——但仍然按容器过滤，好让拼出来的命令行只含真正起作用的参数。
     */
    val supportsFastStart: Boolean,
) {
    MP4(
        key = "mp4", ext = "mp4", mime = "video/mp4", label = "MP4",
        note = "兼容性最好，手机 / 电脑 / 电视通吃", supportsFastStart = true,
    ),
    MKV(
        key = "mkv", ext = "mkv", mime = "video/x-matroska", label = "MKV",
        note = "最宽容，编码再冷门也装得下", supportsFastStart = false,
    ),
    MOV(
        key = "mov", ext = "mov", mime = "video/quicktime", label = "MOV",
        note = "苹果生态友好，剪辑软件通吃", supportsFastStart = true,
    ),
    TS(
        key = "ts", ext = "ts", mime = "video/mp2t", label = "TS",
        note = "流媒体录制格式，部分剪辑软件偏好", supportsFastStart = false,
    ),
    ;

    /** 带扩展名的完整文件名 */
    fun fileName(baseName: String): String =
        com.dsh.bilimerge.core.util.FileNames.sanitize(baseName) + "." + ext

    /** 界面上展示的一行，例如「MP4 — 兼容性最好…」 */
    val pickerLabel: String get() = "$label — $note"

    companion object {
        fun fromKey(key: String?): OutputFormat = entries.firstOrNull { it.key == key } ?: MP4
    }
}
