package com.dsh.bilimerge.core.scan

import com.dsh.bilimerge.core.fs.DocRef
import com.dsh.bilimerge.core.fs.Storage
import com.dsh.bilimerge.core.model.BiliItem

/**
 * B 站缓存目录识别器。
 *
 * 设计原则：**不假设任何固定目录结构**。B 站客户端的缓存布局从
 *   {avid}/{cid}/lua.flv.bili2api.80/{0.m4s,1.m4s}
 * 演变到
 *   {avid}/{cid}/{quality}/{video.m4s,audio.m4s}
 * 再到番剧的 {season}/{ep}/... ，未来还会变。因此这里以 entry.json 作为"条目锚点"，
 * 在锚点目录下递归收集所有媒体文件并按子目录分组（每个子目录 = 一个清晰度的一组流），
 * 完全不关心中间隔了几层、叫什么名字。没有 entry.json 时才退化为按目录结构启发式识别。
 */
class BiliScanner(private val storage: Storage) {

    class Progress(val scannedDirs: Int, val foundItems: Int, val currentName: String)

    // 扫描期缓存的目录树，避免 collectMedia 时重复发起查询
    private val childrenMap = HashMap<String, List<DocRef>>()
    private val parentMap = HashMap<String, String>()
    private val nodeMap = HashMap<String, DocRef>()
    private var rootKey: String = ""

