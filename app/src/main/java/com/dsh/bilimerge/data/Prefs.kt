package com.dsh.bilimerge.data

import android.content.Context
import androidx.core.content.edit

/** 极简配置存储。刻意不上 DataStore/Room：几个键值对不值得引入额外依赖和启动开销。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("bilimerge", Context.MODE_PRIVATE)

    /** 缓存目录的 SAF 树 URI */
    var cacheTreeUri: String?
        get() = sp.getString(KEY_CACHE_TREE, null)
        set(v) = sp.edit { putString(KEY_CACHE_TREE, v) }

    /** 缓存目录的展示路径 */
    var cacheLabel: String?
        get() = sp.getString(KEY_CACHE_LABEL, null)
        set(v) = sp.edit { putString(KEY_CACHE_LABEL, v) }

    /** 自定义输出目录的 SAF 树 URI；为空表示用默认的 Movies/BiliMerge */
    var outputTreeUri: String?
        get() = sp.getString(KEY_OUTPUT_TREE, null)
        set(v) = sp.edit { putString(KEY_OUTPUT_TREE, v) }

    var outputLabel: String?
        get() = sp.getString(KEY_OUTPUT_LABEL, null)
        set(v) = sp.edit { putString(KEY_OUTPUT_LABEL, v) }

    /** 并发合并数，0 = 自动 */
    var concurrency: Int
        get() = sp.getInt(KEY_CONCURRENCY, 0)
        set(v) = sp.edit { putInt(KEY_CONCURRENCY, v) }

    /**
     * 是否给输出加 +faststart。
     * 默认关闭：它需要把整个 mdat 再搬一遍，本地播放毫无收益，纯属浪费 IO。
     */
    var fastStart: Boolean
        get() = sp.getBoolean(KEY_FASTSTART, false)
        set(v) = sp.edit { putBoolean(KEY_FASTSTART, v) }

    /**
     * 强制走"先写私有目录、再搬运到目标"的兼容模式。
     *
     * 默认关闭：直写走 `saf:` 协议，零拷贝，快得多。只有当目标 provider 给出不可 seek 的 fd
     * （mp4 复用器回填 moov 会失败）时才需要它，代价是占用双倍空间并多一次全量拷贝。
     * 即便不开这个开关，单次合并失败后也会自动降级尝试一次。
     */
    var forceStage: Boolean
        get() = sp.getBoolean(KEY_FORCE_STAGE, false)
        set(v) = sp.edit { putBoolean(KEY_FORCE_STAGE, v) }

    /**
     * 合并成功后删除源缓存分片。
     *
     * 默认关闭：删除不可逆，必须由用户明确选择。打开后只在**成品已确认落盘**时执行，
     * 且只删本次用到的 m4s（GB 级的空间大头），不碰 entry.json 与目录本身。
     */
    var deleteSource: Boolean
        get() = sp.getBoolean(KEY_DELETE_SOURCE, false)
        set(v) = sp.edit { putBoolean(KEY_DELETE_SOURCE, v) }

    /** 上次扫描结果（JSON），用于冷启动秒开列表 */
    var scanCache: String?
        get() = sp.getString(KEY_SCAN_CACHE, null)
        set(v) = sp.edit { putString(KEY_SCAN_CACHE, v) }

    companion object {
        private const val KEY_CACHE_TREE = "cache_tree"
        private const val KEY_CACHE_LABEL = "cache_label"
        private const val KEY_OUTPUT_TREE = "output_tree"
        private const val KEY_OUTPUT_LABEL = "output_label"
        private const val KEY_CONCURRENCY = "concurrency"
        private const val KEY_FASTSTART = "faststart"
        private const val KEY_FORCE_STAGE = "force_stage"
        private const val KEY_DELETE_SOURCE = "delete_source"
        private const val KEY_SCAN_CACHE = "scan_cache"
    }
}
