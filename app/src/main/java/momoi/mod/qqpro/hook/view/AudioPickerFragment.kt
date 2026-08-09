package momoi.mod.qqpro.hook.view

import android.content.ContentUris
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.drawable.audioFileIconDrawable
import momoi.mod.qqpro.hook.sendPickedAudio
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.linearLayout
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.marginHorizontal
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * In-app audio-file browser for the "音频文件" panel option. Lists local audio files via
 * MediaStore (newest first) and lets the user pick one to send as a voice (PTT) message — the
 * in-app counterpart to the system file picker, mirroring how 相册 has an in-app gallery vs. the
 * system image picker. Used when [momoi.mod.qqpro.Settings.useSystemAudioPicker] is off.
 */
class AudioPickerFragment : MyDialogFragment() {

    private data class Entry(val uri: Uri, val name: String, val durationMs: Long, val size: Long)

    private var allEntries: List<Entry> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var emptyHint: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).vertical()
        root.setBackgroundColor(M3.surface)

        allEntries = queryAudio()
        Utils.log("AudioPicker: ${allEntries.size} entries")

        root.content {
            add<TextView>()
                .text("选择音频文件")
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .width(FILL)
                .padding(top = 14.dp, bottom = 10.dp)

            add<EditText>()
                .textSize(14f)
                .textColor(M3.onSurface)
                .width(FILL)
                .padding(left = 12.dp, top = 8.dp, right = 12.dp, bottom = 8.dp)
                .marginHorizontal(16.dp)
                .margin(bottom = 8.dp)
                .apply {
                    hint = "搜索文件名"
                    setHintTextColor(M3.hint)
                    setSingleLine()
                    background = GradientDrawable().apply {
                        setColor(M3.surfaceContainer)
                        cornerRadius = 12.dp.toFloat()
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            render(s?.toString().orEmpty())
                        }
                    })
                }

            emptyHint = add<TextView>()
                .text("未找到音频文件\n(可在设置中改用系统音频选择器)")
                .textSize(13f)
                .textColor(M3.hint)
                .gravity(Gravity.CENTER)
                .width(FILL)
                .padding(top = 24.dp, left = 24.dp, right = 24.dp)

            list = add<RecyclerView>().linearLayout()
            (list.layoutParams as LinearLayout.LayoutParams).apply {
                width = FILL
                height = 0
                weight = 1f
            }
        }
        render("")
        // Wrap so a left-to-right swipe dismisses the picker, for watches without a back button.
        return SwipeBackLayout(ctx).apply {
            addView(root, FILL, FILL)
            onSwipeBack = { dismiss() }
        }
    }

    private fun render(query: String) {
        val q = query.trim()
        val shown = if (q.isEmpty()) allEntries
        else allEntries.filter { it.name.contains(q, ignoreCase = true) }
        emptyHint.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        list.content(
            data = shown,
            factory = { rowView() },
            update = { entry ->
                val title = (getChildAt(1) as LinearLayout).getChildAt(0) as TextView
                val sub = (getChildAt(1) as LinearLayout).getChildAt(1) as TextView
                title.text = entry.name
                sub.text = "${formatDuration(entry.durationMs)} · ${formatSize(entry.size)}"
                clickable { pick(entry) }
            }
        )
    }

    /** Query local audio files via MediaStore, newest first. Returns empty on missing permission. */
    private fun queryAudio(): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            val cols = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
            )
            requireContext().contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                cols, null, null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val name = c.getString(nameIdx) ?: id.toString()
                    out.add(Entry(uri, name, c.getLong(durIdx), c.getLong(sizeIdx)))
                }
            }
        }.onFailure { Utils.log("AudioPicker: query failed: $it") }
        return out
    }

    private fun pick(entry: Entry) {
        runCatching {
            sendPickedAudio(entry.uri)
            Utils.log("AudioPicker: picked ${entry.name}")
        }.onFailure { Utils.log("AudioPicker: pick failed: $it") }
        dismiss()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** A row: audio icon on the left, file name + duration/size subtitle on the right. */
    private fun rowView(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.setPadding(16.dp, 10.dp, 16.dp, 10.dp)

        val icon = ImageView(ctx)
        icon.layoutParams = LinearLayout.LayoutParams(28.dp, 28.dp)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setImageDrawable(audioFileIconDrawable())
        row.addView(icon)

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12.dp
        }

        val title = TextView(ctx)
        title.textSize = 14f
        title.setTextColor(M3.onSurface)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        texts.addView(title)

        val sub = TextView(ctx)
        sub.textSize = 11f
        sub.setTextColor(M3.hint)
        texts.addView(sub)

        row.addView(texts)
        return row
    }
}
