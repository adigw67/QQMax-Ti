package momoi.mod.qqpro.hook

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File
import kotlin.concurrent.thread

/**
 * 内置图片选择器（单张）：读取本机 MediaStore 图片，M3 网格展示，点选后点「确定」返回文件路径。
 * 取代系统 ACTION_GET_CONTENT 选择器——手表 ROM 没有系统图库/文件选择器时背景选图也能用。
 *
 * 结果：RESULT_OK + [EXTRA_PATH]（文件路径）且 data.data 为 file:// Uri，兼容原裁剪页入口。
 */
class ImagePickerActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "image_path"
        const val EXTRA_TITLE = "picker_title"
    }

    private class Entry(val path: String)

    private val entries = ArrayList<Entry>()
    private var selected: String? = null
    private var adapter: RecyclerView.Adapter<*>? = null
    private var countLabel: TextView? = null
    private var confirmButton: M3Button? = null
    private var emptyView: TextView? = null
    private val thumbCache = HashMap<String, android.graphics.Bitmap>()
    private var squareSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val edge = if (Utils.isRoundScreen) 14.dp else 6.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 8.dp)
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countLabel = TextView(ctx).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "选择图片"
            setTextColor(M3.onSurface)
            textSize = 14f
        }
        top.addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(M3Button(ctx).apply {
            text = "取消"
            variant(M3Button.Variant.TEXT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        confirmButton = M3Button(ctx).apply {
            text = "确定"
            variant(M3Button.Variant.FILLED)
            setOnClickListener { confirm() }
        }
        top.addView(confirmButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 方网格：圆屏 2 列、方屏 3 列，单元格为正方形。
        val cols = if (Utils.isRoundScreen) 2 else 3
        squareSize = ((resources.displayMetrics.widthPixels - 2 * edge - (cols - 1) * 6.dp) / cols).coerceAtLeast(60.dp)
        val rv = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, cols)
            setPadding(0, 6.dp, 0, 0)
            clipToPadding = false
        }
        adapter = GridAdapter()
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = TextView(ctx).apply {
            text = "没有找到图片"
            textSize = 13f
            setTextColor(M3.hint)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        loadImages()
    }

    private fun loadImages() {
        thread {
            val list = runCatching { queryImages() }.getOrDefault(emptyList())
            runOnUi {
                entries.clear()
                entries.addAll(list)
                adapter?.notifyDataSetChanged()
                emptyView?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                confirmButton?.isEnabled = false
                Utils.log("ImagePicker: loaded ${entries.size} images")
            }
        }
    }

    private fun queryImages(): List<Entry> {
        val out = ArrayList<Entry>()
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        runCatching {
            contentResolver.query(uri, proj, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext() && out.size < 400) {
                    val path = c.getString(dataCol) ?: continue
                    if (!File(path).exists()) continue
                    out.add(Entry(path))
                }
            }
        }.onFailure { Utils.log("ImagePicker query: $it") }
        return out
    }

    private fun toggle(e: Entry) {
        selected = if (selected == e.path) null else e.path
        adapter?.notifyDataSetChanged()
        confirmButton?.isEnabled = selected != null
    }

    private fun confirm() {
        val path = selected ?: return
        val data = Intent().apply {
            putExtra(EXTRA_PATH, path)
            setData(Uri.fromFile(File(path)))
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val cell = FrameLayout(ctx).apply {
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusSm)
            }
            cell.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                id = android.R.id.icon
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(TextView(ctx).apply {
                id = android.R.id.text1
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                text = "✓"
                background = M3.rounded(M3.primary, 999f)
                visibility = View.GONE
            }, FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.TOP or Gravity.END).apply {
                val m = 4.dp
                setMargins(m, m, m, m)
            })
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            val cell = holder.itemView as FrameLayout
            val iv = cell.findViewById<ImageView>(android.R.id.icon)
            val badge = cell.findViewById<TextView>(android.R.id.text1)
            val h = if (squareSize > 0) squareSize else 96.dp
            cell.layoutParams = (cell.layoutParams ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)).apply { height = h }
            iv.tag = e.path
            val cached = thumbCache[e.path]
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                val p = e.path
                thread {
                    val bmp = runCatching { decodeThumb(p) }.getOrNull() ?: return@thread
                    thumbCache[p] = bmp
                    iv.post { if (iv.tag == p) iv.setImageBitmap(bmp) }
                }
            }
            val sel = selected == e.path
            badge.visibility = if (sel) View.VISIBLE else View.GONE
            cell.alpha = if (sel) 0.75f else 1f
            cell.setOnClickListener { toggle(e) }
        }

        private fun decodeThumb(path: String): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val target = 220
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
        }
    }
}
