package com.dsh.bilimerge.core.scan

import com.dsh.bilimerge.core.fs.FileStorage
import com.dsh.bilimerge.core.merge.MergeEngine
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
    fun `清理会删除整个条目目录_其它条目不受影响`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())
        assertEquals(4, items.size)

        val target = items.single { it.title.contains("新版结构") }
        val rootDir = target.rootDir
        assertTrue("条目应记录根目录", rootDir != null)
        assertTrue("条目根不应等于扫描根", !target.rootIsScanRoot)

        val dir = File(rootDir!!.path!!)
        assertTrue("清理前条目目录应存在", dir.isDirectory)
        assertTrue("目录里应有 entry.json", File(dir, "entry.json").isFile)

        // 模拟 MergeEngine.cleanupItem 的行为
        assertTrue(storage.deleteTree(rootDir))
        assertTrue("条目目录应被整个删除", !dir.exists())

        // 同一父目录下的另一个条目不该被牵连
        val other = items.single { it.title.contains("旧版结构") }
        assertTrue("别的条目不该被牵连", File(other.rootDir!!.path!!).isDirectory)
    }

    @Test
    fun `条目根就是扫描根时_只清空内容而保留目录本身`() {
        val base = unzipDataset()
        // 直接进到某个分P的缓存目录作为扫描根，此时条目根 == 扫描根
        val scanDir = File(base, "1001/2001")
        val storage = FileStorage(scanDir)
        val items = BiliScanner(storage).scan(storage.root())

        assertEquals(1, items.size)
        assertTrue("根目录即扫描根时该标记必须为真", items[0].rootIsScanRoot)

        val rootDir = items[0].rootDir!!
        val dir = File(rootDir.path!!)
        // 模拟 cleanupItem 对扫描根的分支：逐项清空，保留目录
        storage.children(rootDir).forEach { storage.deleteTree(it) }

        assertTrue("扫描根目录本身必须留着", dir.isDirectory)
        assertEquals("内容应被清空", 0, dir.listFiles()?.size ?: 0)
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

    @Test
    fun `合并结束后中间层空目录会被清掉_扫描根保留`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())
        assertEquals(4, items.size)

        // 模拟每个条目合并成功后的"删除整个条目目录"
        items.forEach { assertTrue("条目目录应能整个删掉", storage.deleteTree(it.rootDir!!)) }

        // 此刻 download/{avid} 这些中间层都成了不含任何内容的空壳
        assertTrue("中间层目录此刻还在", File(root, "1001").isDirectory)
        assertTrue("且确实已经空了", (File(root, "1001").listFiles()?.size ?: 0) == 0)

        // 走真实实现（MergeEngine 不依赖 Android 运行时，可以在 JVM 上直接调用）
        val removed = MergeEngine(storage).pruneEmptyDirs(storage.root())

        assertTrue("应当删掉了若干空目录", removed > 0)
        assertTrue("扫描根本身必须保留", root.isDirectory)
        assertEquals("扫描根下应已清空", 0, root.listFiles()?.size ?: 0)
        assertTrue("中间层空目录应被删除", !File(root, "1001").exists())
        assertTrue("更上一层同样应被删除", !File(root, "1002").exists())
    }

    @Test
    fun `仍有内容的目录不会被误删`() {
        val root = unzipDataset()
        val storage = FileStorage(root)
        val items = BiliScanner(storage).scan(storage.root())

        // 只清理 1001 那一条，1002 原样留着
        val only = items.first { it.dirLabel.contains("1001") }
        assertTrue(storage.deleteTree(only.rootDir!!))

        MergeEngine(storage).pruneEmptyDirs(storage.root())

        assertTrue("空掉的 1001 应被删除", !File(root, "1001").exists())
        assertTrue("还有内容的 1002 必须留着", File(root, "1002/2002/entry.json").isFile)
        assertTrue("1003 同理", File(root, "1003/2003/entry.json").isFile)
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
