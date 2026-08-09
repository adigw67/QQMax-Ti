package momoi.mod.qqpro.hook.imageeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Brightness/contrast/saturation, each in -100..100 (0 = unchanged). */
data class Adjust(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0) {
    val isIdentity get() = brightness == 0 && contrast == 0 && saturation == 0
}

/** A single editor annotation drawn in IMAGE coordinates (so geometric ops transform it with the base). */
sealed class Op

/** A freehand polyline. [width] is in image pixels so it scales with the picture on export. */
class StrokeOp(val color: Int, val width: Float, val pts: List<PointF>) : Op()

/** A movable/scalable overlay anchored at its center ([cx],[cy]) with [size] (font/height px). */
sealed class Placed(val cx: Float, val cy: Float, val size: Float) : Op() {
    abstract fun moved(x: Float, y: Float): Placed
    abstract fun scaled(newSize: Float): Placed
}

class TextOp(val text: String, val color: Int, cx: Float, cy: Float, size: Float) : Placed(cx, cy, size) {
    override fun moved(x: Float, y: Float) = TextOp(text, color, x, y, size)
    override fun scaled(newSize: Float) = TextOp(text, color, cx, cy, newSize)
    fun recolored(c: Int) = TextOp(text, c, cx, cy, size)
}

class StickerOp(val emoji: String, cx: Float, cy: Float, size: Float) : Placed(cx, cy, size) {
    override fun moved(x: Float, y: Float) = StickerOp(emoji, x, y, size)
    override fun scaled(newSize: Float) = StickerOp(emoji, cx, cy, newSize)
}

/** An image sticker (favourite/收藏 sticker). [size] is the target height in image px. */
class BitmapStickerOp(val bmp: Bitmap, cx: Float, cy: Float, size: Float) : Placed(cx, cy, size) {
    override fun moved(x: Float, y: Float) = BitmapStickerOp(bmp, x, y, size)
    override fun scaled(newSize: Float) = BitmapStickerOp(bmp, cx, cy, newSize)
}

/** The full editor state at a point in time. Base bitmaps are immutable (geometric ops create new
 *  ones), so a snapshot only copies the ops list + the value objects — cheap enough for an undo stack. */
class EditorDoc(var base: Bitmap, val ops: MutableList<Op> = ArrayList(), var adjust: Adjust = Adjust()) {
    fun snapshot() = EditorDoc(base, ArrayList(ops), adjust)
}

/** VIEW = gestures only (rotate/flip/adjust tools, no single-finger tool action). */
enum class EditMode { VIEW, CROP, DRAW, TEXT, STICKER }

