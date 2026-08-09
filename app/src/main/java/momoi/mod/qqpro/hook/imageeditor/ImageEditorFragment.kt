package momoi.mod.qqpro.hook.imageeditor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.CustomEmotionData
import download
import momoi.mod.qqpro.hook.sticker.StickerStore
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.MAX_DECODE_BYTES
import momoi.mod.qqpro.lib.SAFE_MAX_DIM
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3Slider
import momoi.mod.qqpro.lib.material.MaterialColors
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.showColorPicker
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** WATCH = the compact 2-level single-row menu (this device). PHONE = the always-visible tiered
 *  toolbar (kept for a future phone build; temporarily unavailable). */
enum class DeviceMode { WATCH, PHONE }

/**
 * Full-screen Material-3 image editor (rotate / flip / crop / draw / text / sticker / adjust), built
 * with the project's Kotlin DSL / M3 tokens. Shown as a dialog; never recreated by the system, so the
 * source [src] and the [onDone]/[onCancel] callbacks are passed via the constructor. On done it
 * composites every edit into a JPEG in the app cache and hands the [File] back.
 */
@SuppressLint("ValidFragment")
class ImageEditorFragment(
    private val src: File = File(""),
    private val onDone: (File) -> Unit = {},
    private val onCancel: () -> Unit = {},
) : MyDialogFragment() {

    // Watch is the only supported layout for now; phone code is retained for later.
    private val deviceMode = DeviceMode.WATCH

    private lateinit var canvas: EditorCanvasView
    private lateinit var root: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: LinearLayout
    private var adjustOverlay: View? = null
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View
    private var busy = false
    private var chromeHidden = false

    private var textColor = 0xFF_FFFFFF.toInt()
    private var textSize = 48f
    private var favStickers: List<CustomEmotionData> = emptyList()

    private val palette = listOf(
        "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#00BCD4",
        "#2196F3", "#3F51B5", "#9C27B0", "#FFFFFF", "#000000",
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val bmp = runCatching { loadBitmap(src) }.getOrNull()
        if (bmp == null) {
            Utils.toast(ctx, "无法打开图片")
            post { dismissAllowingStateLoss() }
            return View(ctx)
        }
        canvas = EditorCanvasView(ctx, EditorDoc(bmp))
        textSize = max(24f, min(bmp.width, bmp.height) * 0.09f)
        canvas.onTapEmpty = { toggleChrome() }
        canvas.onHistoryChanged = { refreshHistoryButtons() }

        root = FrameLayout(ctx).apply { background = ColorDrawable(M3.surface) }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        topBar = buildTopBar(requireActivity())
        content.addView(topBar, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 40.dp))
        content.addView(canvas, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainer)
                cornerRadii = floatArrayOf(18.dp.toFloat(), 18.dp.toFloat(), 18.dp.toFloat(), 18.dp.toFloat(), 0f, 0f, 0f, 0f)
            }
            // Generous side + bottom insets so the row (incl. the ← back button) stays inside the round
            // screen's usable chord and out of the cut corners.
            setPadding(22.dp, 6.dp, 22.dp, 14.dp)
        }
        content.addView(bottomBar, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        showRootMenu()
        refreshHistoryButtons()
        StickerStore.loadFav { favStickers = it }   // preload favourites for the sticker tool
        post { maybeShowTutorial() }
        root.post { Utils.log("ImageEditor sizes: top=${topBar.height} bottom=${bottomBar.height} canvas=${canvas.height} root=${root.height}") }
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) { cancelWithConfirm(); true } else false
        }
    }

    // ── top bar ────────────────────────────────────────────────────────────────────
    private fun buildTopBar(ctx: Activity): LinearLayout {
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10.dp, 2.dp, 10.dp, 2.dp) }
        bar.addView(navIcon(ctx, MaterialSymbols.close, tint = M3.onSurface) { cancelWithConfirm() })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        undoBtn = navIcon(ctx, MaterialSymbols.undo, tint = M3.onSurface) { canvas.undo() }
        redoBtn = navIcon(ctx, MaterialSymbols.redo, tint = M3.onSurface) { canvas.redo() }
        bar.addView(undoBtn); bar.addView(space(ctx, 4)); bar.addView(redoBtn)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(navIcon(ctx, MaterialSymbols.check, tint = M3.onColor(M3.primary), filled = true) { done() })
        return bar
    }

    private fun refreshHistoryButtons() {
        if (::undoBtn.isInitialized) undoBtn.alpha = if (canvas.canUndo) 1f else 0.3f
        if (::redoBtn.isInitialized) redoBtn.alpha = if (canvas.canRedo) 1f else 0.3f
    }

    private var adjustActive = false

    /** First-run tutorial overlay (shown once) — highlights tap-to-fullscreen and the two-finger zoom. */
    private fun maybeShowTutorial() {
        val sp = momoi.mod.qqpro.Settings.sp
        if (sp.getBoolean("imageEditorTutorialSeen", false)) return
        val ctx = requireActivity()
        val scrim = FrameLayout(ctx).apply { background = ColorDrawable(0xE0_000000.toInt()); isClickable = true }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22.dp, 20.dp, 22.dp, 16.dp)
            background = GradientDrawable().apply { setColor(M3.surfaceContainerHigh); cornerRadius = 22.dp.toFloat() }
        }
        card.addView(TextView(ctx).apply { text = "图片编辑器"; textSize = 16f; setTextColor(M3.onSurface); gravity = Gravity.CENTER; setPadding(0, 0, 0, 12.dp) })
        val tips = listOf(
            "· 底部选择工具，点 ← 返回上级",
            "· 单指点击图片：全屏预览，再点恢复",
            "· 双指缩放 / 拖动图片，单指点一下复位",
            "· ✓ 保存并发送，✕ 取消",
        )
        for (t in tips) card.addView(TextView(ctx).apply { text = t; textSize = 12f; setTextColor(M3.onSurfaceVariant); setPadding(0, 4.dp, 0, 4.dp) })
        card.addView(M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
            text = "知道了"; setOnClickListener { sp.edit().putBoolean("imageEditorTutorialSeen", true).apply(); root.removeView(scrim) }
        }, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })
        val scroll = android.widget.ScrollView(ctx).apply { addView(card) }
        scrim.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = 22.dp; rightMargin = 22.dp })
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun setChrome(visible: Boolean) {
        chromeHidden = !visible
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v; bottomBar.visibility = v
    }

    private fun toggleChrome() { if (adjustActive) return; setChrome(chromeHidden) }

    // ── level 1: root tool menu ──────────────────────────────────────────────────────
    private fun showRootMenu() {
        canvas.mode = EditMode.VIEW
        hideAdjustOverlay()
        bottomBar.removeAllViews()
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(toolBtn(MaterialSymbols.rotate_right, "变换") { showTransform() })
        row.addView(toolBtn(MaterialSymbols.crop, "裁剪") { showCrop() })
        row.addView(toolBtn(MaterialSymbols.brush, "涂鸦") { showDraw() })
        row.addView(toolBtn(MaterialSymbols.text_fields, "文字") { showText() })
        row.addView(toolBtn(MaterialSymbols.add_reaction, "贴纸") { showSticker() })
        row.addView(toolBtn(MaterialSymbols.tune, "调整") { showAdjust() })
        bottomBar.addView(hscroll(row), LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ── level 2: per-tool rows ────────────────────────────────────────────────────────
    private fun subRow(vararg views: View) {
        hideAdjustOverlay()
        bottomBar.removeAllViews()
        for (v in views) bottomBar.addView(v)
    }

    /** A tool submenu: a fixed ← back on the left + the options in a horizontal scroll (so wide
     *  option rows like 变换/调整 never clip or overflow the round screen). */
    private fun toolRow(vararg options: View) {
        hideAdjustOverlay()
        bottomBar.removeAllViews()
        bottomBar.addView(back())
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (o in options) row.addView(o)
        bottomBar.addView(hscroll(row), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun showTransform() {
        canvas.mode = EditMode.VIEW
        toolRow(
            toolBtn(MaterialSymbols.rotate_right, "旋转90°") { canvas.rotate(true) },
            toolBtn(MaterialSymbols.flip, "水平翻转") { canvas.flip(true) },
            toolBtn(MaterialSymbols.flip_camera_android, "垂直翻转") { canvas.flip(false) })
    }

    private fun showCrop() {
        canvas.mode = EditMode.CROP
        val a = requireActivity()
        subRow(
            navIcon(a, MaterialSymbols.close, tint = M3.onSurface) { showRootMenu() },
            View(a).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) },
            labelChip("拖动边框裁剪"),
            View(a).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) },
            navIcon(a, MaterialSymbols.check, tint = M3.onColor(M3.primary), filled = true) { canvas.applyCrop(); showRootMenu() },
        )
    }

    private fun showDraw() {
        canvas.mode = EditMode.DRAW
        toolRow(
            colorBtn(canvas.drawColor) { chooseColor(canvas.drawColor) { canvas.drawColor = it; showDraw() } },
            toolBtn(MaterialSymbols.tune, "粗细") { promptSize("画笔粗细", canvas.drawWidth, 1f, 40f) { canvas.drawWidth = it } })
    }

    private fun showText() {
        canvas.mode = EditMode.TEXT
        toolRow(
            colorBtn(textColor) { chooseColor(textColor) { textColor = it; canvas.recolorSelectedText(it); showText() } },
            toolBtn(MaterialSymbols.tune, "大小") {
                val cur = canvas.selectedPlaced()?.size ?: textSize
                promptSize("文字大小", cur, 12f, min(canvas.doc.base.width, canvas.doc.base.height).toFloat()) { textSize = it; canvas.resizeSelected(it) }
            },
            toolBtn(MaterialSymbols.add, "添加") { promptText(requireActivity()) { canvas.addText(it, textColor, textSize) } })
    }

    private fun showSticker() {
        canvas.mode = EditMode.STICKER
        val ctx = requireContext()
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (emoji in EmojiStickers.LIST) strip.addView(TextView(ctx).apply {
            text = emoji; textSize = 21f; setPadding(7.dp, 4.dp, 7.dp, 4.dp); isClickable = true
            setOnClickListener { canvas.addSticker(emoji) }
        })
        for (fav in favStickers) strip.addView(favThumb(fav))
        subRow(back(), hscroll(strip).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
    }

    private fun showAdjust() {
        canvas.mode = EditMode.VIEW
        toolRow(
            toolBtn(MaterialSymbols.brightness_6, "亮度") { showAdjustOverlay(AdjustParam.BRIGHTNESS) },
            toolBtn(MaterialSymbols.contrast, "对比度") { showAdjustOverlay(AdjustParam.CONTRAST) },
            toolBtn(MaterialSymbols.format_color_reset, "饱和度") { showAdjustOverlay(AdjustParam.SATURATION) })
    }

    private fun back(): View = navIcon(requireActivity(), MaterialSymbols.arrow_back, tint = M3.onSurface) { showRootMenu() }

    // ── adjust overlay (floating slider over the image) ──────────────────────────────
    private enum class AdjustParam { BRIGHTNESS, CONTRAST, SATURATION }

    private fun showAdjustOverlay(param: AdjustParam) {
        hideAdjustOverlay()
        val ctx = requireActivity()
        // fullscreen while adjusting: hide the chrome so the whole image previews the effect.
        setChrome(false); adjustActive = true
        val snap = canvas.beginAdjust()
        val original = canvas.doc.adjust
        fun finish() { hideAdjustOverlay(); adjustActive = false; setChrome(true); showAdjust() }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 10.dp)
            background = GradientDrawable().apply { setColor(0xE6_000000.toInt()); cornerRadius = 24.dp.toFloat() }
        }
        // header row: ✕ · label · ✓
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(navIcon(ctx, MaterialSymbols.close, tint = 0xFF_FFFFFF.toInt()) { canvas.setAdjust(original); finish() })
        header.addView(TextView(ctx).apply {
            text = when (param) { AdjustParam.BRIGHTNESS -> "亮度"; AdjustParam.CONTRAST -> "对比度"; AdjustParam.SATURATION -> "饱和度" }
            textSize = 12f; setTextColor(0xFF_FFFFFF.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(navIcon(ctx, MaterialSymbols.check, tint = M3.onColor(M3.primary), filled = true) { canvas.commitAdjust(snap); finish() })
        bar.addView(header, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // slider on its own full-width line
        bar.addView(M3Slider(ctx).apply {
            max = 200
            progress = 100 + when (param) {
                AdjustParam.BRIGHTNESS -> canvas.doc.adjust.brightness
                AdjustParam.CONTRAST -> canvas.doc.adjust.contrast
                AdjustParam.SATURATION -> canvas.doc.adjust.saturation
            }
            onProgressChanged = { p, _ ->
                val value = p - 100; val a = canvas.doc.adjust
                canvas.setAdjust(when (param) {
                    AdjustParam.BRIGHTNESS -> a.copy(brightness = value)
                    AdjustParam.CONTRAST -> a.copy(contrast = value)
                    AdjustParam.SATURATION -> a.copy(saturation = value)
                })
            }
        }, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })

        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = 12.dp; rightMargin = 12.dp; bottomMargin = 28.dp
        }
        root.addView(bar, lp)
        adjustOverlay = bar
    }

    private fun hideAdjustOverlay() { adjustOverlay?.let { root.removeView(it) }; adjustOverlay = null }

    // ── favourite sticker thumbnail + resolution ─────────────────────────────────────
    private fun favThumb(fav: CustomEmotionData): View = ImageView(requireContext()).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginStart = 4.dp; marginEnd = 4.dp }
        val path = fav.emoPath
        if (!path.isNullOrEmpty() && File(path).exists()) runCatching { bitmapDecodeFile(File(path)) }
        else fav.thumbPath?.takeIf { it.isNotEmpty() }?.let { loadThumb(this, it) } ?: fav.url?.let { loadThumb(this, it) }
        isClickable = true
        setOnClickListener { addFavSticker(fav) }
    }

    private fun loadThumb(iv: ImageView, url: String) {
        val cache = File(cacheDir(), "fav_thumb_${url.hashCode()}")
        if (cache.exists() && cache.length() > 0) { runCatching { iv.bitmapDecodeFile(cache) }; return }
        download(url, cache) { ok -> if (ok) post { runCatching { iv.bitmapDecodeFile(cache) } } }
    }

    /** Resolve a favourite sticker to a full bitmap, then drop it on the canvas. */
    private fun addFavSticker(fav: CustomEmotionData) {
        val local = fav.emoPath?.takeIf { it.isNotEmpty() && File(it).exists() }
        if (local != null) { decodeStickerAsync(File(local)) { it?.let { b -> canvas.addBitmapSticker(b) } }; return }
        val url = fav.url?.takeIf { it.isNotEmpty() } ?: fav.thumbPath
        if (url.isNullOrEmpty()) { Utils.toast(requireContext(), "贴纸不可用"); return }
        val cache = File(cacheDir(), "fav_${url.hashCode()}")
        if (cache.exists() && cache.length() > 0) { decodeStickerAsync(cache) { it?.let { b -> canvas.addBitmapSticker(b) } }; return }
        download(url, cache) { ok -> post { if (ok) decodeStickerAsync(cache) { it?.let { b -> canvas.addBitmapSticker(b) } } else Utils.toast(requireContext(), "贴纸下载失败") } }
    }

    private fun decodeStickerAsync(file: File, onDone: (Bitmap?) -> Unit) {
        Thread {
            val b = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 512) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            }.getOrNull()
            post { onDone(b) }
        }.start()
    }

    // ── done / cancel ────────────────────────────────────────────────────────────────
    private fun done() {
        if (busy) return
        busy = true
        val bmp = canvas.exportBitmap()
        Thread {
            val out = File(cacheDir(), "qqpro_edit_${System.currentTimeMillis()}.jpg")
            val ok = runCatching { FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.isSuccess && out.length() > 0
            post { busy = false; if (ok) { onDone(out); dismissAllowingStateLoss() } else Utils.toast(requireContext(), "导出失败") }
        }.start()
    }

    private fun cancel() { onCancel(); dismissAllowingStateLoss() }

    private fun hasEdits(): Boolean = canvas.canUndo || canvas.doc.ops.isNotEmpty() || !canvas.doc.adjust.isIdentity

    /** Exit with a confirm dialog when there are unsaved edits (avoids accidental data loss). */
    private fun cancelWithConfirm() {
        if (!hasEdits()) { cancel(); return }
        val activity = requireActivity()
        val dialog = Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20.dp, 18.dp, 20.dp, 14.dp)
            background = GradientDrawable().apply { setColor(M3.surfaceContainerHigh); cornerRadius = 22.dp.toFloat() }
        }
        panel.addView(TextView(activity).apply { text = "放弃编辑?"; textSize = 15f; setTextColor(M3.onSurface); gravity = Gravity.CENTER; setPadding(0, 0, 0, 6.dp) })
        panel.addView(TextView(activity).apply { text = "所有修改将不会保存"; textSize = 12f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER; setPadding(0, 0, 0, 12.dp) })
        panel.addView(M3Button(activity).variant(M3Button.Variant.ERROR).apply { text = "放弃"; setOnClickListener { dialog.dismiss(); cancel() } },
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(M3Button(activity).variant(M3Button.Variant.TEXT).apply { text = "继续编辑"; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        dialog.setContentView(panel)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout((activity.resources.displayMetrics.widthPixels * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT) }
        dialog.show()
    }

    // Route the watch's swipe/gesture back and the hardware back key through the exit confirm.
    override fun f() { cancelWithConfirm() }
    override fun p() { cancelWithConfirm() }

    // ── widgets ─────────────────────────────────────────────────────────────────────
    private fun cacheDir(): File = requireContext().externalCacheDir ?: requireContext().cacheDir

    private fun navIcon(ctx: Activity, symbol: String, tint: Int, filled: Boolean = false, onClick: () -> Unit): View =
        ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(symbol, tint)); setPadding(7.dp, 7.dp, 7.dp, 7.dp)
            background = if (filled) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.primary) }
            else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.surfaceContainerHigh) }
            layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginStart = 3.dp; marginEnd = 3.dp }
            isClickable = true; setOnClickListener { onClick() }
        }

    private fun toolBtn(symbol: String, label: String, iconTint: Int = M3.onSurface, onClick: () -> Unit): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(8.dp, 2.dp, 8.dp, 2.dp); isClickable = true; setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageDrawable(MaterialSymbol(symbol, iconTint)); setPadding(7.dp, 7.dp, 7.dp, 7.dp)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.surfaceContainerHigh) }
                layoutParams = LinearLayout.LayoutParams(34.dp, 34.dp)
            })
            addView(TextView(ctx).apply { text = label; textSize = 9f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER; setPadding(0, 2.dp, 0, 0) })
        }
    }

    /** A tool button whose icon is tinted with the current colour (so it reads as the colour picker). */
    private fun colorBtn(color: Int, onClick: () -> Unit): LinearLayout = toolBtn(MaterialSymbols.palette, "颜色", iconTint = color, onClick = onClick)

    private fun labelChip(text: String): TextView = TextView(requireContext()).apply {
        this.text = text; textSize = 11f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER
    }

    private fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(requireContext()).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(child)
    }

    private fun space(ctx: Activity, w: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(w.dp, 1) }

    private fun chooseColor(current: Int, onPick: (Int) -> Unit) {
        showColorPicker(requireActivity(), "选择颜色", "", current, false, MaterialColors.ACCENT) { hex -> M3.parseColorOrNull(hex)?.let { onPick(it) } }
    }

    @SuppressLint("SetTextI18n")
    private fun promptText(activity: Activity, onOk: (String) -> Unit) {
        val dialog = Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(18.dp, 18.dp, 18.dp, 12.dp)
            background = GradientDrawable().apply { setColor(M3.surfaceContainerHigh); cornerRadius = 22.dp.toFloat() }
        }
        panel.addView(TextView(activity).apply { text = "输入文字"; textSize = 15f; setTextColor(M3.onSurface); gravity = Gravity.CENTER; setPadding(0, 0, 0, 10.dp) })
        val field = EditText(activity).apply {
            setTextColor(M3.onSurface); setHintTextColor(M3.hint); hint = "文字内容"
            gravity = Gravity.TOP; maxLines = 4; setHorizontallyScrolling(false)   // cap growth so the dialog stays short
            background = GradientDrawable().apply { setColor(M3.surfaceContainer); cornerRadius = 10.dp.toFloat() }; setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }
        panel.addView(field, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(M3Button(activity).variant(M3Button.Variant.FILLED).apply { text = "确定"; setOnClickListener { onOk(field.text.toString()); dialog.dismiss() } },
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })
        panel.addView(M3Button(activity).variant(M3Button.Variant.TEXT).apply { text = "取消"; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(android.widget.ScrollView(activity).apply { addView(panel) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (activity.resources.displayMetrics.widthPixels * 0.86f).toInt()
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
            panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            setLayout(w, if (panel.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    @SuppressLint("SetTextI18n")
    private fun promptSize(title: String, current: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
        val activity = requireActivity()
        val dialog = Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20.dp, 18.dp, 20.dp, 14.dp)
            background = GradientDrawable().apply { setColor(M3.surfaceContainerHigh); cornerRadius = 22.dp.toFloat() }
        }
        panel.addView(TextView(activity).apply { text = title; textSize = 15f; setTextColor(M3.onSurface); gravity = Gravity.CENTER; setPadding(0, 0, 0, 12.dp) })
        val range = (max - min).coerceAtLeast(1f)
        val slider = M3Slider(activity).apply {
            this.max = 100; progress = (((current - min) / range) * 100).toInt().coerceIn(0, 100)
            onProgressChanged = { p, _ -> onChange(min + range * p / 100f) }
        }
        panel.addView(slider, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(M3Button(activity).variant(M3Button.Variant.FILLED).apply { text = "完成"; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })
        dialog.setContentView(panel)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout((activity.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT) }
        dialog.show()
    }

    private fun post(r: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post(r) }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    // ── image loading (downsampled + EXIF-corrected) ────────────────────────────────
    private fun loadBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) * 4 > MAX_DECODE_BYTES) sample *= 2
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > SAFE_MAX_DIM) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val ori = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        Utils.log("ImageEditor loadBitmap: ${file.path} src=${bounds.outWidth}x${bounds.outHeight} sample=$sample -> ${bmp.width}x${bmp.height} exif=$ori")
        return bmp
    }
}
