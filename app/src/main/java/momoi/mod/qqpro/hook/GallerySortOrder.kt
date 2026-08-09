package momoi.mod.qqpro.hook

import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.tencent.mobileqq.activity.photo.LocalMediaInfo
import com.tencent.qqnt.watch.gallery.GalleryRepository
import kotlin.Unit
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Replaces [GalleryRepository.b], the per-page MediaStore query that backs the
 * image/video picker. When [Settings.gallerySortByDateTaken] is on, it sorts by
 * the EXIF capture time ("datetaken") instead of the default "date_modified",
 * and rewrites each item's date_modified sort key (field [LocalMediaInfo.f]) so
 * the repository's post-merge comparator keeps the same order. Files with no
 * capture time fall back to date_modified so they don't all sink to the bottom.
 *
 * The body mirrors the original method (see decompiled GalleryRepository.b);
 * only the column list, ORDER BY clause and the date_taken override are added.
 */
@Mixin
class GallerySortOrder : GalleryRepository() {

    override fun b(
        uri: Uri,
        extraColumns: Array<out String>,
        offset: Int,
        mediaInfoHandler: (LocalMediaInfo, Cursor) -> Unit
    ): MutableList<LocalMediaInfo> {
        val byDateTaken = Settings.gallerySortByDateTaken.value
        var cursor: Cursor? = null
        try {
            val result = ArrayList<LocalMediaInfo>()

            // BASE_COLUMNS + extraColumns, with "datetaken" appended last when sorting by it.
            val base = a + extraColumns
            val columns = if (byDateTaken) base + "datetaken" else base
            val takenIdx = if (byDateTaken) columns.size - 1 else -1

            val order = if (byDateTaken) {
                "datetaken DESC, date_modified DESC, date_added DESC LIMIT 20 OFFSET $offset"
            } else {
                "date_modified DESC, date_added DESC LIMIT 20 OFFSET $offset"
            }

            cursor = Utils.application.contentResolver.query(uri, columns, null, null, order)
            if (cursor == null) return result
            if (-1 >= cursor.count - 1) {
                cursor.close()
                return result
            }

            if (uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                e += cursor.count
            } else {
                f += cursor.count
            }

            var i = -1
            while (true) {
                i++
                if (!cursor.moveToPosition(i)) {
                    cursor.close()
                    return result
                }
                val path = cursor.getString(1)
                if (cursor.getLong(5) > 0 && File(path).exists()) {
                    val info = LocalMediaInfo()
                    if (!cursor.isClosed) {
                        info.b = cursor.getLong(0)
                        info.c = cursor.getString(1)
                        info.B = cursor.getString(2)
                        info.e = cursor.getLong(3)
                        info.f = cursor.getLong(4)
                        info.d = cursor.getLong(5)
                        info.E = cursor.getInt(6)
                        info.F = cursor.getInt(7)
                        if (byDateTaken && takenIdx >= 0) {
                            // datetaken is in ms; date_modified is in seconds. Use ms for the
                            // sort key uniformly so the post-merge comparator orders correctly.
                            val taken = cursor.getLong(takenIdx)
                            info.f = if (taken > 0) taken else info.f * 1000
                        }
                        mediaInfoHandler.invoke(info, cursor)
                    }
                    result.add(info)
                }
            }
        } catch (th: Throwable) {
            Utils.log("GallerySortOrder query error: $th")
            return ArrayList()
        } finally {
            cursor?.close()
        }
    }
}
