package com.dsh.bilimerge.core.fs

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File

/**
 * 目录访问抽象。实现见 [SafStorage] 与 [FileStorage]。
 */
interface Storage {

    /** 展示给用户的路径描述 */
    val label: String

    /** 是否为"极速模式"（真实路径直读，无需 SAF IPC） */
    val isDirect: Boolean

    /** 扫描起点 */
    fun root(): DocRef

    /** 列出直接子项。实现必须批量查询，绝不能逐项发 IPC。 */
    fun children(parent: DocRef): List<DocRef>

    /** 读取文件前 [max] 个字节，用于嗅探容器格式；失败返回 null */
    fun readHead(node: DocRef, max: Int): ByteArray?

    /** 读取整个文本文件（用于 entry.json），失败返回 null */
    fun readText(node: DocRef, maxBytes: Long = 1L shl 20): String?

    /**
     * 生成可直接交给 ffmpeg 的 URL。
     * SAF 后端返回 `saf:N`（由 ffmpeg-kit 的 SAF 协议在进程内按需打开 fd，零拷贝）；
     * 真实路径后端直接返回绝对路径。
     */
    fun ffmpegUrl(node: DocRef, forWrite: Boolean = false): String?

    /**
     * 删除一个文件。
     *
     * SAF 后端要求该目录被授予了写权限，权限不足或 provider 拒绝时返回 false
     * （不会抛异常，调用方据此提示"清理失败"即可）。
     */
    fun delete(node: DocRef): Boolean

    /**
     * 递归删除一个节点（目录会连同其中的所有内容一起删掉）。
     *
     * 必须自己递归：`DocumentsContract.deleteDocument` 与 `File.delete()` 都只能删空目录，
     * 对非空目录直接调用会失败。先清子项、再删自身。
     *
     * @return true 表示该节点最终确实不存在了
     */
    fun deleteTree(node: DocRef): Boolean {
        if (node.isDir) {
            for (child in children(node)) {
                if (child.isDir) {
                    deleteTree(child)
                } else {
                    delete(child)
                }
            }
        }
        return delete(node)
    }
}

// ---------------------------------------------------------------------------
// SAF 后端
// ---------------------------------------------------------------------------

/**
 * 基于 DocumentsContract 的批量查询实现：一次 query 拿到一层目录的全部子项，
 * 相比 DocumentFile.listFiles() 的"每个子项一次 IPC"是数量级的提升。
 */
