package momoi.mod.qqpro.hook

import android.content.Context
import android.graphics.drawable.Drawable
import com.tencent.util.LoadingUtil
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.lib.material.M3ProgressDrawable

/**
 * Materialize every native APNG loading spinner in one place. [LoadingUtil.b]`(context, type)` is the
 * single source of QQ's animated loading drawables — type 1=white, 2=grey (all the pull-to-refresh
 * heads incl. the main chat list's AIOViewHolderQUIImpl), 3=black, 4=colorful (the in-app camera,
 * loading dialogs, QR background, media preview, STT…). We return our themed [M3ProgressDrawable]
 * for all of them, so the grey refresh spinners and the colorful camera spinner become one
 * consistent M3 ring. Replaces the per-RefreshHead approach (this covers them too, via getHeader).
 *
 * Top-level @StaticHook fn with the same name/signature as the target static method.
 */
@StaticHook(LoadingUtil::class)
fun b(context: Context?, type: Int): Drawable? {
    if (context == null) return LoadingUtil.b(context, type)
    return M3ProgressDrawable()
}
