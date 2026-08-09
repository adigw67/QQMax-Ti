package momoi.mod.qqpro.hook.qzone

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.map.geolocation.TencentPoi
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.mobileqq.soso.location.SosoInterfaceOnLocationListener
import com.tencent.mobileqq.soso.location.api.ISosoInterfaceApi
import com.tencent.mobileqq.soso.location.data.SosoLbsInfo
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Self-contained Material 3 location picker for the QZone compose 位置 chip — a [MyDialogFragment]
 * shown over compose (no nav). Mirrors the phone: "使用当前位置" on top, then a list of nearby POIs.
 *
 * Location/POI source = QQ's Soso location service (`QRoute.api(ISosoInterfaceApi).startLocation`,
 * + the synchronous `getSosoInfo()` cache); the POI list is `SosoLbsInfo.f`(SosoLocation)`.x`
 * (List<TencentPoi>). [onPick] returns the chosen place name (or null to clear).
 */
class QzoneLocationPicker(
    private val onPick: ((String?) -> Unit)? = null,
) : MyDialogFragment() {

    constructor() : this(null)

    private val pois = ArrayList<Pair<String, String>>()   // name, address
    private var currentName: String? = null
    private var listColumn: LinearLayout? = null
    private var status: TextView? = null
    private var listener: SosoInterfaceOnLocationListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 14.dp else 6.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 8.dp)
        }
        root.addView(TextView(ctx).apply {
            text = "选择位置"; setTextColor(M3.onSurface); textSize = 15f
            setPadding(2.dp, 2.dp, 2.dp, 6.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(ctx).apply {
            text = "定位中…"; setTextColor(M3.onSurfaceTip); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 8.dp)
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listColumn = column
        val scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(column) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        rebuild()
        loadLocation()
        return swipeBackWrap(root)
    }

    private fun loadLocation() {
        val api = runCatching { QRoute.api(ISosoInterfaceApi::class.java) }.getOrNull()
        if (api == null) { status?.text = "无法获取位置服务"; return }
        runCatching { api.sosoInfo?.let { apply(it) } }   // synchronous cache, if any
        startSoso(api, retry = true)
    }

    /**
     * Mirrors the native check-in flow (QZoneCheckInViewModel.i): the first location request often
     * returns coords WITHOUT a POI list; when that happens we call startLocation a second time and
     * the second pass returns the nearby POIs. [retry] gates that single re-request.
     */
    private fun startSoso(api: ISosoInterfaceApi, retry: Boolean) {
        val l = object : SosoInterfaceOnLocationListener(1, true, true, 3000L, false, false, "pathtrace") {
            override fun b(errCode: Int, info: SosoLbsInfo?) {
                val n = runCatching { info?.f?.x?.size }.getOrNull()
                Utils.log("QzoneLocation: result err=$errCode poi=$n retry=$retry")
                runCatching {
                    activity?.runOnUiThread {
                        val hasPoi = (n ?: 0) > 0
                        if (!hasPoi && errCode == 0 && retry) startSoso(api, retry = false)
                        else apply(info)
                    }
                }
            }
        }
        listener = l
        runCatching { api.startLocation(l) }.onFailure { Utils.log("QzoneLocation start: $it") }
    }

    private fun apply(info: SosoLbsInfo?) {
        val list = runCatching { info?.f?.x }.getOrNull()
        if (list.isNullOrEmpty()) { if (pois.isEmpty()) status?.text = "未找到附近地点"; return }
        pois.clear()
        list.forEach { p: TencentPoi ->
            val name = runCatching { p.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            pois.add(name to (runCatching { p.address }.getOrNull() ?: ""))
        }
        currentName = pois.firstOrNull()?.first
        status?.visibility = if (pois.isEmpty()) View.VISIBLE else View.GONE
        rebuild()
    }

    private fun rebuild() {
        val col = listColumn ?: return
        val ctx = col.context
        col.removeAllViews()
        col.addView(row(ctx, "使用当前位置", currentName ?: "定位中…", accent = true) { pick(currentName) })
        col.addView(row(ctx, "不显示位置", "", accent = false) { pick(null) })
        pois.forEach { (name, addr) -> col.addView(row(ctx, name, addr, accent = false) { pick(name) }) }
    }

    private fun row(ctx: android.content.Context, title: String, sub: String, accent: Boolean, onClick: () -> Unit): View {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp); isClickable = true
            background = M3.ripple(null)
            setOnClickListener { onClick() }
        }
        v.addView(TextView(ctx).apply {
            text = title; setTextColor(if (accent) M3.primary else M3.onSurface); textSize = 14f; isSingleLine = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (sub.isNotBlank()) v.addView(TextView(ctx).apply {
            text = sub; setTextColor(M3.onSurfaceTip); textSize = 11f; isSingleLine = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    private fun pick(name: String?) {
        runCatching { onPick?.invoke(name) }.onFailure { Utils.log("QzoneLocationPicker pick: $it") }
        runCatching { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching { listener?.let { QRoute.api(ISosoInterfaceApi::class.java).removeOnLocationListener(it) } }
    }
}