class SafStorage(
    private val context: Context,
    private val treeUri: Uri,
) : Storage {

    override val label: String
        get() {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                .getOrNull().orEmpty()
            val relative = docId.substringAfter(':', "")
            return when {
                // primary:Download/bili_cache → Download/bili_cache
                relative.isNotEmpty() -> relative
                // 裸 ID（来自「下载」这类提供者）：光看数字根本不知道是什么目录，标一下
                docId.isNotEmpty() -> "文档目录 $docId"
                else -> "/"
            }
        }

    override val isDirect: Boolean = false

    private val resolver get() = context.contentResolver

    override fun root(): DocRef {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = docId.substringAfterLast('/').substringAfter(':').ifEmpty { "根目录" }
        return DocRef(
            name = name,
            isDir = true,
            size = 0L,
            modified = 0L,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            path = null,
        )
    }

    override fun children(parent: DocRef): List<DocRef> {
        val parentUri = parent.uri ?: return emptyList()
        val parentDocId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
            ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        val out = ArrayList<DocRef>(32)
        runCatching {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    out += DocRef(
                        name = name,
                        isDir = isDir,
                        size = size,
                        modified = modified,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        path = null,
                    )
                }
            }
        }
        return out
    }

    override fun readHead(node: DocRef, max: Int): ByteArray? {
        val uri = node.uri ?: return null
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(max)
                var off = 0
                while (off < max) {
                    val n = input.read(buf, off, max - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == 0) null else buf.copyOf(off)
            }
        }.getOrNull()
    }

    override fun readText(node: DocRef, maxBytes: Long): String? {
        val uri = node.uri ?: return null
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(maxBytes.coerceAtMost(4L shl 20).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    override fun ffmpegUrl(node: DocRef, forWrite: Boolean): String? {
        val uri = node.uri ?: return null
        // 用 "rw" 而不是 ffmpeg-kit 自带的 "w"：mp4 复用器写完 moov 后需要回填头部，
        // 只写 fd 在部分 provider 上会失败。优先 rw，provider 不支持时再退回 w。
        if (forWrite) {
            val rw = runCatching { FFmpegKitConfig.getSafParameter(context, uri, "rw") }
                .getOrNull()?.takeIf { it.startsWith("saf:") }
            if (rw != null) return rw
        }
        return runCatching {
            if (forWrite) FFmpegKitConfig.getSafParameterForWrite(context, uri)
            else FFmpegKitConfig.getSafParameterForRead(context, uri)
        }.getOrNull()?.takeIf { it.startsWith("saf:") }
    }

    override fun delete(node: DocRef): Boolean {
        val uri = node.uri ?: return false
        // deleteDocument 在无写权限时抛 SecurityException，这里吞掉并返回 false
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

// ---------------------------------------------------------------------------
// 真实路径后端
// ---------------------------------------------------------------------------

/** 直接走 java.io 的实现，省掉所有 SAF IPC，扫描速度最快 */
class FileStorage(private val rootDir: File) : Storage {

    override val label: String get() = rootDir.absolutePath

    override val isDirect: Boolean = true

    override fun root(): DocRef = DocRef(
        name = rootDir.name,
        isDir = true,
        size = 0L,
        modified = rootDir.lastModified(),
        uri = null,
        path = rootDir.absolutePath,
    )

    override fun children(parent: DocRef): List<DocRef> {
        val dir = File(parent.path ?: return emptyList())
        val list = dir.listFiles() ?: return emptyList()
        val out = ArrayList<DocRef>(list.size)
        for (f in list) {
            out += DocRef(
                name = f.name,
                isDir = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                modified = f.lastModified(),
                uri = null,
                path = f.absolutePath,
            )
        }
        return out
    }

    override fun readHead(node: DocRef, max: Int): ByteArray? {
        val f = File(node.path ?: return null)
        return runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(max)
                var off = 0
                while (off < max) {
                    val n = input.read(buf, off, max - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == 0) null else buf.copyOf(off)
            }
        }.getOrNull()
    }

    override fun readText(node: DocRef, maxBytes: Long): String? {
        val f = File(node.path ?: return null)
        if (!f.isFile) return null
        return runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(f.length().coerceAtMost(maxBytes).coerceAtMost(4L shl 20).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    override fun ffmpegUrl(node: DocRef, forWrite: Boolean): String? = node.path

    override fun delete(node: DocRef): Boolean {
        val f = File(node.path ?: return false)
        // 注意用 exists() 而不是 isFile：deleteTree 递归清空后还要删掉目录本身，
        // 若在这里把目录排除掉，整个 deleteTree 会返回 false（看着像"清理失败"），
        // 而目录其实并没有被删除。File.delete() 本身只能删空目录，这点由调用方保证。
        return runCatching { f.exists() && f.delete() }.getOrDefault(false)
    }
}

// ---------------------------------------------------------------------------
// 工厂
// ---------------------------------------------------------------------------

object StoreFactory {

    /**
     * 目录解析结果。
     *
     * 之所以要把"为什么没走直读"也带出来：授权之后仍显示 SAF 模式，用户是无从判断的——
     * 可能是没授权、可能是目录来自别的文档提供者换算不出路径、也可能是路径读不了，
     * 三种情况的表现一模一样，只能靠这里把原因传到界面上。
     */
    class Resolution(
        val storage: Storage,
        /** 已进入直读模式时是真实路径 */
        val directPath: String?,
        /** 本来可以直读却被挡住时的原因；未授权或直读成功时为 null */
        val blockedReason: String?,
    )

    /** 是否已获得"所有文件访问权限"（能直接读公共目录） */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 把 SAF 树 URI 还原成真实路径。只对 primary 与 SD 卡卷有效。
     *
     * 注意从「下载」等文档提供者选来的目录，documentId 往往是不带卷标的裸数字 ID，
     * 这里必然返回 null——那种情况下无法直读，只能继续走 SAF。
     */
    fun resolveRealPath(treeUri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep < 0) return null
        val volume = docId.substring(0, sep)
        val relative = docId.substring(sep + 1)
        val base = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            // SD 卡卷号形如 1AB2-3CD4
            volume.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) -> "/storage/$volume"
            else -> return null
        }
        val full = if (relative.isEmpty()) base else "$base/$relative"
        return full
    }

    /**
     * 解析目录，并给出没能直读的原因（如果有）。
     *
     * Android 11+ 即使有 MANAGE_EXTERNAL_STORAGE 也读不了 Android/data 下别的应用目录，
     * 这种情况 canRead() 会是 false，于是自动落到 SAF。
     */
    fun resolve(context: Context, treeUri: Uri): Resolution {
        val saf = SafStorage(context, treeUri)

        if (!hasAllFilesAccess(context)) {
            // 没授权就没必要解释"为什么不是极速"，那是用户主动选择的结果
            return Resolution(saf, null, null)
        }

        val real = resolveRealPath(treeUri)
            ?: return Resolution(
                saf, null,
                "已授予所有文件访问权限，但这个目录来自「下载」等文档提供者，换算不出真实路径。" +
                    "想用极速模式，请从「设备存储」逐层进入后重新选择。",
            )

        if (!File(real).canRead()) {
            return Resolution(
                saf, null,
                "已授予所有文件访问权限，但 $real 仍然读不了（Android 11 起 /Android/data 下的" +
                    "其它应用目录即使授权也进不去）。",
            )
        }

        return Resolution(FileStorage(File(real)), real, null)
    }

    /** 只要 storage，不关心原因时的便捷入口 */
    fun create(context: Context, treeUri: Uri, preferDirect: Boolean = true): Storage =
        if (preferDirect) resolve(context, treeUri).storage
        else SafStorage(context, treeUri)

    /** 由绝对路径直接构造（用于内部缓存目录等已知位置） */
    fun direct(path: File): Storage = FileStorage(path)
}
