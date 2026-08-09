package momoi.mod.qqpro.hook.style
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.watch.selftab.ui.SelfSetBirthFragment
import com.tencent.qqnt.watch.selftab.ui.SelfSetGenderFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3ListItem
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Materialize the two self-profile selector pages — 性别 ([SelfSetGenderFragment]) and 生日
 * ([SelfSetBirthFragment]) — to match the rest of the M3 app, gated by [Settings.useM3Settings].
 *
 * Strategy (same harvest-and-delegate as [M3SettingsRedesign]): let the native fragment build its
 * tree in super.Y() (which wires the real save listeners on the option layouts / confirm button +
 * date-picker), then rebuild an M3 surface over it that REUSES those native interactive views — the
 * gender option layouts via performClick(), and the live WatchDatePicker + its confirm button — so
 * the kernel save path is completely unchanged. W() returns true so WatchFragment skips its native
 * gradient bgView and our M3 surface covers cleanly.
 *
 * Top-level build funcs (not in the @Mixin body) so their lambdas don't get copied into the target
 * package (anonymous-in-mixin crashes); same pattern as NotifyAndAddMaterial.
 */

private fun View.byId(name: String): View? {
    val id = resources.getIdentifier(name, "id", context.packageName)
    return if (id != 0) findViewById(id) else null
}

private fun divider(ctx: Context): View = View(ctx).apply {
    setBackgroundColor(M3.outlineVariant)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = 12.dp; marginEnd = 12.dp
    }
}

private fun titleText(ctx: Context, text: CharSequence): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 16f
    setTextColor(M3.onSurface)
    gravity = Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = 4.dp }
}

private fun tipsText(ctx: Context, text: CharSequence?): TextView? {
    val t = text?.toString()?.trim().orEmpty()
    if (t.isEmpty()) return null
    return TextView(ctx).apply {
        this.text = t
        textSize = 11f
        setTextColor(M3.onSurfaceVariant)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 14.dp }
    }
}

/** A rounded surface-container card that clips its child rows' ripples to the rounded corners. */
private fun listCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
    setClipToOutlineCompat(true)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

@Mixin
class SelfSetGenderM3 : SelfSetGenderFragment() {
    override fun W(): Boolean = Settings.useM3Settings.value || super.W()

    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val native = super.Y(p0, p1, p2)!!
        if (!Settings.useM3Settings.value) return native
        return runCatching { buildGenderM3(native as ViewGroup) }
            .onFailure { Utils.log("SelfSetGenderM3: $it") }
            .getOrDefault(native)
    }
}

private fun buildGenderM3(native: ViewGroup): View {
    val ctx = native.context
    val male = native.byId("gender_male_layout")
    val female = native.byId("gender_female_layout")
    val tips = (native.byId("tv_gender_tips") as? TextView)?.text

    fun genderRow(label: String, drawableName: String, onTap: View?): M3ListItem {
        val item = M3ListItem(ctx).title(label)
        val drawId = ctx.resources.getIdentifier(drawableName, "drawable", ctx.packageName)
        if (drawId != 0) item.leading(ImageView(ctx).apply { setImageResource(drawId) }, 28)
        else item.leading(ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.person, M3.primary))
        }, 28)
        item.setOnClickListener { onTap?.performClick() }
        return item
    }

    val card = listCard(ctx).apply {
        addView(genderRow("男", "male", male))
        addView(divider(ctx))
        addView(genderRow("女", "female", female))
    }

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp, 26.dp, 16.dp, 26.dp)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(titleText(ctx, "选择性别"))
        tipsText(ctx, tips)?.let { addView(it) }
        addView(card)
    }
    val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(column)
    }

    native.visibility = View.GONE
    Utils.log("SelfSetGenderM3: gender selector rebuilt")
    return FrameLayout(ctx).apply {
        setBackgroundColor(M3.surface)
        addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll)
    }
}

@Mixin
class SelfSetBirthM3 : SelfSetBirthFragment() {
    override fun W(): Boolean = Settings.useM3Settings.value || super.W()

    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val native = super.Y(p0, p1, p2)!!
        if (!Settings.useM3Settings.value) return native
        return runCatching { buildBirthM3(native as ViewGroup) }
            .onFailure { Utils.log("SelfSetBirthM3: $it") }
            .getOrDefault(native)
    }
}

/**
 * Recolor the native date wheel (WatchDatePicker → 3 [android.widget.NumberPicker]) for the current
 * theme: the EditText (center value), the wheel paint and the selection divider are natively white,
 * so they vanish on a light M3 card in light mode. Reflects on NumberPicker's framework fields
 * (mSelectorWheelPaint / mSelectionDivider — stable AOSP names, not obfuscated).
 */
private fun styleDateWheel(picker: View) {
    fun walk(v: View) {
        if (v is android.widget.NumberPicker) {
            for (i in 0 until v.childCount) (v.getChildAt(i) as? android.widget.EditText)?.setTextColor(M3.onSurface)
            runCatching {
                val f = android.widget.NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint").apply { isAccessible = true }
                (f.get(v) as? android.graphics.Paint)?.color = M3.onSurface
            }
            runCatching {
                android.widget.NumberPicker::class.java.getDeclaredField("mSelectionDivider").apply { isAccessible = true }
                    .set(v, android.graphics.drawable.ColorDrawable(M3.onSurfaceVariant))
            }
            v.invalidate()
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(picker)
}

private fun buildBirthM3(native: ViewGroup): View {
    val ctx = native.context
    val picker = native.byId("date_picker") ?: return native
    val confirm = native.byId("confirm")
    val tips = (native.byId("tv_birth_tips") as? TextView)?.text

    // Reparent the live WatchDatePicker into our M3 card. Its confirm button (kept in the hidden
    // native tree) reads the picker via a field reference, so getCurDate() + save still work.
    (picker.parent as? ViewGroup)?.removeView(picker)
    // Inside the ScrollView below, let the date wheel keep its own vertical drag (don't let the
    // ScrollView steal it).
    picker.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
    styleDateWheel(picker)

    val card = listCard(ctx).apply {
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        gravity = Gravity.CENTER_HORIZONTAL
        // WRAP (not MATCH) so the wheel keeps its natural width and the centering gravity actually
        // applies — at MATCH_PARENT the 3 NumberPickers sit left-packed inside a full-width picker.
        addView(picker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }
    // Shrink the wheel to half its natural height (its full height isn't known until laid out).
    picker.post {
        val h = picker.height
        if (h > 0) {
            picker.layoutParams = picker.layoutParams.apply { height = h / 2 }
            picker.requestLayout()
        }
        styleDateWheel(picker)  // re-apply after layout (the wheel paint is set lazily)
    }

    val confirmBtn = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        text = "确定"
        setOnClickListener { confirm?.performClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp)
            .apply { topMargin = 18.dp }
    }

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(16.dp, 22.dp, 16.dp, 22.dp)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(titleText(ctx, "选择生日"))
        tipsText(ctx, tips)?.let { addView(it) }
        addView(card)
        addView(confirmBtn)
    }
    // Scrollable so the confirm button is reachable when the wheel makes the page taller than the
    // watch screen. fillViewport keeps short content filling the screen.
    val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(column)
    }

    native.visibility = View.GONE
    Utils.log("SelfSetBirthM3: birthday selector rebuilt (scrollable)")
    return FrameLayout(ctx).apply {
        setBackgroundColor(M3.surface)
        addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll)
    }
}
