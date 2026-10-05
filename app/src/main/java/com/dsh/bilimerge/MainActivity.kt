package com.dsh.bilimerge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.bilimerge.core.fs.Storage
import com.dsh.bilimerge.core.fs.StoreFactory
import com.dsh.bilimerge.core.merge.MergeManager
import com.dsh.bilimerge.core.merge.OutputFormat
import com.dsh.bilimerge.core.merge.OutputTarget
import com.dsh.bilimerge.core.merge.SafTreeOutput
import com.dsh.bilimerge.core.merge.defaultOutputTarget
import com.dsh.bilimerge.core.model.BiliItem
import com.dsh.bilimerge.core.scan.BiliScanner
import com.dsh.bilimerge.core.util.Fmt
import com.dsh.bilimerge.databinding.ActivityMainBinding
import com.dsh.bilimerge.ui.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: ItemAdapter

    private val prefs get() = App.instance.prefs
    private val mergeManager get() = App.instance.mergeManager

    private var storage: Storage? = null
    private var outputTarget: OutputTarget? = null
    private var items: List<BiliItem> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var scanJob: Job? = null
    private var wasRunning = false

    // ------------------------------------------------------------------ 结果回调

    private val pickSourceDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            if (!takePersistable(uri, needWrite = false)) {
                toast(getString(R.string.toast_no_permission))
            }
            prefs.cacheTreeUri = uri.toString()
            applySource(uri, rescan = true)
        }

    private val pickOutputDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            takePersistable(uri, needWrite = true)
            prefs.outputTreeUri = uri.toString()
            prefs.outputLabel = uri.lastPathSegment?.substringAfterLast(':') ?: "自定义目录"
            setupOutput()
        }

    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshFastButton()
        }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyWindowInsets()

        adapter = ItemAdapter(
            onToggle = { toggleSelection(it) },
            onOpenOutput = { openOutput(it) },
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        // 关掉条目动画：合并时进度会高频刷新，动画反而造成闪烁且白耗性能
        b.list.itemAnimator = null
        b.list.setItemViewCacheSize(12)

        bindActions()
        setupOutput()
        restoreSource()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mergeManager.revision.collect { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        refreshFastButton()
        // 用户可能刚从系统设置里授权回来：把当前目录升级成直读模式
        val st = storage
        if (st != null && !st.isDirect && StoreFactory.hasAllFilesAccess(this)) {
            prefs.cacheTreeUri?.let { runCatching { applySource(Uri.parse(it), rescan = false) } }
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------------ 交互绑定

    private fun bindActions() {
        b.btnPickSource.setOnClickListener { pickSourceDir.launch(null) }
        b.btnPickOutput.setOnClickListener { pickOutputDir.launch(null) }
        b.btnRescan.setOnClickListener { doScan() }
        b.btnFast.setOnClickListener { openAllFilesSettings() }
        b.btnHelp.setOnClickListener { showHelp() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnSelectAll.setOnClickListener { selectAll(true) }
        b.btnSelectNone.setOnClickListener { selectAll(false) }
        b.btnStart.setOnClickListener { startMerge() }
        b.btnCancel.setOnClickListener { mergeManager.cancelAll() }
        b.btnResetOutput.setOnClickListener {
            prefs.outputTreeUri = null
            prefs.outputLabel = null
            setupOutput()
        }
    }

    // ------------------------------------------------------------------ 目录

    private fun takePersistable(uri: Uri, needWrite: Boolean): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { contentResolver.takePersistableUriPermission(uri, rw) }.isSuccess) return true
        if (needWrite) return false
        return runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
    }

    private fun restoreSource() {
        val saved = prefs.cacheTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (!hasReadPermission(saved)) {
            prefs.cacheTreeUri = null
            return
        }
        runCatching { applySource(saved, rescan = true) }
            .onFailure { prefs.cacheTreeUri = null }
    }

    private fun hasReadPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun applySource(uri: Uri, rescan: Boolean) {
        val st = StoreFactory.create(this, uri)
        storage = st
        prefs.cacheLabel = st.label

        b.tvSourcePath.text = st.label
        b.badgeMode.visibility = View.VISIBLE
        b.badgeMode.text = getString(if (st.isDirect) R.string.badge_fast else R.string.badge_saf)
        b.btnRescan.visibility = View.VISIBLE
        refreshFastButton()

        if (rescan) doScan()
    }

    private fun refreshFastButton() {
        b.btnFast.visibility = if (StoreFactory.hasAllFilesAccess(this)) View.GONE else View.VISIBLE
    }

    private fun setupOutput() {
        val treeUri = prefs.outputTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val custom = treeUri != null && hasReadPermission(treeUri)
        outputTarget = if (custom) {
            SafTreeOutput(this, treeUri!!, prefs.outputLabel ?: "自定义目录")
        } else {
            prefs.outputTreeUri = null
            defaultOutputTarget(this)
        }
        b.tvOutputPath.text = outputTarget?.label ?: "-"
        b.btnResetOutput.visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }.isSuccess
            if (!ok) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestLegacyStorage.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }

    // ------------------------------------------------------------------ 扫描

    private fun doScan() {
        val st = storage ?: return
        if (mergeManager.isRunning) {
            toast("合并进行中，暂不能重新扫描")
            return
        }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            b.tvScanInfo.visibility = View.VISIBLE
            b.tvScanInfo.text = getString(R.string.scanning, 0)
            b.btnRescan.isEnabled = false
            b.tvEmpty.visibility = View.GONE

            val scanner = BiliScanner(st)
            var lastTick = 0L
            val result = withContext(Dispatchers.IO) {
                scanner.scan(
                    root = st.root(),
                    onProgress = { p ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTick >= 200) {
                            lastTick = now
                            runOnUiThread {
                                b.tvScanInfo.text = getString(R.string.scanning, p.scannedDirs)
                            }
                        }
                    },
                    isCancelled = { !isActive },
                )
            }

            b.btnRescan.isEnabled = true
            items = result
            selected.clear()
            result.forEach { selected += it.key }

            if (result.isEmpty()) {
                b.tvScanInfo.visibility = View.GONE
                b.tvEmpty.visibility = View.VISIBLE
                b.tvEmpty.text = getString(R.string.scan_none)
            } else {
                val total = result.sumOf { maxOf(it.sourceBytes, 0L) }
                b.tvScanInfo.text = getString(R.string.scan_done, result.size, Fmt.size(total))
            }
            render()
        }
    }

    // ------------------------------------------------------------------ 合并

    private fun startMerge() {
        val st = storage ?: return toast(getString(R.string.toast_need_source))
        val target = outputTarget ?: return toast(getString(R.string.toast_need_source))
        val chosen = items.filter { it.key in selected }
        if (chosen.isEmpty()) return toast(getString(R.string.toast_nothing_selected))

        val concurrency = prefs.concurrency.takeIf { it > 0 } ?: defaultConcurrency()
        val started = mergeManager.start(
            items = chosen,
            storage = st,
            output = target,
            concurrency = concurrency,
            fastStart = prefs.fastStart,
            forceStage = prefs.forceStage,
            deleteSource = prefs.deleteSource,
            format = OutputFormat.fromKey(prefs.outputFormat),
        )
        if (started) toast(getString(R.string.toast_merge_started, chosen.size))
    }

    private fun defaultConcurrency(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun toggleSelection(item: BiliItem) {
        if (mergeManager.isRunning) return
        if (!selected.remove(item.key)) selected.add(item.key)
        render()
    }

    private fun selectAll(value: Boolean) {
        if (mergeManager.isRunning) return
        selected.clear()
        if (value) items.forEach { selected += it.key }
        render()
    }

    private fun openOutput(task: MergeManager.Task) {
        val uri = task.outputUri ?: return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { toast("没有找到能播放该视频的应用") }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val tasks = mergeManager.tasks
        val running = mergeManager.isRunning
        val taskMap: Map<String, MergeManager.Task> =
            if (tasks.isEmpty()) emptyMap() else tasks.associateBy { it.key }

        adapter.submit(items, selected, taskMap, running)

        b.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.tvSelection.text = getString(R.string.selection_fmt, selected.size, items.size)

        b.btnStart.isEnabled = !running && selected.isNotEmpty()
        b.btnStart.alpha = if (b.btnStart.isEnabled) 1f else 0.45f
        b.btnSelectAll.alpha = if (running) 0.45f else 1f
        b.btnSelectNone.alpha = if (running) 0.45f else 1f
        b.btnCancel.visibility = if (running) View.VISIBLE else View.GONE

        if (tasks.isNotEmpty()) {
            b.progressPanel.visibility = View.VISIBLE
            val done = mergeManager.finishedCount
            val ok = mergeManager.successCount
            val failed = tasks.count { it.status == MergeManager.Status.FAILED }
            b.progressOverall.progress = (done * 1000) / tasks.size
            b.tvOverall.text = getString(R.string.overall_fmt, done, tasks.size, ok, failed)
            if (wasRunning && !running) {
                toast(getString(R.string.toast_merge_done, ok, failed))
            }
        } else {
            b.progressPanel.visibility = View.GONE
        }
        wasRunning = running
    }

    // ------------------------------------------------------------------ 对话框

    // ------------------------------------------------------------------ 设置面板

    private fun showSettings() {
        val panel = layoutInflater.inflate(R.layout.dialog_settings, null)
        val container = panel.findViewById<LinearLayout>(R.id.settingsContainer)
        val dividerColor = ContextCompat.getColor(this, R.color.divider)

        container.addView(dialogTitle("设置"))

        // ---- 输出 ----
        container.addView(groupTitle("输出"))
        val fmt = OutputFormat.fromKey(prefs.outputFormat)
        val outputCard = card()
        var formatRow: View? = null
        formatRow = valueRow(outputCard, "输出格式", fmt.note, fmt.label) {
            showFormatPicker { picked ->
                // 就地刷新这一行，不必把整个面板重建一遍
                formatRow?.findViewById<TextView>(R.id.tvSettingSubtitle)?.text = picked.note
                formatRow?.findViewById<TextView>(R.id.tvSettingValue)?.text = picked.label
            }
        }
        outputCard.addView(formatRow)
        outputCard.addView(divider(dividerColor))
        outputCard.addView(
            switchRow(outputCard, "添加 faststart", "便于边下边播；仅 MP4 / MOV 有效", prefs.fastStart) {
                prefs.fastStart = it
            }
        )
        container.addView(outputCard)

        // ---- 行为 ----
        container.addView(groupTitle("行为"))
        val behaviorCard = card()
        behaviorCard.addView(
            switchRow(behaviorCard, "兼容模式输出", "直写失败时自动启用；会占用双倍空间", prefs.forceStage) {
                prefs.forceStage = it
            }
        )
        behaviorCard.addView(divider(dividerColor))
        behaviorCard.addView(
            switchRow(behaviorCard, "合并成功后删除缓存目录", "不可恢复；仅在成品确认落盘后执行", prefs.deleteSource) {
                prefs.deleteSource = it
            }
        )
        container.addView(behaviorCard)

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setPositiveButton("完成", null)
            .create()
        dialog.show()
        // AlertDialog 默认是直角白底，换成圆角才和卡片风格一致
        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_dialog))
    }

    private fun showFormatPicker(onPicked: (OutputFormat) -> Unit) {
        val formats = OutputFormat.entries
        val selected = formats.indexOfFirst { it.key == prefs.outputFormat }.coerceAtLeast(0)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = formats.size
            override fun getItem(position: Int): Any = formats[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView
                    ?: layoutInflater.inflate(R.layout.row_format_option, parent, false)
                val f = formats[position]
                v.findViewById<TextView>(R.id.tvFormatLabel).text = f.label
                v.findViewById<TextView>(R.id.tvFormatNote).text = f.note
                val isSelected = position == selected
                v.findViewById<TextView>(R.id.tvFormatCheck).visibility =
                    if (isSelected) View.VISIBLE else View.INVISIBLE
                v.findViewById<LinearLayout>(R.id.formatTextWrap).background =
                    if (isSelected) ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_format_selected)
                    else null
                return v
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("输出格式")
            .setAdapter(adapter) { d, which ->
                val picked = formats[which]
                prefs.outputFormat = picked.key
                d.dismiss()
                onPicked(picked)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.bg_dialog))
    }

    // ---- 设置面板的小零件 ----

    private fun dialogTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
        textSize = 20f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(6), dp(10), dp(6), dp(2))
    }

    private fun groupTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        textSize = 12f
        letterSpacing = 0.08f
        setPadding(dp(6), dp(16), dp(6), dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
        // 让子项的点击反馈被卡片圆角裁切，否则涟漪会溢出到方角
        clipToOutline = true
    }

    private fun divider(color: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(color)
    }

    private fun switchRow(
        parent: ViewGroup,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        // 传真实 parent：root 传 null 时行布局的 match_parent 无法解析成正确的 LayoutParams
        val row = layoutInflater.inflate(R.layout.row_setting_switch, parent, false)
        row.findViewById<TextView>(R.id.tvSettingTitle).text = title
        row.findViewById<TextView>(R.id.tvSettingSubtitle).text = subtitle
        val sw = row.findViewById<SwitchCompat>(R.id.swSetting)
        sw.isChecked = checked
        // 整行响应点击；开关自身不接收点击，避免出现两套状态
        row.setOnClickListener {
            val next = !sw.isChecked
            sw.isChecked = next
            onChange(next)
        }
        return row
    }

    private fun valueRow(
        parent: ViewGroup,
        title: String,
        subtitle: String,
        value: String,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.row_setting_value, parent, false)
        row.findViewById<TextView>(R.id.tvSettingTitle).text = title
        row.findViewById<TextView>(R.id.tvSettingSubtitle).text = subtitle
        row.findViewById<TextView>(R.id.tvSettingValue).text = value
        row.setOnClickListener { onClick() }
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_help_title)
            .setMessage(HELP_TEXT)
            .setPositiveButton(R.string.dialog_help_ok, null)
            .setNeutralButton(R.string.dialog_help_open_settings) { _, _ -> openAllFilesSettings() }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val HELP_TEXT = """
            它能做什么
            把 B 站客户端缓存的音视频分片（m4s）无损合并成一个 mp4。全程 -c copy，不重新编码，
            画质零损失，速度基本等于磁盘拷贝；合并不会删除原缓存。

            为什么需要你自己选目录
            Android 11 起，任何普通应用都进不去 /Android/data/tv.danmaku.bili/ 里的缓存。
            请先用下面任一方式把缓存目录搬到你可以选中的位置：

            1) 免 root：用 MT 管理器等支持 Shizuku 的文件管理器，把
               /storage/emulated/0/Android/data/tv.danmaku.bili/download
               整个复制到 /storage/emulated/0/BiliCache

            2) 用电脑：手机连电脑后执行
               adb pull /sdcard/Android/data/tv.danmaku.bili/download ./bili_cache
               再把 bili_cache 拷回手机的公共目录

            3) 部分 B 站版本支持在「我的 - 设置 - 缓存设置」里自定义缓存位置，
               直接把它指到公共目录即可

            怎么用
            1. 点「选择目录」选中放缓存的目录（例如 /BiliCache/download）
            2. 等扫描完成，列表会列出识别到的每个分P
            3. 勾选要合并的项，点「开始合并」
            4. 成品默认在 Movies/BiliMerge/，点已完成的条目可直接播放

            关于极速模式
            授权「所有文件访问权限」后，扫描会直接读文件系统，比 SAF 快很多。
            对 Android/data 下的目录仍然无效（系统限制），但对你复制出来的目录有效。

            其它
            · 输出格式可在「设置」里切换 MP4 / MKV / MOV / TS。合并始终是无损封装，
              换格式只换外壳、不重新编码，画质和速度都不受影响
            · 只有画面没有声音的缓存也能合并，会自动识别
            · 目录结构从老版 {avid}/{cid}/lua.flv.bili2api.80/0.m4s 到新版
              {avid}/{cid}/{quality}/video.m4s 都支持，不依赖固定布局
            · 合并默认不删除缓存。批量处理怕占空间的话，可在「设置」里打开
              「合并成功后删除整个缓存文件夹」——它只在成品确认落盘后才执行，
              会把该视频的缓存目录连同分片、entry.json、未用到的其它清晰度一并删掉，
              空出来的上级目录也会顺手清掉；你选中的那个目录本身始终保留
        """.trimIndent()
    }
}
