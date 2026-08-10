package momoi.mod.qqpro.hook.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.ChatMultiSelect
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.aio_cell.AIOCell
import momoi.mod.qqpro.hook.aio_cell.BubbleCorner
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.renderQQFaces
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils

/**
 * 聊天截图 — render the selected chat messages into one tall bitmap and preview it (save / send /
 * cancel). Entered from the long-press menu (single message) and 消息多选 batch ("截图").
 *
 * Approach: realize each selected message's cell OFF-SCREEN via the live RecyclerView adapter
 * (createViewHolder + bindViewHolder — the same path the chat uses, so every cell renders exactly as
 * the user's current settings), stack them in a vertical container (optionally with a decorative
 * titlebar + input bar), measure/lay-out at the chat width with NO height constraint (the whole point —
 * the watch screen is too small), then draw to a Bitmap. Options: render self on the left
 * (screenshotSelfAsOther) and anonymize nick/avatar to A/B/C ([LetterAvatar], screenshotShowIdentity).
 */
object ChatScreenshot {

    fun capture(host: View, msgIds: List<Long>) {
        runCatching { doCapture(host, msgIds) }
            .onFailure { Utils.log("ChatScreenshot.capture failed: $it"); Utils.toast(host.context, "截图失败") }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        var p = view.parent
        while (p != null) { if (p is RecyclerView) return p; p = (p as? View)?.parent }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun doCapture(host: View, msgIds: List<Long>) {
        val ctx = host.context
        if (msgIds.isEmpty()) { Utils.toast(ctx, "未选择消息"); return }
        // The long-press menu host is an overlay (not in the list), so walking up finds no RecyclerView.
        // Use the live chat RV captured by ChatMultiSelect on every bind; fall back to walking up.
        val rv = ChatMultiSelect.recyclerView ?: findRecyclerView(host)
            ?: run { Utils.toast(ctx, "截图失败(无列表)"); return }
        val adapter = rv.adapter as? RecyclerView.Adapter<RecyclerView.ViewHolder>
            ?: run { Utils.toast(ctx, "截图失败(无适配器)"); return }
        val live = CurrentMsgList.uiOp?.m() ?: run { Utils.toast(ctx, "截图失败(无消息)"); return }

        // Resolve selected msgIds → adapter positions; render in chronological (position) order.
        val idSet = msgIds.toHashSet()
        val positions = ArrayList<Int>()
        for (i in live.indices) {
            val it = live[i] as? WatchAIOMsgItem ?: continue
            if (it.d.msgId in idSet) positions.add(i)
        }
        if (positions.isEmpty()) { Utils.toast(ctx, "截图失败(消息不在列表)"); return }

        val width = rv.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        if (!Settings.screenshotShowIdentity.value) LetterAvatar.reset()
        val selfAsOther = Settings.screenshotSelfAsOther.value
        val realSelf = SelfContact.peerUid

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
        }
        if (Settings.screenshotTitlebar.value) container.addView(buildTitlebar(ctx), LinearLayout.LayoutParams(FILL, WRAP))

        // Header-merge relative to the SELECTED set (first rendered always shows its header).
        val ordered = positions.mapNotNull { live[it] as? WatchAIOMsgItem }
        val prevMap = HashMap<WatchAIOMsgItem, WatchAIOMsgItem?>()
        ordered.forEachIndexed { i, m -> prevMap[m] = if (i > 0) ordered[i - 1] else null }
        CurrentMsgList.prevOverride = { prevMap[it] }
        // Render own messages as a third party: spoof SelfContact so our hooks (avatar side, nick
        // gravity, @self highlight) treat self as another sender during the bind. The bubble side/color
        // (driven by the NATIVE locationType, which uses QQ's own self) is flipped in postProcess.
        if (selfAsOther) SelfContact.peerUid = ""

        var rendered = 0
        try {
            for (pos in positions) {
                val item = live[pos] as? WatchAIOMsgItem ?: continue
                runCatching {
                    val type = adapter.getItemViewType(pos)
                    val holder = adapter.createViewHolder(rv, type)
                    adapter.bindViewHolder(holder, pos)
                    val cell = holder.itemView
                    (cell.parent as? ViewGroup)?.removeView(cell)
                    postProcess(cell, item, realSelf, selfAsOther)
                    container.addView(cell, LinearLayout.LayoutParams(FILL, WRAP))
                    rendered++
                }.onFailure { Utils.log("ChatScreenshot: cell pos=$pos failed: $it") }
            }
        } finally {
            CurrentMsgList.prevOverride = null
            if (selfAsOther) SelfContact.peerUid = realSelf
        }
        if (rendered == 0) { Utils.toast(ctx, "截图失败(无可渲染消息)"); return }

        if (Settings.screenshotInputBar.value) container.addView(buildInputBar(ctx), LinearLayout.LayoutParams(FILL, WRAP))
        if (Settings.screenshotWatermark.value) container.addView(buildWatermark(ctx), LinearLayout.LayoutParams(FILL, WRAP))

        Utils.toast(ctx, "生成截图…")
        layoutContainer(container, width)
        // Attach the container to the window (INVISIBLE) during the settle delay so:
        //  • detached-view post() callbacks drain — the reply quote resolves its real sender name via
        //    getSingleMsg + view.post{}, which never fires while detached (showed the raw uid);
        //  • link-preview / avatar / image async loads run and re-layout naturally.
        // The final bitmap still comes from our own full-height measure (the window would clamp it to
        // the screen). Detach + draw after the delay. Falls back to a plain detached capture.
        val contentView = findActivity(ctx)?.findViewById<ViewGroup>(android.R.id.content)
        if (contentView != null) runCatching {
            container.visibility = View.INVISIBLE
            contentView.addView(container, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }.onFailure { Utils.log("ChatScreenshot: attach failed: $it") }
        ThreadManager.runOnUiThread({
            runCatching {
                // Direct View.draw() renders regardless of the container's own INVISIBLE flag (its
                // VISIBLE children are still drawn), so no need to flip it back (avoids a flash).
                layoutContainer(container, width)
                val bmp = drawToBitmap(container, width)
                ScreenshotPreview.show(ctx, bmp, rv)
            }.onFailure { Utils.log("ChatScreenshot draw failed: $it"); Utils.toast(ctx, "截图失败") }
            runCatching { contentView?.removeView(container) }
        }, 900)
    }

    private fun findActivity(ctx: Context): android.app.Activity? {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
        return null
    }

    private fun layoutContainer(c: View, width: Int) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        c.measure(wSpec, hSpec)
        c.layout(0, 0, width, c.measuredHeight)
    }

