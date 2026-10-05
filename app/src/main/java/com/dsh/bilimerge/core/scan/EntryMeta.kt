package com.dsh.bilimerge.core.scan

import org.json.JSONObject

/**
 * entry.json 的兼容解析。
 *
 * B 站各版本客户端的 entry.json 字段位置变过多次（有的把标题放顶层，有的塞进 page_data，
 * 番剧又多一层 ep），这里按"候选列表取第一个非空"的方式兼容，不做版本判断。
 */
class EntryMeta(
    val title: String,
    val part: String,
    val quality: Int,
    val durationMs: Long,
    val totalBytes: Long,
    val completed: Boolean,
    val pageIndex: Int,
) {
    companion object {

        /** 解析失败返回 null（调用方会退化为按目录名识别） */
        fun parse(json: String): EntryMeta? = runCatching {
            val root = JSONObject(json)
            val page = root.optJSONObject("page_data")
            val ep = root.optJSONObject("ep")

            val title = firstNonBlank(
                root.str("title"),
                page?.str("download_title"),
                ep?.str("title"),
                root.str("download_title"),
                root.str("season_title"),
            ).ifBlank { "未命名" }

            val part = firstNonBlank(
                ep?.str("long_title"),
                page?.str("part"),
                page?.str("download_subtitle"),
                ep?.str("index_title"),
                root.str("part"),
            )

            // B 站自己的字段名少了一个 r（prefered），两个拼写都要认
            val quality = firstPositive(
                root.optInt("prefered_video_quality", 0),
                root.optInt("preferred_video_quality", 0),
                root.str("type_tag").toIntOrNull() ?: 0,
                root.optInt("quality", 0),
            )

            val durationMs = firstPositive(
                root.optLong("total_time_milli", 0L),
                (page?.optLong("duration", 0L) ?: 0L) * 1000L,
            )

            val totalBytes = firstPositive(
                root.optLong("total_bytes", 0L),
                root.optLong("downloaded_bytes", 0L),
            )

            EntryMeta(
                title = title,
                part = part,
                quality = quality,
                durationMs = durationMs,
                totalBytes = totalBytes,
                completed = root.optBoolean("is_completed", true),
                pageIndex = page?.optInt("page", 0) ?: 0,
            )
        }.getOrNull()

        private fun firstNonBlank(vararg values: String?): String =
            values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

        private fun firstPositive(vararg values: Int): Int = values.firstOrNull { it > 0 } ?: 0
        private fun firstPositive(vararg values: Long): Long = values.firstOrNull { it > 0 } ?: 0L
    }
}

/** optString 遇到 JSON null 会返回字符串 "null"，必须当成空串处理 */
private fun JSONObject.str(key: String): String {
    if (!has(key)) return ""
    val v = opt(key) ?: return ""
    if (v === JSONObject.NULL) return ""
    val s = v.toString().trim()
    return if (s.equals("null", true)) "" else s
}
