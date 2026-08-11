package momoi.mod.qqpro.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.Utils

/**
 * 聊天背景裁剪页（实验性）：把选中的图片强制裁到聊天背景比例（屏幕宽高比），
 * 拖动/双指缩放调整位置，确定后保存为该会话（或全局）背景。莫奈取色不在此处自动执行——
 * 需要取色时走设置页「用图片取色（莫奈）」单独上传图片。
 */
class CropBackgroundActivity : Activity() {
    companion object {
        const val EXTRA_URI = "bg_uri"
        const val EXTRA_PEER = "bg_peer"
    }

    private var srcBitmap: Bitmap? = null
    private var peerUid: String? = null
    private lateinit var imageView: PanZoomImageView
    private lateinit var overlay: CropOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peerUid = intent.getStringExtra(EXTRA_PEER)
        val uri: Uri? = intent.getParcelableExtra(EXTRA_URI)
        if (uri == null) { toast("没有图片"); finish(); return }
        srcBitmap = decodeSampled(uri)
        if (srcBitmap == null) { toast("图片解码失败"); finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF_000000.toInt()) }
        imageView = PanZoomImageView(this)
        root.addView(imageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = CropOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp, 10.dp, 16.dp, 14.dp)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
        }
        buttons.addView(M3Button(this).variant(M3Button.Variant.TEXT).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(M3Button(this).variant(M3Button.Variant.FILLED).apply {
            text = "确定"
            setOnClickListener { confirmCrop() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)

        imageView.post { imageView.setBitmap(srcBitmap!!) }
    }

    private fun confirmCrop() {
        val bmp = srcBitmap ?: return
        val imgRect = imageView.displayedRect()
        val frame = overlay.frameRect()
        if (imgRect.isEmpty || frame.isEmpty) { toast("无法裁剪"); return }
        // 视图坐标 → 位图像素坐标
        val sx = bmp.width / imgRect.width()
        val sy = bmp.height / imgRect.height()
        val left = ((frame.left - imgRect.left) * sx).toInt().coerceIn(0, bmp.width)
        val top = ((frame.top - imgRect.top) * sy).toInt().coerceIn(0, bmp.height)
        val w = (frame.width() * sx).toInt().coerceIn(1, bmp.width - left)
        val h = (frame.height() * sy).toInt().coerceIn(1, bmp.height - top)
        val crop = Bitmap.createBitmap(bmp, left, top, w, h)
        if (!ChatBackground.saveCropped(crop, peerUid)) { toast("保存失败"); return }
        toast("已设置背景")
        crop.recycle()
        setResult(RESULT_OK)
        finish()
    }

    private fun decodeSampled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        Utils.log("CropBackground decode failed: $e")
        null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
}

/** 可拖动/双指缩放的图片视图（MATRIX 缩放），初始适配屏幕居中。 */
private class PanZoomImageView(ctx: Context) : ImageView(ctx) {
    private val matrix = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var mode = 0
    private var dist = 0f

    init {
        scaleType = ScaleType.MATRIX
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { mode = 1; lastX = ev.x; lastY = ev.y }
                MotionEvent.ACTION_POINTER_DOWN -> { mode = 2; dist = spacing(ev) }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) {
                        matrix.postTranslate(ev.x - lastX, ev.y - lastY)
                        lastX = ev.x; lastY = ev.y
                        imageMatrix = matrix
                    } else if (mode == 2 && ev.pointerCount >= 2) {
                        val d = spacing(ev)
                        if (d > 10f) {
                            matrix.postScale(d / dist, d / dist, midX(ev), midY(ev))
                            dist = d
                            imageMatrix = matrix
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            true
        }
    }

    fun setBitmap(bmp: Bitmap) {
        setImageBitmap(bmp)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val s = minOf(vw / bmp.width, vh / bmp.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
        imageMatrix = matrix
    }

    fun displayedRect(): RectF {
        val d = drawable ?: return RectF()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imageMatrix.mapRect(r)
        return r
    }

    private fun spacing(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midX(ev: MotionEvent): Float = (ev.getX(0) + ev.getX(1)) / 2f
    private fun midY(ev: MotionEvent): Float = (ev.getY(0) + ev.getY(1)) / 2f
}

/** 裁剪遮罩：四周变暗 + 中央比例框 + 九宫格线。比例 = 屏幕宽高比。 */
private class CropOverlayView(ctx: Context) : View(ctx) {
    private val mask = Paint().apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }
    private val border = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val grid = Paint().apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val frame = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val ratio = w.toFloat() / h.toFloat()
        val m = 12f * resources.displayMetrics.density
        var fw = w - m * 2
        var fh = fw / ratio
        if (fh > h - m * 2 - 60f * resources.displayMetrics.density) {
            fh = h - m * 2 - 60f * resources.displayMetrics.density
            fw = fh * ratio
        }
        frame.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
    }

    fun frameRect(): RectF = RectF(frame)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // 四边遮罩
        canvas.drawRect(0f, 0f, w, frame.top, mask)
        canvas.drawRect(0f, frame.bottom, w, h, mask)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, mask)
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, mask)
        // 边框 + 九宫格
        canvas.drawRect(frame, border)
        for (i in 1..2) {
            val x = frame.left + frame.width() * i / 3f
            canvas.drawLine(x, frame.top, x, frame.bottom, grid)
            val y = frame.top + frame.height() * i / 3f
            canvas.drawLine(frame.left, y, frame.right, y, grid)
        }
    }
}
