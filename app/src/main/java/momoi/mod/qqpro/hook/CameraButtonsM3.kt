package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.tencent.qqnt.watch.camera.CameraFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils

/**
 * Materialize the in-app photo camera ([CameraFragment]) confirm bar. After a shot the page shows
 * 重拍 (id `cancel`) and 确定 (id `confirm`) in a `button_container` LinearLayout. Rather than recolor
 * the native MaterialButtons (which keep their non-M3 icons / corner shape), replace them with real
 * pill-shaped [M3Button]s (重拍 tonal + camera symbol, 确定 filled + check symbol) that delegate their
 * clicks back to the now-hidden native buttons. Gated by the redesign toggle.
 */
@Mixin
class CameraButtonsM3 : CameraFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.useM3Settings.value) materializeCameraButtons(view)
    }
}

private const val CAM_TAG = "qqpro_m3_cam"

/** Top-level so the @Mixin body stays free of helper closures/anonymous classes. */
fun materializeCameraButtons(view: View) {
    runCatching {
        val ctx = view.context
        val res = view.resources
        val pkg = ctx.packageName
        fun byId(n: String): View? {
            val id = res.getIdentifier(n, "id", pkg)
            return if (id != 0) view.findViewById(id) else null
        }
        val container = byId("button_container") as? ViewGroup ?: return
        if (container.findViewWithTag<View>(CAM_TAG) != null) return // idempotent

        val cancel = byId("cancel")   // 重拍
        val confirm = byId("confirm") // 确定

        fun textOf(v: View?, fallback: String) =
            (v as? Button)?.text?.toString()?.takeIf { it.isNotBlank() } ?: fallback

        fun m3(label: String, variant: M3Button.Variant, native: View?): M3Button {
            // Text-only: on a small round screen an icon + label clips inside the weighted button.
            return M3Button(ctx).variant(variant).apply {
                tag = CAM_TAG
                text = label
                setOnClickListener { native?.performClick() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6.dp; marginEnd = 6.dp
                }
            }
        }

        cancel?.let {
            it.visibility = View.GONE
            container.addView(m3(textOf(it, "重拍"), M3Button.Variant.TONAL, it))
        }
        confirm?.let {
            it.visibility = View.GONE
            container.addView(m3(textOf(it, "确定"), M3Button.Variant.FILLED, it))
        }
        Utils.log("CameraButtonsM3: confirm bar replaced with M3 buttons")
    }.onFailure { Utils.log("CameraButtonsM3 failed: $it") }
}
