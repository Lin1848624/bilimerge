package com.dsh.bilimerge.core.scan

/**
 * 极简 MP4 box 解析器：只做一件事——判断一个 m4s 分片里装的是视频轨还是音频轨。
 *
 * 为什么不靠文件名：B 站各版本客户端的命名从 `0.m4s/1.m4s` 到 `video.m4s/audio.m4s`
 * 变过好几轮，还有用户手动改名的。直接读 moov 里的 hdlr.handler_type 才是可靠依据，
 * 而且只需要文件头几十 KB，代价极低。
 */
object Mp4Sniffer {

    private const val BOX_MOOV = 0x6D6F6F76 // 'moov'
    private const val BOX_TRAK = 0x7472616B // 'trak'
    private const val BOX_MDIA = 0x6D646961 // 'mdia'
    private const val BOX_HDLR = 0x68646C72 // 'hdlr'

    /**
     * @return "vide"（视频）/ "soun"（音频）/ null（无法判定）
     */
    fun sniffHandlerType(head: ByteArray): String? {
        val handler = findHandlerType(head, 0, head.size, 0) ?: return null
        return when (handler) {
            "vide", "soun" -> handler
            else -> null
        }
    }

    private fun findHandlerType(buf: ByteArray, start: Int, end: Int, depth: Int): String? {
        if (depth > 6) return null
        var pos = start
        while (pos + 8 <= end) {
            val size = readU32(buf, pos)
            val type = readU32(buf, pos + 4)
            var headerSize = 8
            // box size 是 32 位无符号数：超过 2GB 的 mdat 会读成负数，必须按无符号还原，
            // 否则会被误判成"非法 box"而提前退出解析
            var boxSize = size.toLong() and 0xFFFFFFFFL
            if (size == 1) {
                if (pos + 16 > end) return null
                boxSize = readU64(buf, pos + 8)
                headerSize = 16
            } else if (size == 0) {
                boxSize = (end - pos).toLong()
            }
            if (boxSize < headerSize) return null
            val boxEnd = pos + boxSize
            if (boxEnd > end) {
                // box 被截断：若是不关心的容器就算了
                return null
            }
            when (type) {
                BOX_HDLR -> {
                    // hdlr: version(1)+flags(3)+pre_defined(4)+handler_type(4)
                    val p = pos + headerSize + 8
                    if (p + 4 <= boxEnd) return String(buf, p, 4, Charsets.US_ASCII)
                }
                BOX_MOOV, BOX_TRAK, BOX_MDIA -> {
                    val r = findHandlerType(buf, pos + headerSize, boxEnd.toInt(), depth + 1)
                    if (r != null) return r
                }
            }
            pos = boxEnd.toInt()
        }
        return null
    }

    private fun readU32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or
            ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or
            (b[i + 3].toInt() and 0xFF)

    private fun readU64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }
}