    fun scan(
        root: DocRef,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<BiliItem> {
        childrenMap.clear()
        parentMap.clear()
        nodeMap.clear()
        rootKey = root.key

        val allMedia = ArrayList<DocRef>(256)
        val entryFiles = ArrayList<DocRef>(64)

        // ---- 1. 广度优先遍历整棵树，一次拿到所有目录与媒体文件 ----
        // 根目录本身也要登记进 nodeMap：它就是"用户选中的目录"，
        // 清理逻辑需要拿到它的 DocRef 才能判断该不该跳过
        nodeMap[root.key] = root
        var frontier = ArrayList<DocRef>(1).also { it += root }
        var scanned = 0
        while (frontier.isNotEmpty()) {
            if (isCancelled()) return emptyList()
            val next = ArrayList<DocRef>(frontier.size * 2)
            for (dir in frontier) {
                scanned++
                val kids = storage.children(dir)
                childrenMap[dir.key] = kids
                for (k in kids) {
                    nodeMap[k.key] = k
                    parentMap[k.key] = dir.key
                    when {
                        k.isDir -> next += k
                        k.isMediaFile() -> allMedia += k
                        k.name.equals(ENTRY_JSON, ignoreCase = true) -> entryFiles += k
                    }
                }
            }
            onProgress(Progress(scanned, 0, frontier.firstOrNull()?.name ?: ""))
            frontier = next
            if (scanned > MAX_DIRS) break
        }

        if (allMedia.isEmpty()) {
            onProgress(Progress(scanned, 0, ""))
            return emptyList()
        }

        val claimed = HashSet<String>(allMedia.size)
        val items = ArrayList<BiliItem>(entryFiles.size + 8)

        // ---- 2. 以 entry.json 为锚点识别（深目录优先，避免嵌套互相抢文件）----
        val sortedEntries = entryFiles.sortedByDescending { depthOf(it.key) }
        for (entry in sortedEntries) {
            if (isCancelled()) return items
            val dirKey = parentMap[entry.key] ?: continue
            val media = ArrayList<DocRef>(8)
            collectMedia(dirKey, media)
            val fresh = media.filter { it.key !in claimed }
            if (fresh.isEmpty()) continue
            claimed += fresh.map { it.key }

            val meta = storage.readText(entry)?.let { EntryMeta.parse(it) }
            items += buildItem(dirKey, meta, fresh)
        }

        // ---- 3. 剩余的媒体文件：按目录结构兜底识别 ----
        val leftovers = allMedia.filter { it.key !in claimed }
        if (leftovers.isNotEmpty()) {
            // 先按媒体所在目录分组
            val byDir = LinkedHashMap<String, MutableList<DocRef>>()
            for (m in leftovers) {
                val d = parentMap[m.key] ?: continue
                byDir.getOrPut(d) { ArrayList(4) } += m
            }
            // 再判断是否需要"向上归并清晰度层"
            val merged = LinkedHashMap<String, MutableList<DocRef>>()
            for ((dirKey, files) in byDir) {
                val target = mergeTarget(dirKey)
                merged.getOrPut(target) { ArrayList(4) } += files
            }
            for ((dirKey, files) in merged) {
                items += buildItem(dirKey, null, files)
            }
        }

        // 按标题+分P排序，顺便去重（同一 key 只留一份）
        val seen = HashSet<String>(items.size)
        val result = items.filter { seen.add(it.key) }
            .sortedWith(compareBy({ it.title }, { it.part }, { -it.quality }))
        onProgress(Progress(scanned, result.size, ""))
        return result
    }

    // ------------------------------------------------------------------

    private fun collectMedia(dirKey: String, out: MutableList<DocRef>) {
        val kids = childrenMap[dirKey] ?: return
        for (k in kids) {
            if (k.isDir) collectMedia(k.key, out) else if (k.isMediaFile()) out += k
        }
    }

    private fun depthOf(key: String): Int {
        var d = 0
        var cur = parentMap[key]
        while (cur != null) {
            d++
            if (d > 32) break
            cur = parentMap[cur]
        }
        return d
    }

    /**
     * 兜底模式下判断某个"媒体所在目录"是否应归并到父目录。
     *
     * 新版缓存是 `{avid}/{cid}/{quality}/video.m4s`。没有 entry.json 时若让每个目录各自成条目，
     * 同一个分P的多个清晰度会被拆成好几条，合并时白白重复劳动。
     * B 站画质码（0/6/16/32/64/74/80/112/116/120/125/126/127）都远小于 200，
     * 而 cid 是 8~9 位数，据此可以稳定区分"画质层"和"分P层"。
     */
    private fun mergeTarget(dirKey: String): String {
        val dir = nodeMap[dirKey] ?: return dirKey
        val parent = parentMap[dirKey] ?: return dirKey
        val q = dir.name.toIntOrNull() ?: return dirKey
        return if (q in 0..200) parent else dirKey
    }

    private fun buildItem(dirKey: String, meta: EntryMeta?, media: List<DocRef>): BiliItem {
        val groups = LinkedHashMap<String, StreamGroup>()
        for (m in media) {
            val gk = parentMap[m.key] ?: dirKey
            groups.getOrPut(gk) { StreamGroup(gk, qualityOf(gk)) }.add(m, storage)
        }

        val best = pickBestGroup(groups, meta?.quality ?: 0)
        val videos = best?.videos ?: emptyList()
        val audios = best?.audios ?: emptyList()

        val title: String
        val part: String
        if (meta != null) {
            title = meta.title
            part = meta.part
        } else {
            title = fallbackTitle(dirKey)
            part = ""
        }

        val quality = meta?.quality?.takeIf { it > 0 } ?: best?.quality?.takeIf { it > 0 } ?: 0
        val bytes = (best?.totalBytes ?: 0L).takeIf { it > 0 } ?: media.sumOf { it.size }

        return BiliItem(
            key = "$dirKey#${best?.dirKey ?: ""}",
            dirLabel = labelOf(dirKey),
            title = title,
            part = part,
            quality = quality,
            durationMs = meta?.durationMs ?: 0L,
            videos = videos,
            audios = audios,
            sourceBytes = if (bytes > 0) bytes else (meta?.totalBytes ?: 0L),
            hasMeta = meta != null,
            rootDir = nodeMap[dirKey],
            rootIsScanRoot = dirKey == rootKey,
        )
    }

    /**
     * 从流组所在目录名提取画质码：
     * 老结构 `lua.flv.bili2api.80` 取末段 80，新结构 `80` 直接就是 80。
     */
    private fun qualityOf(groupDirKey: String): Int {
        val name = nodeMap[groupDirKey]?.name ?: return 0
        return name.substringAfterLast('.').toIntOrNull()?.takeIf { it in 1..200 } ?: 0
    }

    /** 多个清晰度时挑一个：优先与 entry.json 记录的清晰度一致，其次体积最大 */
    private fun pickBestGroup(
        groups: Map<String, StreamGroup>,
        preferQuality: Int,
    ): StreamGroup? {
        if (groups.isEmpty()) return null
        if (groups.size == 1) return groups.values.first()
        val sorted = groups.values.sortedWith(
            compareByDescending<StreamGroup> { if (preferQuality > 0 && it.quality == preferQuality) 1 else 0 }
                .thenByDescending { it.totalBytes }
                .thenByDescending { it.quality }
        )
        return sorted.first()
    }

    private fun fallbackTitle(dirKey: String): String {
        val chain = ArrayList<String>(4)
        var cur: String? = dirKey
        while (cur != null && cur != rootKey && chain.size < 3) {
            nodeMap[cur]?.let { chain += it.name }
            cur = parentMap[cur]
        }
        return chain.reversed().joinToString(" / ").ifBlank { "未命名" }
    }

    private fun labelOf(dirKey: String): String = fallbackTitle(dirKey)

    /** 一组流：同一清晰度下的视频与音频 */
    private class StreamGroup(val dirKey: String, val quality: Int) {
        val videos = ArrayList<DocRef>(1)
        val audios = ArrayList<DocRef>(1)
        val totalBytes: Long get() = videos.sumOf { it.size } + audios.sumOf { it.size }

        fun add(file: DocRef, storage: Storage) {
            when (classify(file, storage)) {
                Kind.VIDEO -> videos += file
                Kind.AUDIO -> audios += file
                Kind.UNKNOWN -> Unit
            }
        }
    }

    private enum class Kind { VIDEO, AUDIO, UNKNOWN }

    private companion object {
        const val ENTRY_JSON = "entry.json"
        const val MAX_DIRS = 20000

        /** 先看文件名（覆盖 99% 的缓存），定不下来才读文件头嗅探 moov.hdlr */
        fun classify(file: DocRef, storage: Storage): Kind {
            val n = file.name.lowercase()
            if (n.contains("video")) return Kind.VIDEO
            if (n.contains("audio")) return Kind.AUDIO
            when (file.extension) {
                "m4a", "aac", "mp3" -> return Kind.AUDIO
                "blv", "flv" -> return Kind.VIDEO // flv 通常音视频复用，按单输入处理
                "m4s", "mp4" -> {
                    when (n.substringBeforeLast('.')) {
                        "0" -> return Kind.VIDEO
                        "1" -> return Kind.AUDIO
                    }
                }
            }
            val head = storage.readHead(file, HEAD_SNIFF_BYTES) ?: return Kind.UNKNOWN
            return when (Mp4Sniffer.sniffHandlerType(head)) {
                "vide" -> Kind.VIDEO
                "soun" -> Kind.AUDIO
                else -> Kind.UNKNOWN
            }
        }

        const val HEAD_SNIFF_BYTES = 256 * 1024
    }
}
