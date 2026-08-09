package momoi.mod.qqpro.hook

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.view.View
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.frame.contentViewHolder.FeedCommentViewHolder
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import momoi.anno.mixin.Mixin

/**
 * QZone 动态(说说)点赞按钮：已赞(按下)状态太接近未赞状态。
 * n(context, resId) 负责把点赞图标 drawable 取出来设置到 tvLike / 评论按钮。
 * - 当 resId 是 icon_feed_like(已赞图标)时给它染成蓝色，让按下状态一目了然。
 * - 当 resId 是 icon_feed_common(评论图标)时换成 Material chat 符号，与全局 Material 风格一致。
 */
@Mixin
class FeedLikeBlue(p0: View, p1: Int, p2: IAdapterHost) : FeedCommentViewHolder(p0, p1, p2) {

    override fun n(context: Context, resId: Int): Drawable? {
        val drawable = super.n(context, resId)
        fun id(name: String) = context.resources.getIdentifier(name, "drawable", context.packageName)
        val likedId = id("icon_feed_like")
        val unlikedId = id("icon_feed_like_disable")
        val commentId = id("icon_feed_common")

        // Size any replacement symbol to the native icon's bounds so it lines up with the text.
        fun symbol(path: String, tint: Int): MaterialSymbol {
            val sym = MaterialSymbol(path, tint)
            val w = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: 18.dp
            val h = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: 18.dp
            sym.setBounds(0, 0, w, h)
            return sym
        }

        return when (resId) {
            // 评论按钮 → Material chat-bubble symbol.
            commentId -> {
                Utils.log("FeedLikeBlue: replace comment icon with Material symbol")
                symbol(MaterialSymbols.chat_bubble, M3.primary)
            }
            // 已赞 → filled-look thumb_up in accent blue (state shown by color).
            likedId -> {
                Utils.log("FeedLikeBlue: replace liked icon with Material thumb_up (accent)")
                symbol(MaterialSymbols.thumb_up, M3.primary)
            }
            // 未赞 → same thumb_up in a muted inactive color.
            unlikedId -> {
                Utils.log("FeedLikeBlue: replace unliked icon with Material thumb_up (muted)")
                symbol(MaterialSymbols.thumb_up, M3.onSurfaceVariant)
            }
            else -> drawable
        }
    }
}
