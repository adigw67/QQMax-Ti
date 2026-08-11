package momoi.mod.qqpro.hook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.hook.aio_cell.BiliCard
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import loadPicUrl

/**
 * B站视频详情页：展示封面、标题、简介、播放/弹幕/点赞/投币/收藏/评论、标签、发布时间、AV/BV 号，
 * 并提供「打开客户端」（优先哔哩终端，其次官方）与「复制链接」。
 */
class BiliDetailActivity : Activity() {
    companion object {
        const val EXTRA_BVID = "bili_bvid"
        const val EXTRA_AID = "bili_aid"
        const val EXTRA_SHORT = "bili_short"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(view: TextView, value: Float) =
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private var info: BiliCard.VideoInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "B站视频"
            setTextColor(M3.onSurface)
            sp(this, 15f)
            gravity = Gravity.CENTER
        })

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(body, LinearLayout.LayoutParams(FILL, WRAP))
        }
        root.addView(scroll, LinearLayout.LayoutParams(FILL, 0, 1f))

        root.addView(TextView(this).apply {
            text = "加载中…"
            setTextColor(M3.onSurfaceVariant)
            sp(this, 10f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }.also { loading = it })

        root.addView(buildButtons(), LinearLayout.LayoutParams(FILL, WRAP).apply {
            topMargin = dp(8)
        })
        setContentView(root)

        val bvid = intent.getStringExtra(EXTRA_BVID)
        val aid = intent.getLongExtra(EXTRA_AID, -1L)
        val short = intent.getStringExtra(EXTRA_SHORT)
        val ref = when {
            !bvid.isNullOrBlank() -> BiliCard.BiliRef(bvid, null, null)
            aid > 0 -> BiliCard.BiliRef(null, aid, null)
            !short.isNullOrBlank() -> BiliCard.BiliRef(null, null, short)
            else -> null
        }
        if (ref == null) {
            loading?.text = "无法解析视频链接"
            return
        }
        BiliCard.fetchInfo(ref) { v ->
            runOnUi {
                if (v == null) {
                    loading?.text = "获取视频信息失败"
                } else {
                    loading?.visibility = View.GONE
                    render(v)
                }
            }
        }
    }

    private var loading: TextView? = null

    private fun render(v: BiliCard.VideoInfo) {
        info = v
        val ctx = this
        val density = resources.displayMetrics.density
        fun dp2(x: Int) = (x * density).toInt()

        body.addView(ImageView(ctx).apply {
            maxHeight = dp2(180)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            loadPicUrl(v.pic, cacheFileName = "bili${v.bvid}")
        }, LinearLayout.LayoutParams(FILL, WRAP))

        body.addView(TextView(ctx).apply {
            text = v.title
            setTextColor(M3.onSurface)
            sp(this, 14f)
            setPadding(0, dp2(6), 0, dp2(2))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        body.addView(TextView(ctx).apply {
            text = buildString {
                if (v.owner.isNotBlank()) append(v.owner).append(" · ")
                append(v.durationText()).append(" · ")
                append(v.pubdateText())
                if (v.tname.isNotBlank()) append(" · ").append(v.tname)
            }
            setTextColor(M3.onSurfaceVariant)
            sp(this, 10.5f)
        }, LinearLayout.LayoutParams(FILL, WRAP))

        // 数据区：两行三列（播放/弹幕/点赞 / 投币/收藏/评论），无底色灰框。
        body.addView(statsRow(listOf("播放" to v.view, "弹幕" to v.danmaku, "点赞" to v.like)), LinearLayout.LayoutParams(FILL, WRAP).apply {
            topMargin = dp2(10)
        })
        body.addView(statsRow(listOf("投币" to v.coin, "收藏" to v.favorite, "评论" to v.reply)), LinearLayout.LayoutParams(FILL, WRAP).apply {
            topMargin = dp2(4)
        })
        if (v.share > 0) {
            body.addView(TextView(ctx).apply {
                text = "分享 ${BiliCard.fmt(v.share)}"
                setTextColor(M3.onSurfaceVariant)
                sp(this, 10f)
                gravity = Gravity.CENTER
                setPadding(0, dp2(4), 0, dp2(2))
            }, LinearLayout.LayoutParams(FILL, WRAP))
        }

        if (v.tags.isNotEmpty()) {
            body.addView(TextView(ctx).apply {
                text = v.tags.joinToString(" ", prefix = "#", postfix = "") { it }
                setTextColor(M3.primary)
                sp(this, 10.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp2(8), 0, dp2(2))
            }, LinearLayout.LayoutParams(FILL, WRAP))
        }

        body.addView(TextView(ctx).apply {
            text = "AV${v.aid} · ${v.bvid}"
            setTextColor(M3.onSurfaceVariant)
            sp(this, 10f)
            setPadding(0, dp2(4), 0, dp2(2))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        if (v.desc.isNotBlank()) {
            body.addView(TextView(ctx).apply {
                text = v.desc
                setTextColor(M3.onSurfaceVariant)
                sp(this, 10.5f)
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp2(4), 0, dp2(4))
            }, LinearLayout.LayoutParams(FILL, WRAP))
        }
    }

    private fun statsRow(items: List<Pair<String, Long>>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        items.forEachIndexed { index, (label, value) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            cell.addView(TextView(this).apply {
                text = BiliCard.fmt(value)
                setTextColor(M3.onSurface)
                sp(this, 11.5f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            cell.addView(TextView(this).apply {
                text = label
                setTextColor(M3.onSurfaceVariant)
                sp(this, 9.5f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (index < items.size - 1) {
                row.addView(TextView(this).apply {
                    text = "·"
                    setTextColor(M3.onSurfaceVariant)
                    sp(this, 10f)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(WRAP, WRAP))
            }
        }
        return row
    }

    private fun buildButtons(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(button("打开客户端") {
            val v = info
            if (v == null) {
                Utils.toast(this@BiliDetailActivity, "视频信息未加载，请稍后重试")
                return@button
            }
            BiliCard.openClient(this@BiliDetailActivity, v)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
        row.addView(button("复制链接") {
            val v = info ?: return@button
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bilibili", v.webUrl()))
            Utils.toast(this@BiliDetailActivity, "已复制链接")
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(4) })
        return row
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(M3.onPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setBackgroundColor(M3.primary)
            sp(this, 12f)
            setOnClickListener { onClick() }
        }
}
