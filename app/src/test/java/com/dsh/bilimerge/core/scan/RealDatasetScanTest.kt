package com.dsh.bilimerge.core.scan

import com.dsh.bilimerge.core.fs.FileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 端到端识别测试：用真实生成的分片式 fMP4（与 B 站缓存同构）走完整扫描流程。
 *
 * 数据集 `testcache.zip` 由 ffmpeg 以 `-movflags frag_keyframe+empty_moov` 生成，
 * 覆盖新版结构、旧版多清晰度、无音轨、无 entry.json 四种情况。
 * 这里用的是**真实 box 结构**，所以顺带把 Mp4Sniffer 的嗅探路径也验证了。
 */
class RealDatasetScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `真实数据集四种缓存结构全部正确识别`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())

        assertEquals("应识别出 4 个条目", 4, items.size)

        // ---- 场景 1：新版结构，画面与声音各一路 ----
        val newLayout = items.single { it.title.contains("新版结构") }
        assertEquals("【测试】新版结构 分片分离", newLayout.title)
        assertEquals("P1 新版分片", newLayout.part)
        assertEquals(80, newLayout.quality)
        assertEquals(5000L, newLayout.durationMs)
        assertEquals(listOf("video.m4s"), newLayout.videos.map { it.name })
        assertEquals(listOf("audio.m4s"), newLayout.audios.map { it.name })
        assertTrue("分片应有实际字节", newLayout.sourceBytes > 1000)

        // ---- 场景 2：旧版结构 + 两个清晰度，entry.json 说 64 就该选 64 ----
        val legacy = items.single { it.title.contains("旧版结构") }
        assertEquals(64, legacy.quality)
        assertEquals(listOf("0.m4s"), legacy.videos.map { it.name })
        assertEquals(listOf("1.m4s"), legacy.audios.map { it.name })
        assertTrue(
            "选中的应是 64 那一组，不是体积更大的 80",
            legacy.videos[0].path!!.contains("lua.flv.bili2api.64"),
        )

        // ---- 场景 3：只有画面 ----
        val videoOnly = items.single { it.title.contains("只有画面") }
        assertEquals(1, videoOnly.videos.size)
        assertEquals(0, videoOnly.audios.size)
        assertTrue(!videoOnly.hasAudio)

        // ---- 场景 4：没有 entry.json，文件名也看不出类型，只能靠 moov.hdlr ----
        val sniffed = items.single { it.title.contains("2004") }
        assertTrue("无 entry.json 时标题退化为目录路径", !sniffed.hasMeta)
        assertEquals(listOf("stream_a.m4s"), sniffed.videos.map { it.name })
        assertEquals(listOf("stream_b.m4s"), sniffed.audios.map { it.name })
    }

    @Test
    fun `清理源分片只删除媒体文件_保留元信息与目录`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())
        assertEquals(4, items.size)

        // 模拟 MergeEngine.deleteSources 的行为
        val target = items.single { it.title.contains("新版结构") }
        val before = target.videos.size + target.audios.size
        var removed = 0
        target.videos.forEach { if (storage.delete(it)) removed++ }
        target.audios.forEach { if (storage.delete(it)) removed++ }
        assertEquals("本次用到的分片都应被删掉", before, removed)

        // 分片确实没了
        assertTrue(!File(target.videos[0].path!!).exists())
        assertTrue(!File(target.audios[0].path!!).exists())

        // entry.json / index.json 与目录本身必须留着：清理只针对媒体文件
        val dir = File(target.videos[0].path!!).parentFile!!
        assertTrue("目录不应被删除", dir.isDirectory)
        assertTrue("entry.json 不应被删除", File(dir.parentFile, "entry.json").isFile)
        assertTrue("index.json 不应被删除", File(dir, "index.json").isFile)

        // 其它条目不受影响
        val other = items.single { it.title.contains("旧版结构") }
        assertTrue("别的条目不该被牵连", File(other.videos[0].path!!).exists())
    }

    @Test
    fun `重复删除同一个文件返回 false 而不是抛异常`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())
        val node = items.first { it.videos.isNotEmpty() }.videos.first()

        assertTrue("首次删除应成功", storage.delete(node))
        assertTrue("再次删除应安全返回 false", !storage.delete(node))
    }

    // ------------------------------------------------------------------

    private fun unzipDataset(): File {
        val dir = tmp.newFolder("cache")
        val input = javaClass.classLoader!!.getResourceAsStream("testcache.zip")
            ?: error("测试资源 testcache.zip 不存在")
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val download = File(dir, "download")
        assertTrue("解压后应存在 download 目录", download.isDirectory)
        return download
    }
}
