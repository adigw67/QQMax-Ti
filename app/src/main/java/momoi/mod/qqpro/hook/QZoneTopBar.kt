package momoi.mod.qqpro.hook
import android.os.Build
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import loadPicUrl
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialIconButton
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ

/**
 * Material-style top action bar for the QZone feed page — see [QZoneMainFrameHook].
 *
 * The original feed header (built in QZoneMainFrame.Y()) adds three full-width rows into the
 * adapter's headViewContainer: 发布, 通知, and 我的空间 (with the self avatar). This moves them
 * into a compact three-button bar pinned above the list — the same overlay pattern as [ContactTopBar].
 *
 * Buttons left-to-right:
 *  • Self avatar (round) → tap opens my own QZone space
 *  • Edit icon → tap triggers the original 发布 row click (opens publish selector)
 *  • Bell icon → tap triggers the original 通知 row click (opens notification screen)
 *
 * Click lambdas live here (not in the @Mixin class) to avoid IllegalAccessError.
 */
object QZoneTopBar {
    // Refs to the original header rows; read by QZoneMainFrameHook.bar*().
    var publishRow: View? = null
    var notifyRow: View? = null
    var ownerRow: View? = null

    private var topContainer: LinearLayout? = null
    private var barVisible = true

    fun wrap(host: QZoneMainFrameHook, root: View): View {
        val ctx = root.context

        // Locate and hide the original header rows from the feed adapter's headViewContainer.
        // QZoneMainFrame.o = QZoneFeedAdapter; QZoneFeedAdapter.d = Lazy<FrameLayout> headViewContainer.
        val feedAdapter = host.javaClass.getField("o").get(host)!!
        val headViewLazy = feedAdapter.javaClass.getField("d").get(feedAdapter)!!
        val headFrame = headViewLazy.javaClass.getMethod("getValue").invoke(headViewLazy) as FrameLayout
        val headerLl = headFrame.getChildAt(0) as LinearLayout
        publishRow = headerLl.getChildAt(0)   // item_publish
        notifyRow  = headerLl.getChildAt(1)   // item_notify
        ownerRow   = headerLl.getChildAt(2)   // layout_qzone_item_owner (self avatar + click to my space)
        headerLl.visibility = View.GONE

        barVisible = true
        val bar = buildBar(ctx, host)
        topContainer = bar

        val wrap = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }

        // After the bar is laid out, push the RecyclerView down by the bar height via clipToPadding.
        // QZoneMainFrame.n = SmartRefreshLayout; RecyclerView is its first child.
        bar.post {
            runCatching {
                val srl = host.javaClass.getField("n").get(host) as SmartRefreshLayout
                // Inset the pull-refresh header below the pinned bar (E0 = mHeaderInsetStart, px) so the
                // spinner pulls down from under the bar instead of overlapping it.
                runCatching { srl.E0 = bar.height; srl.requestLayout() }
                    .onFailure { Utils.log("QZoneTopBar: header inset failed: $it") }
                val rv = (0 until srl.childCount).mapNotNull { srl.getChildAt(it) as? RecyclerView }.firstOrNull()
                    ?: run { Utils.log("QZoneTopBar: RecyclerView not found in SmartRefreshLayout"); return@runCatching }
                rv.clipToPadding = false
                rv.setPadding(rv.paddingLeft, bar.height, rv.paddingRight, rv.paddingBottom)
                installHideOnScroll(rv)
                Utils.log("QZoneTopBar: RV paddingTop=${bar.height} headerInset=${bar.height}")
            }.onFailure { Utils.log("QZoneTopBar wrap post: $it") }
        }

        Utils.log("QZoneTopBar: wrapped")
        return wrap
    }

    private fun buildBar(ctx: Context, host: QZoneMainFrameHook): LinearLayout {
        val c = M3.ACCENT

        // Round avatar ImageView — loads self avatar from QQ CDN using current user uin.
        val avatarIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            maxHeight = 30.dp
            setClipToOutlineCompat(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
            post {
                runCatching {
                    val uin = MobileQQ.sMobileQQ?.peekAppRuntime()?.currentUin ?: return@runCatching
                    loadPicUrl("https://thirdqq.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100", "selfavatar_$uin")
                }.onFailure { Utils.log("QZoneTopBar avatar load: $it") }
            }
            setOnClickListener {
                runCatching { host.barMyQzone() }.onFailure { Utils.log("QZoneTopBar avatar click: $it") }
            }
        }

        val editBtn = MaterialIconButton(ctx).apply {
            setIcon(MaterialSymbol(MaterialSymbols.edit, c))
            setTonalContainer()
            setOnClickListener {
                runCatching { host.barPublish() }.onFailure { Utils.log("QZoneTopBar edit click: $it") }
            }
        }

        val notifyBtn = MaterialIconButton(ctx).apply {
            setIcon(MaterialSymbol(MaterialSymbols.notifications, c))
            setTonalContainer()
            setOnClickListener {
                runCatching { host.barNotify() }.onFailure { Utils.log("QZoneTopBar notify click: $it") }
            }
        }

        val spread = Settings.qzoneBarSpread.value
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(8.dp, 6.dp, 8.dp, 4.dp)
            if (spread) {
                // Evenly distribute all three buttons across the full bar width.
                val avatarCell = LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER
                    addView(avatarIv, LinearLayout.LayoutParams(30.dp, 30.dp))
                }
                addView(avatarCell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                for (btn in listOf(editBtn, notifyBtn)) {
                    val cell = LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER
                        clipChildren = false
                        clipToPadding = false
                        addView(btn, LinearLayout.LayoutParams(30.dp, 30.dp))
                    }
                    addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            } else {
                // Group the three buttons tightly in the center (better for round screens).
                addView(avatarIv, LinearLayout.LayoutParams(30.dp, 30.dp))
                addView(editBtn, LinearLayout.LayoutParams(30.dp, 30.dp).apply { marginStart = 10.dp })
                addView(notifyBtn, LinearLayout.LayoutParams(30.dp, 30.dp).apply { marginStart = 10.dp })
            }
        }
    }

    private fun installHideOnScroll(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val top = topContainer ?: return
                if (dy > 6 && barVisible) {
                    barVisible = false
                    top.animate().translationY(-top.height.toFloat()).setDuration(160).start()
                } else if (dy < -6 && !barVisible) {
                    barVisible = true
                    top.animate().translationY(0f).setDuration(160).start()
                }
            }
        })
    }
}
