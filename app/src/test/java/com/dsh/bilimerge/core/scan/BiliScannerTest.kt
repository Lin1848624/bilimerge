package com.dsh.bilimerge.core.scan

import com.dsh.bilimerge.core.fs.FileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 扫描识别逻辑的主机侧测试。
 *
 * BiliScanner 只依赖 Storage 接口，而 FileStorage 是纯 java.io 实现，
 * 因此可以脱离设备、在 JVM 上用真实的临时目录树把识别规则跑一遍。
 */
class BiliScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- 用例

    @Test
    fun `新版结构 entry_json 加 quality 子目录`() {
        val base = tmp.newFolder("download")
        val entryDir = File(base, "12345/67890")
        entryDir.mkdirs()
        File(entryDir, "entry.json").writeText(
            """
            {"title":"测试视频","prefered_video_quality":80,"total_time_milli":125000,
             "total_bytes":2048,"is_completed":true,
             "page_data":{"part":"P1 开场","page":1,"cid":67890}}
            """.trimIndent()
        )
        val q = File(entryDir, "80").apply { mkdirs() }
        File(q, "video.m4s").writeBytes(ByteArray(1024))
        File(q, "audio.m4s").writeBytes(ByteArray(512))
        File(q, "index.json").writeText("{}")

        val items = BiliScanner(FileStorage(base)).scan(FileStorage(base).root())

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("测试视频", item.title)
        assertEquals("P1 开场", item.part)
        assertEquals(80, item.quality)
        assertEquals(125000L, item.durationMs)
        assertEquals(1536L, item.sourceBytes)
        assertEquals(listOf("video.m4s"), item.videos.map { v -> v.name })
        assertEquals(listOf("audio.m4s"), item.audios.map { a -> a.name })
        assertTrue(item.hasMeta)
    }

    @Test
    fun `旧版结构：lua_flv_bili2api 目录 + 0_1_m4s`() {
        val base = tmp.newFolder("dl")
        val entryDir = File(base, "111/222")
        entryDir.mkdirs()
        File(entryDir, "entry.json").writeText(
            """{"title":"老视频","type_tag":"64","page_data":{"part":"P1"}}"""
        )
        for (q in listOf("80", "64")) {
            val d = File(entryDir, "lua.flv.bili2api.$q").apply { mkdirs() }
            val vs = if (q == "80") 4096 else 1024
            File(d, "0.m4s").writeBytes(ByteArray(vs))
            File(d, "1.m4s").writeBytes(ByteArray(256))
        }

        val items = BiliScanner(FileStorage(base)).scan(FileStorage(base).root())

        assertEquals("多个清晰度只应产出一个条目", 1, items.size)
        val item = items[0]
        // entry.json 记录的是 64，应当优先匹配 64 那一组而不是体积更大的 80
        assertEquals(64, item.quality)
        assertEquals(1024L, item.videos[0].size)
        assertEquals("0.m4s", item.videos[0].name)
        assertEquals("1.m4s", item.audios[0].name)
    }

    @Test
    fun `文件名无法判定时回退到读取 moov_hdlr`() {
        val base = tmp.newFolder("dl2")
        val dir = File(base, "abc").apply { mkdirs() }
        File(dir, "x1.m4s").writeBytes(mp4WithHandler("vide"))
        File(dir, "x2.m4s").writeBytes(mp4WithHandler("soun"))

        val items = BiliScanner(FileStorage(base)).scan(FileStorage(base).root())

        assertEquals(1, items.size)
        assertEquals(listOf("x1.m4s"), items[0].videos.map { v -> v.name })
        assertEquals(listOf("x2.m4s"), items[0].audios.map { a -> a.name })
        // 没有 entry.json，标题退化为目录路径
        assertTrue(!items[0].hasMeta)
        assertTrue(items[0].title.contains("abc"))
    }

    @Test
    fun `纯音频缓存也能识别出来`() {
        val base = tmp.newFolder("dl3")
        val entryDir = File(base, "9/8")
        entryDir.mkdirs()
        File(entryDir, "entry.json").writeText("""{"title":"电台","page_data":{"part":""}}""")
        File(entryDir, "audio.m4s").writeBytes(ByteArray(700))

        val items = BiliScanner(FileStorage(base)).scan(FileStorage(base).root())

        assertEquals(1, items.size)
        assertEquals(0, items[0].videos.size)
        assertEquals(1, items[0].audios.size)
        assertTrue(items[0].hasAudio)
    }

    @Test
    fun `没有媒体文件时返回空列表`() {
        val base = tmp.newFolder("empty")
        File(base, "a/b").mkdirs()
        File(base, "a/b/entry.json").writeText("""{"title":"空"}""")

        val items = BiliScanner(FileStorage(base)).scan(FileStorage(base).root())
        assertEquals(0, items.size)
    }

    // ---------------------------------------------------------------- 工具

    /** 手工拼一个只含 ftyp + moov(trak(mdia(hdlr))) 的最小 mp4 头，用来喂嗅探器 */
    private fun mp4WithHandler(handler: String): ByteArray {
        val hdlrPayload = ByteArray(24)
        // 前 8 字节 = version(1) + flags(3) + pre_defined(4)，全 0；
        // 紧接着 4 字节就是 handler_type
        System.arraycopy(handler.toByteArray(Charsets.US_ASCII), 0, hdlrPayload, 8, 4)
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII) + ByteArray(4))
        val moov = box("moov", box("trak", box("mdia", box("hdlr", hdlrPayload))))
        return ftyp + moov
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        System.arraycopy(type.toByteArray(Charsets.US_ASCII), 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }
}
