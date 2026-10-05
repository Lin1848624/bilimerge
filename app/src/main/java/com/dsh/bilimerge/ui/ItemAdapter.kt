package com.dsh.bilimerge.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dsh.bilimerge.R
import com.dsh.bilimerge.core.merge.MergeManager
import com.dsh.bilimerge.core.model.BiliItem
import com.dsh.bilimerge.core.util.Fmt
import com.dsh.bilimerge.databinding.ItemBiliBinding

/**
 * 列表适配器。
 *
 * 刷新策略：外部只调用 [submit]，内部逐项比对，**只通知真正变化的行**。
 * 合并过程中进度每秒更新多次，如果每帧都 notifyDataSetChanged，几百个条目会明显掉帧。
 */
class ItemAdapter(
    private val onToggle: (BiliItem) -> Unit,
    private val onOpenOutput: (MergeManager.Task) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    private data class Row(
        val item: BiliItem,
        val task: MergeManager.Task?,
        val checked: Boolean,
        val status: MergeManager.Status?,
        val progressMilli: Int,
        val message: String,
        val locked: Boolean,
    )

    private var rows: List<Row> = emptyList()
    private var colorSecondary = 0
    private var colorSuccess = 0
    private var colorDanger = 0
    private var colorDisabled = 0

    @SuppressLint("NotifyDataSetChanged")
    fun submit(
        items: List<BiliItem>,
        selected: Set<String>,
        tasks: Map<String, MergeManager.Task>,
        locked: Boolean,
    ) {
        val next = ArrayList<Row>(items.size)
        for (it in items) {
            val t = tasks[it.key]
            next += Row(
                item = it,
                task = t,
                checked = it.key in selected,
                status = t?.status,
                progressMilli = ((t?.progress ?: 0f) * 1000f).toInt(),
                message = t?.message ?: "",
                locked = locked,
            )
        }
        val old = rows
        rows = next
        if (old.size != next.size) {
            // 条目数量变了（重新扫描/清空），此时全量刷新是唯一正确做法：
            // 逐项 notifyItemChanged 无法表达"插入/删除"，反而会错位。
            notifyDataSetChanged()
            return
        }
        for (i in next.indices) {
            if (old[i] != next[i]) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        if (colorSecondary == 0) {
            colorSecondary = ContextCompat.getColor(ctx, R.color.text_secondary)
            colorSuccess = ContextCompat.getColor(ctx, R.color.success)
            colorDanger = ContextCompat.getColor(ctx, R.color.danger)
            colorDisabled = ContextCompat.getColor(ctx, R.color.text_disabled)
        }
        return VH(ItemBiliBinding.inflate(LayoutInflater.from(ctx), parent, false))
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val b = holder.b
        val ctx = b.root.context
        val item = r.item

        b.tvTitle.text = item.displayTitle
        b.tvMeta.text = metaOf(item)
        b.cbSelect.isChecked = r.checked
        // 整个条目可点，勾选框只做展示，避免"点框"和"点行"两套状态打架
        b.cbSelect.isClickable = false
        b.cbSelect.isFocusable = false

        when (r.status) {
            null -> {
                b.pbItem.visibility = View.GONE
                b.tvStatus.visibility = View.GONE
            }
            MergeManager.Status.PENDING -> {
                b.pbItem.visibility = View.GONE
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = ctx.getString(R.string.status_pending)
                b.tvStatus.setTextColor(colorDisabled)
            }
            MergeManager.Status.RUNNING -> {
                b.pbItem.visibility = View.VISIBLE
                b.pbItem.progress = r.progressMilli
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = ctx.getString(R.string.status_running, r.progressMilli / 10)
                b.tvStatus.setTextColor(colorSecondary)
            }
            MergeManager.Status.DONE -> {
                b.pbItem.visibility = View.GONE
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = ctx.getString(
                    R.string.status_done, r.message, r.task?.outputLabel.orEmpty()
                )
                b.tvStatus.setTextColor(colorSuccess)
            }
            MergeManager.Status.FAILED -> {
                b.pbItem.visibility = View.GONE
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = ctx.getString(R.string.status_failed, r.message)
                b.tvStatus.setTextColor(colorDanger)
            }
            MergeManager.Status.CANCELLED -> {
                b.pbItem.visibility = View.GONE
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = ctx.getString(R.string.status_cancelled)
                b.tvStatus.setTextColor(colorDisabled)
            }
        }

        b.root.setOnClickListener {
            val t = r.task
            if (r.status == MergeManager.Status.DONE && t?.outputUri != null) {
                onOpenOutput(t)
            } else if (!r.locked) {
                onToggle(item)
            }
        }
    }

    private fun metaOf(item: BiliItem): String {
        val sb = StringBuilder(48)
        sb.append(item.qualityLabel)
        if (item.durationMs > 0) sb.append(" · ").append(Fmt.duration(item.durationMs))
        if (item.sourceBytes > 0) sb.append(" · ").append(Fmt.size(item.sourceBytes))
        if (!item.hasAudio) sb.append(" · 无音轨")
        return sb.toString()
    }

    class VH(val b: ItemBiliBinding) : RecyclerView.ViewHolder(b.root)
}
