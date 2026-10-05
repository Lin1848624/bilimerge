package com.dsh.bilimerge.core.model

import com.dsh.bilimerge.core.fs.DocRef

/**
 * 一个可合并的缓存条目 = B 站的一个分P / 一集。
 * 视频流与音频流在缓存里是分开的 m4s，合并就是把它们无损装进同一个 mp4。
 */
class BiliItem(
    /** 稳定唯一键，用于选中状态与完成记录 */
    val key: String,
    /** 条目所在目录的展示路径 */
    val dirLabel: String,
    /** 主标题（合集/视频名） */
    val title: String,
    /** 分P名 / 剧集名，可能为空 */
    val part: String,
    /** 清晰度代码，见 qualityLabel */
    val quality: Int,
    /** 时长（毫秒），未知为 0 */
    val durationMs: Long,
    /** 视频流，一般只有一路 */
    val videos: List<DocRef>,
    /** 音频流，一般只有一路；纯视频缓存时为空 */
    val audios: List<DocRef>,
    /** 原始缓存总字节数（视频+音频） */
    val sourceBytes: Long,
    /** 是否存在 entry.json（false 说明是兜底识别出来的） */
    val hasMeta: Boolean,
    /**
     * 条目根目录：entry.json 所在目录；兜底识别时是被归并到的那一层。
     * 开启清理后删的就是它，连同其中所有内容（分片、entry.json、其它清晰度、弹幕）。
     */
    val rootDir: DocRef?,
    /**
     * [rootDir] 是否恰好就是用户选中的扫描根目录。
     * 是的话绝不能连它一起删——那样用户选的目录会凭空消失，
     * 这种情况只清空其内容、保留目录本身。
     */
    val rootIsScanRoot: Boolean,
) {
    /** 列表里显示的标题 */
    val displayTitle: String
        get() {
            val t = title.ifBlank { "未命名" }
            val p = part.trim()
            if (p.isEmpty() || p == t) return t
            if (t.contains(p)) return t
            return "$t · $p"
        }

    /** 建议的输出文件名（不含扩展名），已做非法字符过滤 */
    val outputBaseName: String
        get() {
            val t = title.ifBlank { "未命名" }
            val p = part.trim()
            val raw = when {
                p.isEmpty() || p == t -> t
                t.contains(p) -> t
                else -> "${t}_$p"
            }
            return raw
        }

    val qualityLabel: String get() = qualityName(quality)

    val hasAudio: Boolean get() = audios.isNotEmpty()
    val hasVideo: Boolean get() = videos.isNotEmpty()

    companion object {
        fun qualityName(q: Int): String = when (q) {
            127 -> "8K 超高清"
            126 -> "杜比视界"
            125 -> "HDR 真彩"
            120 -> "4K 超清"
            116 -> "1080P60"
            112 -> "1080P 高码率"
            80 -> "1080P 高清"
            74 -> "720P60"
            64 -> "720P 高清"
            32 -> "480P 清晰"
            16 -> "360P 流畅"
            6 -> "240P 极速"
            else -> if (q > 0) "画质 $q" else "未知画质"
        }
    }
}
