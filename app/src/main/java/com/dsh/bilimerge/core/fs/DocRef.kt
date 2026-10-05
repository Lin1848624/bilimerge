package com.dsh.bilimerge.core.fs

import android.net.Uri

/**
 * 统一的文件/目录引用。
 *
 * 同一套扫描逻辑要同时服务于两种后端：
 *  - SAF 后端（[uri] 非空）：Android 11+ 上访问用户自选目录的唯一合法途径；
 *  - 真实路径后端（[path] 非空）：拿到"所有文件访问权限"后可直接用 java.io，遍历快一个数量级。
 *
 * 刻意不用 androidx.documentfile 的 DocumentFile：它每取一个属性就发一次 IPC，
 * 在成百上千个文件的缓存目录上会慢到不可用。
 */
class DocRef(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val uri: Uri?,
    val path: String?,
) {
    /** 稳定唯一键，用于去重与缓存 */
    val key: String get() = uri?.toString() ?: path ?: name

    val extension: String
        get() {
            val i = name.lastIndexOf('.')
            return if (i in 0 until name.length - 1) name.substring(i + 1).lowercase() else ""
        }

    fun isMediaFile(): Boolean = !isDir && extension in MEDIA_EXTENSIONS

    override fun toString(): String = "DocRef($name, dir=$isDir, size=$size)"

    companion object {
        /** B 站缓存出现过的媒体容器：m4s 为主，blv/flv 是老版本，mp4/m4a/aac 是手动整理过的 */
        val MEDIA_EXTENSIONS = setOf("m4s", "mp4", "m4a", "aac", "blv", "flv", "mkv", "ts")
    }
}