    private fun drawToBitmap(c: View, width: Int): Bitmap {
        val h = c.measuredHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(M3.surface)
        c.draw(canvas)
        return bmp
    }

    /** Apply the per-message options (self-on-left, anonymize) to a freshly-bound cell. */
    private fun postProcess(cell: View, item: WatchAIOMsgItem, realSelf: String, selfAsOther: Boolean) {
        val widget = cell as? AIOCellGroupWidget ?: return
        if (selfAsOther && item.d.senderUid == realSelf) runCatching {
            // The avatar side / nick gravity / @self highlight were already rendered as "other" during
            // the spoofed bind. Here flip the bubble to the LEFT (onLayout positions by locationType)
            // and re-theme it + its text as the other side (forceColor: normal apply short-circuits).
            widget.setLocation(0)
            BubbleCorner.forceColor(widget, 0)
            // Re-color the WHOLE bubble wrapper, not just the content TextView — the reply quote and
            // link-preview live in it too and were colored for the self side during the bind (the nick
            // sits OUTSIDE the wrapper, so it keeps its themed onSurface color).
            val styled = runCatching { widget.getLongClickWrapper<View>() }.getOrNull()
                ?: runCatching { widget.getContentWidget<View>() }.getOrNull()
            AIOCell.applyMsgTextStyle(styled, 0)
        }.onFailure { Utils.log("ChatScreenshot self-as-other failed: $it") }
        if (!Settings.screenshotShowIdentity.value) runCatching { anonymize(widget, item.d.senderUid) }
            .onFailure { Utils.log("ChatScreenshot anonymize failed: $it") }
    }

    /** Replace the sender's nick text + avatar (a compound drawable on the nick view) with a letter. */
    private fun anonymize(widget: AIOCellGroupWidget, uid: String) {
        val nick = widget.getNickWidget<TextView>() ?: return
        val cds = nick.compoundDrawables // [left, top, right, bottom]
        val side = when { cds[0] != null -> 0; cds[2] != null -> 2; else -> -1 }
        if (side >= 0) {
            val old = cds[side]!!
            val w = old.bounds.width().takeIf { it > 0 } ?: old.intrinsicWidth.takeIf { it > 0 } ?: (nick.lineHeight * 2)
            val h = old.bounds.height().takeIf { it > 0 } ?: old.intrinsicHeight.takeIf { it > 0 } ?: w
            val d = LetterAvatar.drawableFor(uid).apply { setBounds(0, 0, w, h) }
            val n = arrayOfNulls<Drawable>(4); n[side] = d
            nick.setCompoundDrawables(n[0], n[1], n[2], n[3])
        }
        if (nick.visibility == View.VISIBLE && !nick.text.isNullOrEmpty()) nick.text = LetterAvatar.letterFor(uid)
    }

    /** Decorative titlebar: just the group/contact name (no rounded corner, no unread badge). */
    private fun buildTitlebar(ctx: Context): View {
        val name = runCatching {
            val rc = RecentContacts.get(CurrentContact.peerUid)
            (rc?.raw?.remark?.takeIf { it.isNotEmpty() } ?: rc?.raw?.peerName)?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: "聊天记录"
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(M3.surfaceContainer)
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            addView(TextView(ctx).apply {
                text = renderQQFaces(name, 14)
                setTextColor(M3.onSurface)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    /** Decorative empty input bar (just a pill with the hint text). */
    private fun buildInputBar(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }
        val pill = TextView(ctx).apply {
            text = "说点什么..."
            setTextColor(M3.hint)
            textSize = 14f
            setPadding(14.dp, 9.dp, 14.dp, 9.dp)
            background = M3.filledOutlined(M3.surfaceContainer, M3.outline, M3.radiusMd)
        }
        row.addView(pill, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    /** "由 QQMax-Ti 生成" watermark line at the very bottom. */
    private fun buildWatermark(ctx: Context): View = TextView(ctx).apply {
        text = "由 QQMax-Ti 生成"
        setTextColor(M3.onSurfaceVariant)
        textSize = 10f
        gravity = Gravity.CENTER
        setBackgroundColor(M3.surface)
        setPadding(0, 6.dp, 0, 8.dp)
    }
}
