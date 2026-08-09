package momoi.mod.qqpro.hook.qzone
import android.os.Build
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mobileqq.activity.photo.LocalMediaInfo
import downloadExecutor
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.symbolImage
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Self-contained Material 3 media picker (device gallery) for the QZone compose screen. A
 * [MyDialogFragment] shown OVER the compose dialog — no NavController navigation, so it never
 * disrupts the compose state (the native QQ gallery navigates and is chat-coupled, which both
 * loses the post draft and routes picks into the chat input bar).
 *
 * Reads images + videos from MediaStore, shows a 3-column M3 grid, multi-selects images but enforces
 * the "a post is either ONE video or multiple images" rule, and returns the chosen items as
 * [LocalMediaInfo] (path in `c`, type in `C`, dims in `E`/`F`) via [onPick].
 */
class QzoneMediaPicker(
    private val onPick: ((List<LocalMediaInfo>) -> Unit)? = null,
) : MyDialogFragment() {

    constructor() : this(null)

    private class Entry(val path: String, val isVideo: Boolean)

    private val entries = ArrayList<Entry>()
    private val selected = LinkedHashSet<String>()
    private var selectedVideo: String? = null
    private var adapter: RecyclerView.Adapter<*>? = null
    private var countLabel: TextView? = null
    // Decoded thumbnails kept so a rebind (scroll recycle / selection notify) never re-decodes → no flicker.
    private val thumbCache = java.util.HashMap<String, android.graphics.Bitmap>()
    private var squareSize = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 14.dp else 6.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 8.dp)
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        countLabel = TextView(ctx).apply { text = "选择图片/视频"; setTextColor(M3.onSurface); textSize = 14f }
        top.addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(M3Button(ctx).apply {
            text = "确定"; variant(M3Button.Variant.FILLED); setOnClickListener { confirm() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2-wide square grid.
        squareSize = ((ctx.resources.displayMetrics.widthPixels - 2 * edge - 6.dp) / 2).coerceAtLeast(60.dp)
        val rv = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 2)
            setPadding(0, 6.dp, 0, 0); clipToPadding = false
        }
        adapter = GridAdapter()
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        loadMedia()
        return swipeBackWrap(root)
    }

    private fun loadMedia() {
        val ctx = requireContext().applicationContext
        downloadExecutor.execute {
            val list = runCatching { queryMedia(ctx) }.getOrDefault(emptyList())
            runCatching {
                activity?.runOnUiThread {
                    entries.clear(); entries.addAll(list)
                    adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun queryMedia(ctx: android.content.Context): List<Entry> {
        val out = ArrayList<Entry>()
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        runCatching {
            ctx.contentResolver.query(uri, proj, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext() && out.size < 400) {
                    val path = c.getString(dataCol) ?: continue
                    if (!File(path).exists()) continue
                    out.add(Entry(path, c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO))
                }
            }
        }.onFailure { Utils.log("QzoneMediaPicker query: $it") }
        Utils.log("QzoneMediaPicker: loaded ${out.size} media")
        return out
    }

    private fun toggle(e: Entry) {
        if (e.isVideo) {
            // A video must be alone.
            if (selectedVideo == e.path) { selectedVideo = null }
            else { selectedVideo = e.path; selected.clear() }
        } else {
            selectedVideo = null
            if (!selected.remove(e.path)) selected.add(e.path)
        }
        adapter?.notifyDataSetChanged()
        updateCount()
    }

    private fun isSelected(e: Entry): Int =
        if (e.isVideo) { if (selectedVideo == e.path) 1 else 0 }
        else { val i = selected.toList().indexOf(e.path); if (i >= 0) i + 1 else 0 }

    private fun updateCount() {
        val n = if (selectedVideo != null) 1 else selected.size
        countLabel?.text = if (n > 0) "已选 $n" else "选择图片/视频"
    }

    private fun confirm() {
        val picks = ArrayList<LocalMediaInfo>()
        selectedVideo?.let { picks.add(mediaInfo(it, true)) }
        selected.forEach { picks.add(mediaInfo(it, false)) }
        runCatching { onPick?.invoke(picks) }.onFailure { Utils.log("QzoneMediaPicker confirm: $it") }
        runCatching { dismiss() }
    }

    private fun mediaInfo(path: String, video: Boolean): LocalMediaInfo = LocalMediaInfo().apply {
        c = path
        C = if (video) 1 else 0
        if (!video) runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o); E = o.outWidth; F = o.outHeight
        }
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val cell = FrameLayout(ctx).apply {
                setClipToOutlineCompat(true)
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusSm)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, M3.radiusSm)
                    }
                }
            }
            cell.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; maxHeight = 400.dp; id = android.R.id.icon
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            // selection badge
            cell.addView(TextView(ctx).apply {
                id = android.R.id.text1; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = M3.rounded(M3.primary, 999f); visibility = View.GONE
            }, FrameLayout.LayoutParams(18.dp, 18.dp, Gravity.TOP or Gravity.END).apply { val m = 4.dp; setMargins(m, m, m, m) })
            // video play marker
            cell.addView(symbolImage(ctx, MaterialSymbols.play_arrow, Color.WHITE, 26).apply {
                id = android.R.id.icon1; visibility = View.GONE
            }, FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.CENTER))
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            val cell = holder.itemView as FrameLayout
            val iv = cell.findViewById<ImageView>(android.R.id.icon)
            val badge = cell.findViewById<TextView>(android.R.id.text1)
            val play = cell.findViewById<View>(android.R.id.icon1)
            val h = if (squareSize > 0) squareSize else 96.dp
            cell.layoutParams = (cell.layoutParams ?: RecyclerView.LayoutParams(MATCH, h)).apply { height = h }
            iv.tag = e.path
            val cached = thumbCache[e.path]
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                downloadExecutor.execute {
                    val bmp = runCatching { decodeThumb(e) }.getOrNull() ?: return@execute
                    thumbCache[e.path] = bmp
                    iv.post { if (iv.tag == e.path) iv.setImageBitmap(bmp) }
                }
            }
            play.visibility = if (e.isVideo) View.VISIBLE else View.GONE
            val sel = isSelected(e)
            if (sel > 0) {
                badge.visibility = View.VISIBLE
                badge.text = if (e.isVideo) "✓" else sel.toString()
                cell.alpha = 0.7f
            } else { badge.visibility = View.GONE; cell.alpha = 1f }
            cell.setOnClickListener { toggle(e) }
        }

        private fun decodeThumb(e: Entry): android.graphics.Bitmap? {
            if (e.isVideo) {
                return runCatching {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(e.path, MediaStore.Images.Thumbnails.MINI_KIND)
                }.getOrNull()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(e.path, bounds)
            var sample = 1
            val target = 200
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return runCatching { BitmapFactory.decodeFile(e.path, opts) }.getOrNull()
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
