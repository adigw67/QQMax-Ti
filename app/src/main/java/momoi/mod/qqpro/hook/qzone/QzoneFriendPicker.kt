package momoi.mod.qqpro.hook.qzone
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.graphics.Outline
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.BuddyListCategory
import com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType
import com.tencent.qqnt.kernel.nativeinterface.IBuddyListCallback
import com.tencent.qqnt.msg.KernelServiceUtil
import loadPicUrl
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Self-contained Material 3 friend picker for the QZone compose @-mention. A [MyDialogFragment] shown
 * OVER compose — no NavController navigation (QQ's `startFriendSelect` navigates and tears down the
 * compose dialog, losing the draft). Lists buddies from the kernel ([KernelServiceUtil.a] →
 * `getBuddyListV2`), with a search box, and returns the chosen friend's (uid, uin, nick) via [onPick].
 */
class QzoneFriendPicker(
    private val onPick: ((uid: String, uin: Long, nick: String) -> Unit)? = null,
) : MyDialogFragment() {

    constructor() : this(null)

    private class Friend(val uid: String, val uin: Long, val nick: String)

    private val all = ArrayList<Friend>()
    private var shown = ArrayList<Friend>()
    private var adapter: RecyclerView.Adapter<*>? = null
    private var emptyLabel: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 14.dp else 6.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 8.dp)
        }
        val search = EditText(ctx).apply {
            hint = "搜索好友"
            setTextColor(M3.onSurface); setHintTextColor(M3.hint); textSize = 14f
            background = M3.outlined(M3.outline, M3.radiusMd)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter(s?.toString().orEmpty()) }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emptyLabel = TextView(ctx).apply {
            text = "加载好友中…"; setTextColor(M3.onSurfaceTip); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, 20.dp, 0, 20.dp)
        }
        root.addView(emptyLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setPadding(0, 6.dp, 0, 0); clipToPadding = false
        }
        adapter = ListAdapter()
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        loadBuddies()
        return swipeBackWrap(root)
    }

    private fun loadBuddies() {
        runCatching {
            val svc = KernelServiceUtil.a()
            if (svc == null) { emptyLabel?.text = "无法获取好友列表"; return }
            svc.getBuddyListV2(false, BuddyListReqType.KNOMAL, object : IBuddyListCallback {
                override fun onResult(code: Int, msg: String?, data: java.util.ArrayList<BuddyListCategory>?) {
                    // getBuddyListV2 categories only carry uids; resolve nick + uin via the profile service.
                    val uids = ArrayList<String>()
                    val seen = HashSet<String>()
                    runCatching { data?.forEach { cat -> cat.buddyUids?.forEach { if (seen.add(it)) uids.add(it) } } }
                    val info = runCatching { KernelServiceUtil.d()?.getCoreAndBaseInfo("qzone_at", uids) }.getOrNull()
                    val list = uids.map { uid ->
                        val u = info?.get(uid)
                        val ci = runCatching { u?.coreInfo }.getOrNull()
                        val nick = (ci?.remark?.takeIf { it.isNotBlank() } ?: ci?.nick)?.takeIf { it.isNotBlank() } ?: uid
                        Friend(uid, runCatching { u?.uin ?: 0L }.getOrDefault(0L), nick)
                    }
                    Utils.log("QzoneFriendPicker: ${uids.size} uids, ${list.size} friends, info=${info?.size} (code=$code)")
                    runCatching {
                        activity?.runOnUiThread {
                            all.clear(); all.addAll(list)
                            filter("")
                        }
                    }
                }
            })
        }.onFailure { Utils.log("QzoneFriendPicker load: $it"); emptyLabel?.text = "无法获取好友列表" }
    }

    private fun filter(query: String) {
        val q = query.trim()
        shown = if (q.isEmpty()) ArrayList(all)
        else ArrayList(all.filter { it.nick.contains(q, ignoreCase = true) || it.uin.toString().contains(q) })
        emptyLabel?.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (shown.isEmpty()) emptyLabel?.text = if (all.isEmpty()) "加载好友中…" else "没有匹配的好友"
        adapter?.notifyDataSetChanged()
    }

    private inner class ListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp, 8.dp, 10.dp, 8.dp); isClickable = true
                background = M3.ripple(null)
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            val av = ImageView(ctx).apply {
                id = android.R.id.icon; scaleType = ImageView.ScaleType.CENTER_CROP; maxHeight = 36.dp
                setClipToOutlineCompat(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            row.addView(av, LinearLayout.LayoutParams(34.dp, 34.dp))
            row.addView(TextView(ctx).apply {
                id = android.R.id.text1; setTextColor(M3.onSurface); textSize = 14f; isSingleLine = true
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10.dp })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val f = shown[position]
            val row = holder.itemView as LinearLayout
            val av = row.findViewById<ImageView>(android.R.id.icon)
            val tv = row.findViewById<TextView>(android.R.id.text1)
            tv.text = f.nick
            runCatching { av.loadPicUrl(QzoneActions.avatarUrl(f.uin), "qzbud_${f.uin}") }
            row.setOnClickListener {
                runCatching { onPick?.invoke(f.uid, f.uin, f.nick) }.onFailure { Utils.log("QzoneFriendPicker pick: $it") }
                runCatching { dismiss() }
            }
        }
    }
}