/**
 * The interactive editing surface. Renders the base image (with a live brightness/contrast/saturation
 * color filter) plus all annotation ops, fit-centered and letterboxed. Supports two-finger pinch-zoom
 * and pan on top of the fit; a single tap on empty space resets the zoom (if zoomed) or toggles the
 * chrome via [onTapEmpty]. Touch is routed per [mode]; a bounded snapshot undo/redo stack backs undo.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class EditorCanvasView(ctx: Context, var doc: EditorDoc) : View(ctx) {

    var mode: EditMode = EditMode.VIEW
        set(value) {
            field = value
            selected = null
            cropRect = if (value == EditMode.CROP) RectF(0f, 0f, doc.base.width.toFloat(), doc.base.height.toFloat()) else null
            invalidate()
        }

    var drawColor: Int = 0xFF_F44336.toInt()
    var drawWidth: Float = 8f   // image px

    /** Invoked on a single tap of empty space when NOT zoomed (used to toggle fullscreen chrome). */
    var onTapEmpty: (() -> Unit)? = null
    var onHistoryChanged: (() -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    private val defaultTextSize get() = max(24f, min(doc.base.width, doc.base.height) * 0.09f)
    private val defaultStickerSize get() = max(48f, min(doc.base.width, doc.base.height) * 0.20f)

    private val undoStack = ArrayDeque<EditorDoc>()
    private val redoStack = ArrayDeque<EditorDoc>()
    private val maxHistory = 12
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private val disp = Matrix()        // fit-center (image → view)
    private val userMatrix = Matrix()  // pinch-zoom / pan (view → view)
    private val full = Matrix()        // disp ∘ userMatrix
    private val inv = Matrix()

    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.dp.toFloat() }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99_000000.toInt() }
    private val handleR = 8.dp.toFloat()

    init { isClickable = true }

    // ── history ──────────────────────────────────────────────────────────────────
    private fun pushUndo(snap: EditorDoc) {
        undoStack.addLast(snap)
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        onHistoryChanged?.invoke()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(doc.snapshot()); doc = undoStack.removeLast()
        selected = null; rebuild(); invalidate(); onHistoryChanged?.invoke(); onSelectionChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(doc.snapshot()); doc = redoStack.removeLast()
        selected = null; rebuild(); invalidate(); onHistoryChanged?.invoke(); onSelectionChanged?.invoke()
    }

    // ── geometric ops ─────────────────────────────────────────────────────────────
    fun rotate(cw: Boolean = true) {
        val snap = doc.snapshot()
        val ow = doc.base.width.toFloat(); val oh = doc.base.height.toFloat()
        val m = Matrix()
        if (cw) { m.postRotate(90f); m.postTranslate(oh, 0f) } else { m.postRotate(-90f); m.postTranslate(0f, ow) }
        applyGeometric(m, oh.toInt(), ow.toInt()); pushUndo(snap)
    }

    fun flip(horizontal: Boolean) {
        val snap = doc.snapshot()
        val w = doc.base.width.toFloat(); val h = doc.base.height.toFloat()
        val m = Matrix()
        if (horizontal) { m.postScale(-1f, 1f); m.postTranslate(w, 0f) } else { m.postScale(1f, -1f); m.postTranslate(0f, h) }
        applyGeometric(m, w.toInt(), h.toInt()); pushUndo(snap)
    }

    private fun applyGeometric(m: Matrix, newW: Int, newH: Int) {
        val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        Canvas(nb).apply { concat(m); drawBitmap(doc.base, 0f, 0f, basePaint) }
        val newOps = doc.ops.map { it.transformedBy(m) }.toMutableList()
        doc = EditorDoc(nb, newOps, doc.adjust)
        selected = null; userMatrix.reset(); rebuild(); invalidate(); onSelectionChanged?.invoke()
    }

    private fun Op.transformedBy(m: Matrix): Op = when (this) {
        is StrokeOp -> {
            val pts = FloatArray(this.pts.size * 2)
            this.pts.forEachIndexed { i, p -> pts[i * 2] = p.x; pts[i * 2 + 1] = p.y }
            m.mapPoints(pts)
            StrokeOp(color, width, List(this.pts.size) { PointF(pts[it * 2], pts[it * 2 + 1]) })
        }
        is Placed -> {
            val p = floatArrayOf(cx, cy); m.mapPoints(p)
            when (this) {
                is TextOp -> TextOp(text, color, p[0], p[1], size)
                is StickerOp -> StickerOp(emoji, p[0], p[1], size)
                is BitmapStickerOp -> BitmapStickerOp(bmp, p[0], p[1], size)
            }
        }
    }

    // ── crop ──────────────────────────────────────────────────────────────────────
    private var cropRect: RectF? = null

    fun applyCrop(): Boolean {
        val r = cropRect ?: return false
        val l = r.left.toInt().coerceIn(0, doc.base.width - 1)
        val t = r.top.toInt().coerceIn(0, doc.base.height - 1)
        val w = r.width().toInt().coerceIn(1, doc.base.width - l)
        val h = r.height().toInt().coerceIn(1, doc.base.height - t)
        if (w == doc.base.width && h == doc.base.height) return false
        val snap = doc.snapshot()
        val nb = Bitmap.createBitmap(doc.base, l, t, w, h)
        val mm = Matrix().apply { postTranslate(-l.toFloat(), -t.toFloat()) }
        doc = EditorDoc(nb, doc.ops.map { it.transformedBy(mm) }.toMutableList(), doc.adjust)
        cropRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        selected = null; userMatrix.reset(); rebuild(); invalidate(); pushUndo(snap)
        return true
    }

    // ── annotations ─────────────────────────────────────────────────────────────
    fun addText(text: String, color: Int, size: Float = defaultTextSize) {
        if (text.isBlank()) return
        val snap = doc.snapshot()
        doc.ops.add(TextOp(text, color, doc.base.width / 2f, doc.base.height / 2f, size))
        if (mode != EditMode.TEXT) mode = EditMode.TEXT
        selected = doc.ops.lastIndex
        invalidate(); pushUndo(snap); onSelectionChanged?.invoke()
    }

    fun addSticker(emoji: String) {
        val snap = doc.snapshot()
        doc.ops.add(StickerOp(emoji, doc.base.width / 2f, doc.base.height / 2f, defaultStickerSize))
        selected = doc.ops.lastIndex
        invalidate(); pushUndo(snap); onSelectionChanged?.invoke()
    }

    fun addBitmapSticker(bmp: Bitmap) {
        val snap = doc.snapshot()
        val size = max(64f, min(doc.base.width, doc.base.height) * 0.30f)
        doc.ops.add(BitmapStickerOp(bmp, doc.base.width / 2f, doc.base.height / 2f, size))
        selected = doc.ops.lastIndex
        invalidate(); pushUndo(snap); onSelectionChanged?.invoke()
    }

    /** The currently selected placed op, or null. */
    fun selectedPlaced(): Placed? = selected?.let { doc.ops.getOrNull(it) as? Placed }

    fun recolorSelectedText(color: Int) {
        val idx = selected ?: return
        val op = doc.ops.getOrNull(idx) as? TextOp ?: return
        val snap = doc.snapshot(); doc.ops[idx] = op.recolored(color); invalidate(); pushUndo(snap)
    }

    fun resizeSelected(size: Float) {
        val idx = selected ?: return
        val op = doc.ops.getOrNull(idx) as? Placed ?: return
        val snap = doc.snapshot(); doc.ops[idx] = op.scaled(size); invalidate(); pushUndo(snap)
    }

    fun setAdjust(a: Adjust) { doc.adjust = a; invalidate() }
    fun beginAdjust(): EditorDoc = doc.snapshot()
    fun commitAdjust(snap: EditorDoc) { if (snap.adjust != doc.adjust) pushUndo(snap) }

    // ── layout / matrix ─────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { computeDisp(); rebuild() }

    private fun computeDisp() {
        val bw = doc.base.width.toFloat(); val bh = doc.base.height.toFloat()
        if (bw <= 0 || bh <= 0 || width == 0 || height == 0) return
        val s = min(width / bw, height / bh)
        disp.reset(); disp.postScale(s, s); disp.postTranslate((width - bw * s) / 2f, (height - bh * s) / 2f)
    }

    private fun rebuild() { full.set(disp); full.postConcat(userMatrix); full.invert(inv) }

    private fun viewToImage(x: Float, y: Float): PointF { val p = floatArrayOf(x, y); inv.mapPoints(p); return PointF(p[0], p[1]) }
    private fun imageToView(x: Float, y: Float): PointF { val p = floatArrayOf(x, y); full.mapPoints(p); return PointF(p[0], p[1]) }
    private fun scaleFactor(): Float { val v = floatArrayOf(1f, 0f); full.mapVectors(v); return hypot(v[0], v[1]) }
    private fun isZoomed(): Boolean = !userMatrix.isIdentity

    fun resetZoom() { if (!userMatrix.isIdentity) { userMatrix.reset(); rebuild(); invalidate() } }

    // ── rendering ─────────────────────────────────────────────────────────────────
    private fun colorFilter(a: Adjust): ColorMatrixColorFilter? {
        if (a.isIdentity) return null
        val m = ColorMatrix()
        m.postConcat(ColorMatrix().apply { setSaturation(1f + a.saturation / 100f) })
        val c = 1f + a.contrast / 100f
        val tc = (1f - c) * 128f
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, tc, 0f, c, 0f, 0f, tc, 0f, 0f, c, 0f, tc, 0f, 0f, 0f, 1f, 0f)))
        val b = a.brightness.toFloat()
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        return ColorMatrixColorFilter(m)
    }

    private var loggedDraw = false

    override fun onDraw(canvas: Canvas) {
        if (!loggedDraw) { loggedDraw = true; Utils.log("ImageEditor onDraw: view=${width}x${height} base=${doc.base.width}x${doc.base.height}") }
        canvas.drawColor(M3.surface)
        if (full.isIdentity) { computeDisp(); rebuild() }
        canvas.save(); canvas.concat(full)
        basePaint.colorFilter = colorFilter(doc.adjust)
        canvas.drawBitmap(doc.base, 0f, 0f, basePaint)
        basePaint.colorFilter = null
        for (op in doc.ops) drawOp(canvas, op)
        if (drawing && currentPts.size > 1) drawStrokePath(canvas, drawColor, drawWidth, currentPts)
        canvas.restore()
        drawSelectionUi(canvas)
        drawCropUi(canvas)
    }

    private fun drawOp(canvas: Canvas, op: Op) {
        when (op) {
            is StrokeOp -> drawStrokePath(canvas, op.color, op.width, op.pts)
            is TextOp -> {
                textPaint.color = op.color; textPaint.textSize = op.size
                textPaint.setShadowLayer(op.size * 0.06f, 0f, 0f, 0x88_000000.toInt())
                drawCenteredText(canvas, op.text, op.cx, op.cy); textPaint.clearShadowLayer()
            }
            is StickerOp -> { textPaint.color = 0xFF_FFFFFF.toInt(); textPaint.textSize = op.size; drawCenteredText(canvas, op.emoji, op.cx, op.cy) }
            is BitmapStickerOp -> {
                val s = op.size / op.bmp.height
                canvas.save(); canvas.translate(op.cx, op.cy); canvas.scale(s, s)
                canvas.drawBitmap(op.bmp, -op.bmp.width / 2f, -op.bmp.height / 2f, basePaint); canvas.restore()
            }
        }
    }

    private fun drawStrokePath(canvas: Canvas, color: Int, width: Float, pts: List<PointF>) {
        if (pts.isEmpty()) return
        strokePaint.color = color; strokePaint.strokeWidth = width
        if (pts.size == 1) {
            strokePaint.style = Paint.Style.FILL; canvas.drawCircle(pts[0].x, pts[0].y, width / 2f, strokePaint); strokePaint.style = Paint.Style.STROKE; return
        }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        canvas.drawPath(path, strokePaint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val lines = text.split("\n"); val fm = textPaint.fontMetrics; val lineH = fm.descent - fm.ascent
        var y = cy - lineH * lines.size / 2f - fm.ascent
        for (line in lines) { val w = textPaint.measureText(line); canvas.drawText(line, cx - w / 2f, y, textPaint); y += lineH }
    }

    private fun placedBounds(op: Placed): RectF {
        val w: Float; val h: Float
        when (op) {
            is BitmapStickerOp -> { h = op.size; w = op.bmp.width * (op.size / op.bmp.height) }
            else -> {
                textPaint.textSize = op.size
                val text = when (op) { is TextOp -> op.text; is StickerOp -> op.emoji; is BitmapStickerOp -> "" }
                val lines = text.split("\n"); val fm = textPaint.fontMetrics
                h = (fm.descent - fm.ascent) * lines.size
                w = (lines.maxOfOrNull { textPaint.measureText(it) } ?: op.size).coerceAtLeast(op.size * 0.6f)
            }
        }
        return RectF(op.cx - w / 2f, op.cy - h / 2f, op.cx + w / 2f, op.cy + h / 2f)
    }

    private fun drawSelectionUi(canvas: Canvas) {
        val op = selectedPlaced() ?: return
        val b = placedBounds(op)
        val tl = imageToView(b.left, b.top); val br = imageToView(b.right, b.bottom)
        val pad = 6.dp.toFloat()
        val rect = RectF(min(tl.x, br.x) - pad, min(tl.y, br.y) - pad, max(tl.x, br.x) + pad, max(tl.y, br.y) + pad)
        uiStroke.color = M3.primary
        canvas.drawRoundRect(rect, 8.dp.toFloat(), 8.dp.toFloat(), uiStroke)
        drawHandle(canvas, rect.left, rect.top, M3.error, "×")
        drawHandle(canvas, rect.right, rect.bottom, M3.primary, "⤡")
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, color: Int, glyph: String) {
        uiFill.color = color; canvas.drawCircle(x, y, handleR, uiFill)
        textPaint.color = 0xFF_FFFFFF.toInt(); textPaint.textSize = handleR * 1.3f; textPaint.clearShadowLayer()
        val w = textPaint.measureText(glyph); val fm = textPaint.fontMetrics
        canvas.drawText(glyph, x - w / 2f, y - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawCropUi(canvas: Canvas) {
        val r = cropRect ?: return
        val tl = imageToView(r.left, r.top); val br = imageToView(r.right, r.bottom)
        canvas.drawRect(0f, 0f, width.toFloat(), tl.y, dim)
        canvas.drawRect(0f, br.y, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, tl.y, tl.x, br.y, dim)
        canvas.drawRect(br.x, tl.y, width.toFloat(), br.y, dim)
        uiStroke.color = 0xFF_FFFFFF.toInt(); canvas.drawRect(tl.x, tl.y, br.x, br.y, uiStroke)
        uiStroke.color = 0x66_FFFFFF
        for (i in 1..2) {
            val gx = tl.x + (br.x - tl.x) * i / 3f; val gy = tl.y + (br.y - tl.y) * i / 3f
            canvas.drawLine(gx, tl.y, gx, br.y, uiStroke); canvas.drawLine(tl.x, gy, br.x, gy, uiStroke)
        }
        uiFill.color = M3.primary
        val cr = 5.dp.toFloat()   // smaller crop dots
        for (p in listOf(PointF(tl.x, tl.y), PointF(br.x, tl.y), PointF(tl.x, br.y), PointF(br.x, br.y),
            PointF((tl.x + br.x) / 2f, tl.y), PointF((tl.x + br.x) / 2f, br.y), PointF(tl.x, (tl.y + br.y) / 2f), PointF(br.x, (tl.y + br.y) / 2f)))
            canvas.drawCircle(p.x, p.y, cr, uiFill)
    }

    // ── touch ─────────────────────────────────────────────────────────────────────
    private var selected: Int? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var multi = false          // a 2nd finger touched during this gesture

    // draw
    private var drawing = false
    private val currentPts = ArrayList<PointF>()
    private var drawSnap: EditorDoc? = null

    // overlay drag
    private enum class Grab { NONE, MOVE, RESIZE }
    private var grab = Grab.NONE
    private var overlaySnap: EditorDoc? = null
    private var overlayChanged = false
    private var startDist = 0f; private var startSize = 0f
    private var lastImg = PointF()

    // crop drag
    private var nearL = false; private var nearR = false; private var nearT = false; private var nearB = false

    // pinch/pan
    private var lastFocusX = 0f; private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            userMatrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            // clamp total zoom to [1, 6]
            val vals = FloatArray(9); userMatrix.getValues(vals); val s = vals[Matrix.MSCALE_X]
            if (s < 1f) userMatrix.reset() else if (s > 6f) userMatrix.postScale(6f / s, 6f / s, d.focusX, d.focusY)
            rebuild(); invalidate(); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; moved = false; multi = false
                lastFocusX = e.x; lastFocusY = e.y
                startToolAction(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // second finger: cancel any single-finger tool action, begin pan/zoom
                multi = true
                cancelToolAction()
                lastFocusX = focusX(e); lastFocusY = focusY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val fx = focusX(e); val fy = focusY(e)
                    userMatrix.postTranslate(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx; lastFocusY = fy; rebuild(); invalidate()
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) moved = true
                    if (moved) moveToolAction(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { lastFocusX = focusX(e); lastFocusY = focusY(e) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!multi && !moved) handleTap(e.x, e.y) else if (!multi) endToolAction()
                cancelToolAction()
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    /** A tap on empty space: reset zoom if zoomed, else toggle chrome. In overlay modes a tap can select. */
    private fun handleTap(x: Float, y: Float) {
        if ((mode == EditMode.TEXT || mode == EditMode.STICKER)) {
            val img = viewToImage(x, y)
            val hit = doc.ops.indexOfLast { it is Placed && placedBounds(it).contains(img.x, img.y) }
            if (hit >= 0) { selected = hit; invalidate(); onSelectionChanged?.invoke(); return }
            if (selected != null) { selected = null; invalidate(); onSelectionChanged?.invoke() }
        }
        if (isZoomed()) resetZoom() else onTapEmpty?.invoke()
    }

    private fun startToolAction(e: MotionEvent) {
        when (mode) {
            EditMode.DRAW -> { drawing = true; drawSnap = doc.snapshot(); currentPts.clear(); currentPts.add(viewToImage(e.x, e.y)) }
            EditMode.CROP -> startCrop(e)
            EditMode.TEXT, EditMode.STICKER -> startOverlay(e)
            EditMode.VIEW -> {}
        }
    }

    private fun moveToolAction(e: MotionEvent) {
        when (mode) {
            EditMode.DRAW -> if (drawing) { currentPts.add(viewToImage(e.x, e.y)); invalidate() }
            EditMode.CROP -> moveCrop(e)
            EditMode.TEXT, EditMode.STICKER -> moveOverlay(e)
            EditMode.VIEW -> {}
        }
    }

    private fun endToolAction() {
        when (mode) {
            EditMode.DRAW -> if (drawing) {
                drawing = false
                if (currentPts.size > 1) { doc.ops.add(StrokeOp(drawColor, drawWidth, ArrayList(currentPts))); drawSnap?.let { pushUndo(it) } }
                currentPts.clear(); invalidate()
            }
            EditMode.TEXT, EditMode.STICKER -> if (overlayChanged && (grab == Grab.MOVE || grab == Grab.RESIZE)) overlaySnap?.let { pushUndo(it) }
            else -> {}
        }
    }

    private fun cancelToolAction() {
        if (drawing) { drawing = false; currentPts.clear(); invalidate() }
        grab = Grab.NONE; overlaySnap = null; overlayChanged = false
        nearL = false; nearR = false; nearT = false; nearB = false
    }

    private fun startOverlay(e: MotionEvent) {
        grab = Grab.NONE
        val sel = selectedPlaced()
        if (sel != null) {
            val b = placedBounds(sel); val tl = imageToView(b.left, b.top); val br = imageToView(b.right, b.bottom)
            val pad = 6.dp.toFloat()
            val dl = min(tl.x, br.x) - pad; val dt = min(tl.y, br.y) - pad
            val rr = max(tl.x, br.x) + pad; val rb = max(tl.y, br.y) + pad
            if (hypot(e.x - dl, e.y - dt) <= handleR * 1.8f) {
                val snap = doc.snapshot(); doc.ops.remove(sel); selected = null; invalidate(); pushUndo(snap); onSelectionChanged?.invoke(); return
            }
            if (hypot(e.x - rr, e.y - rb) <= handleR * 1.8f) {
                grab = Grab.RESIZE; overlaySnap = doc.snapshot(); overlayChanged = false
                val c = imageToView(sel.cx, sel.cy); startDist = hypot(e.x - c.x, e.y - c.y).coerceAtLeast(1f); startSize = sel.size; return
            }
        }
        val img = viewToImage(e.x, e.y)
        val hit = doc.ops.indexOfLast { it is Placed && placedBounds(it).contains(img.x, img.y) }
        if (hit >= 0) { selected = hit; grab = Grab.MOVE; overlaySnap = doc.snapshot(); overlayChanged = false; lastImg = img; invalidate(); onSelectionChanged?.invoke() }
    }

    private fun moveOverlay(e: MotionEvent) {
        val idx = selected ?: return
        val op = doc.ops.getOrNull(idx) as? Placed ?: return
        when (grab) {
            Grab.MOVE -> { val img = viewToImage(e.x, e.y); doc.ops[idx] = op.moved(op.cx + (img.x - lastImg.x), op.cy + (img.y - lastImg.y)); lastImg = img; overlayChanged = true; invalidate() }
            Grab.RESIZE -> { val c = imageToView(op.cx, op.cy); val d = hypot(e.x - c.x, e.y - c.y); doc.ops[idx] = op.scaled((startSize * d / startDist).coerceIn(10f, min(doc.base.width, doc.base.height) * 2f)); overlayChanged = true; invalidate() }
            else -> {}
        }
    }

    private fun startCrop(e: MotionEvent) {
        val r = cropRect ?: return; val img = viewToImage(e.x, e.y); val edge = 26f / scaleFactor()
        nearL = abs(img.x - r.left) <= edge; nearR = abs(img.x - r.right) <= edge
        nearT = abs(img.y - r.top) <= edge; nearB = abs(img.y - r.bottom) <= edge
    }

    private fun moveCrop(e: MotionEvent) {
        val r = cropRect ?: return; val img = viewToImage(e.x, e.y); val minSz = 40f
        if (nearL) r.left = img.x.coerceIn(0f, r.right - minSz)
        if (nearR) r.right = img.x.coerceIn(r.left + minSz, doc.base.width.toFloat())
        if (nearT) r.top = img.y.coerceIn(0f, r.bottom - minSz)
        if (nearB) r.bottom = img.y.coerceIn(r.top + minSz, doc.base.height.toFloat())
        invalidate()
    }

    // ── export ─────────────────────────────────────────────────────────────────────
    fun exportBitmap(): Bitmap {
        val out = Bitmap.createBitmap(doc.base.width, doc.base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        basePaint.colorFilter = colorFilter(doc.adjust)
        canvas.drawBitmap(doc.base, 0f, 0f, basePaint); basePaint.colorFilter = null
        for (op in doc.ops) drawOp(canvas, op)
        return out
    }
}
