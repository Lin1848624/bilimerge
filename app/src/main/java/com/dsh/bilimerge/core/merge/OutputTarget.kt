package com.dsh.bilimerge.core.merge

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.dsh.bilimerge.core.util.FileNames
import java.io.File
import java.io.FileInputStream

/**
 * 一个已经准备好的输出文件。
 *
 * 两种形态：
 *  - 直写（[staged] = false）：[url] 是 `saf:<id>` 或绝对路径，ffmpeg 直接往里写，零拷贝；
 *  - 暂存（[staged] = true）：[url] 是 app 私有目录里的临时文件，合并成功后再流式搬到目标。
 *
 * 之所以要留暂存这条路：`saf:` 协议的 seek 走的是 fd 的 `lseek`，绝大多数 provider 没问题，
 * 但个别 ROM 的 ContentResolver 提供的是不可 seek 的 fd，mp4 复用器回填 moov 时就会失败。
 * 直写失败时自动降级到暂存，代价是多一次拷贝。
 */
class PreparedOutput(
    val url: String,
    val uri: Uri?,
    val fileName: String,
    val label: String,
    val staged: Boolean,
    private val onCommit: () -> Boolean,
    private val onAbort: () -> Unit,
) {
    /** @return 是否成功落到最终位置 */
    fun commit(): Boolean = runCatching { onCommit() }.getOrDefault(false)

    fun abort() = runCatching { onAbort() }
}

interface OutputTarget {
    val label: String

    /**
     * 是否支持"先暂存再搬运"的兼容路径。
     * 目标是真实文件路径时本身就是最终位置，不需要也不应该走暂存。
     */
    val supportsStaging: Boolean

    /**
     * 为一个条目准备输出。
     * @param staged true 表示改走"先暂存再搬运"的兼容路径
     * @return null 表示目标不可用（目录不可写等）
     */
    fun prepare(baseName: String, staged: Boolean = false): PreparedOutput?
}

// ---------------------------------------------------------------------------
// 暂存路径的公共工具
// ---------------------------------------------------------------------------

/**
 * 暂存文件放在 app 私有外部目录：容量比 cacheDir 宽裕得多，且不需要任何权限。
 * 外部存储不可用时退回 cacheDir。
 */
internal fun stageFileFor(context: Context, fileName: String): File {
    val base = context.getExternalFilesDir(null) ?: context.cacheDir
    val dir = File(base, "merge_stage")
    if (!dir.isDirectory) dir.mkdirs()
    val f = File(dir, fileName)
    if (f.exists()) f.delete()
    return f
}

/** 流式把暂存文件搬进目标 URI，成功后删掉暂存文件 */
internal fun moveStagedToTarget(context: Context, src: File, uri: Uri): Boolean {
    var ok = false
    runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            FileInputStream(src).use { input ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
            ok = true
        }
    }
    // 无论成功与否都清掉暂存文件：成功时已完成使命，失败时留着只会白占空间
    runCatching { src.delete() }
    return ok
}

private const val COPY_BUFFER = 1 shl 20 // 1 MB

// ---------------------------------------------------------------------------

/**
 * Android 10+ 的无权限写入路径。
 * 标注 [RequiresApi] 既表达意图，也让 Lint 明白 VOLUME_EXTERNAL_PRIMARY 只在 Q+ 上被求值
 * （Android 9 及以下由 [LegacyFileOutput] 接手）。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreOutput(
    private val context: Context,
    private val relativeDir: String = "Movies/BiliMerge",
) : OutputTarget {

    override val label: String get() = relativeDir

    override val supportsStaging: Boolean = true

    override fun prepare(baseName: String, staged: Boolean): PreparedOutput? = runCatching {
        val resolver = context.contentResolver
        val fileName = FileNames.mp4(baseName)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativeDir)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null

        // 重名时 MediaProvider 会自动加后缀，取回真实文件名用于展示
        val realName = resolver
            .query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: fileName

        val publish: () -> Boolean = {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null,
            )
            true
        }

        if (staged) {
            val tmp = stageFileFor(context, realName)
            PreparedOutput(
                url = tmp.absolutePath,
                uri = uri,
                fileName = realName,
                label = "$relativeDir/$realName",
                staged = true,
                onCommit = {
                    if (moveStagedToTarget(context, tmp, uri)) publish() else false
                },
                onAbort = {
                    tmp.delete()
                    resolver.delete(uri, null, null)
                },
            )
        } else {
            val url = safUrl(context, uri, forWrite = true) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            PreparedOutput(
                url = url,
                uri = uri,
                fileName = realName,
                label = "$relativeDir/$realName",
                staged = false,
                onCommit = publish,
                onAbort = { resolver.delete(uri, null, null) },
            )
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------------

/** 用户自选的 SAF 目录 */
class SafTreeOutput(
    private val context: Context,
    private val treeUri: Uri,
    private val displayLabel: String,
) : OutputTarget {

    override val label: String get() = displayLabel

    override val supportsStaging: Boolean = true

    override fun prepare(baseName: String, staged: Boolean): PreparedOutput? = runCatching {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val fileName = FileNames.mp4(baseName)

        val created = DocumentsContract.createDocument(resolver, parentUri, "video/mp4", fileName)
            ?: return null

        val realName = DocumentsContract.getDocumentId(created)
            .substringAfterLast('/')
            .substringAfterLast(':')

        if (staged) {
            val tmp = stageFileFor(context, realName)
            PreparedOutput(
                url = tmp.absolutePath,
                uri = created,
                fileName = realName,
                label = "$displayLabel/$realName",
                staged = true,
                onCommit = { moveStagedToTarget(context, tmp, created) },
                onAbort = {
                    tmp.delete()
                    runCatching { DocumentsContract.deleteDocument(resolver, created) }
                },
            )
        } else {
            val url = safUrl(context, created, forWrite = true) ?: run {
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                return null
            }
            PreparedOutput(
                url = url,
                uri = created,
                fileName = realName,
                label = "$displayLabel/$realName",
                staged = false,
                onCommit = { true },
                onAbort = { runCatching { DocumentsContract.deleteDocument(resolver, created) } },
            )
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------------

/** Android 9 及以下的直写实现：目标本身就是真实路径，无需暂存 */
class LegacyFileOutput(
    private val dir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        "BiliMerge",
    ),
) : OutputTarget {

    override val label: String get() = dir.absolutePath

    override val supportsStaging: Boolean = false

    override fun prepare(baseName: String, staged: Boolean): PreparedOutput? = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = uniqueFile(dir, FileNames.mp4(baseName))
        PreparedOutput(
            url = file.absolutePath,
            uri = null,
            fileName = file.name,
            label = file.absolutePath,
            staged = false,
            onCommit = { true },
            onAbort = { runCatching { file.delete() } },
        )
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mp4")
        var i = 1
        while (f.exists() && i < 1000) {
            f = File(dir, "$base ($i).$ext")
            i++
        }
        return f
    }
}

// ---------------------------------------------------------------------------

internal fun safUrl(context: Context, uri: Uri, forWrite: Boolean): String? {
    if (forWrite) {
        // mp4 复用器写完后要回填 moov，只写 fd 在部分 provider 上会失败，优先 "rw"
        runCatching { FFmpegKitConfig.getSafParameter(context, uri, "rw") }
            .getOrNull()?.takeIf { it.startsWith("saf:") }?.let { return it }
        runCatching { FFmpegKitConfig.getSafParameterForWrite(context, uri) }
            .getOrNull()?.takeIf { it.startsWith("saf:") }?.let { return it }
        return null
    }
    return runCatching { FFmpegKitConfig.getSafParameterForRead(context, uri) }
        .getOrNull()?.takeIf { it.startsWith("saf:") }
}

/** 按系统版本挑选默认输出目标 */
fun defaultOutputTarget(context: Context): OutputTarget =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreOutput(context)
    } else {
        LegacyFileOutput()
    }
