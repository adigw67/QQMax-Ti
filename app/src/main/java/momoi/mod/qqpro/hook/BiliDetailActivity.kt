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
import momoi.mod.qqpro.hook.aio_cell.BiliParser
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import loadPicUrl

/**
 * B站详情页：
 *  - 视频：封面、标题、简介、播放/弹幕/点赞/投币/收藏/评论、标签、发布时间、AV/BV 号，
 *    可跳转「哔哩终端」或官方客户端；
 *  - 动态：作者、时间、完整正文、图片；
 *  - 专栏：封面、标题、作者、摘要。
 * 均提供「复制链接」与「打开链接」。
 */
class BiliDetailActivity : Activity() {
    companion object {
        const val EXTRA_BVID = "bili_bvid"
        const val EXTRA_AID = "bili_aid"
        const val EXTRA_SHORT = "bili_short"
        const val EXTRA_DYNAMIC_ID = "bili_dynamic"
        const val EXTRA_ARTICLE_ID = "bili_article"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(view: TextView, value: Float) =
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

    private lateinit var root: LinearLayout
    private lateinit var header: TextView
    private lateinit var body: LinearLayout
    private var loading: TextView? = null
    private var btnOpen: TextView? = null
    private var openAction: (() -> Unit)? = null
    private var copyUrl: String = ""
    // 已知 BV 号：有它时「打开客户端」直接用哔哩终端 GetIntentActivity 直开，不依赖 API。
    private var knownBvid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        header = TextView(this).apply {
            text = "B站"
            setTextColor(M3.onSurface)
            sp(this, 15f)
            gravity = Gravity.CENTER
        }
        root.addView(header)

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
        val dynamic = intent.getStringExtra(EXTRA_DYNAMIC_ID)
        val article = intent.getStringExtra(EXTRA_ARTICLE_ID)
        val target: BiliParser.Target? = when {
            !bvid.isNullOrBlank() -> BiliParser.Target.Video(bvid, null, null)
            aid > 0 -> BiliParser.Target.Video(null, aid, null)
            !short.isNullOrBlank() -> BiliParser.Target.ShortLink(short)
            !dynamic.isNullOrBlank() -> BiliParser.Target.Dynamic(dynamic)
            !article.isNullOrBlank() -> BiliParser.Target.Article(article)
            else -> null
        }
        if (target == null) {
            loading?.text = "无法解析B站链接"
            return
        }
        loadTarget(target)
    }

    private fun loadTarget(target: BiliParser.Target) {
        when (target) {
            is BiliParser.Target.Video -> {
                loading?.text = "视频信息加载中…"
                knownBvid = target.bvid
                // BV 已知：打开客户端按钮立即生效（哔哩终端 GetIntentActivity 直开，不依赖 API）。
                if (target.bvid != null) {
                    btnOpen?.text = "打开客户端"
                    openAction = { BiliCard.openClientByBvid(this@BiliDetailActivity, target.bvid!!) }
                }
                BiliCard.fetchInfo(target) { v ->
                    runOnUi {
                        if (v == null) {
                            // 接口风控/无网络：展示链接本身，点击可用浏览器打开。
                            loading?.visibility = View.GONE
                            header.text = "B站视频"
                            body.addView(TextView(this@BiliDetailActivity).apply {
                                text = target.webUrl()
                                setTextColor(M3.primary)
                                sp(this, 12.5f)
                                setPadding(0, 0, 0, dp(6))
                            }, LinearLayout.LayoutParams(FILL, WRAP))
                            body.addView(TextView(this@BiliDetailActivity).apply {
                                text = "（视频信息获取失败，可打开链接查看）"
                                setTextColor(M3.onSurfaceVariant)
                                sp(this, 10.5f)
                            }, LinearLayout.LayoutParams(FILL, WRAP))
                            if (knownBvid != null) {
                                btnOpen?.text = "打开客户端"
                                openAction = { BiliCard.openClientByBvid(this@BiliDetailActivity, knownBvid!!) }
                            } else {
                                btnOpen?.text = "打开链接"
                                openAction = { Utils.openUrl(target.webUrl()) }
                            }
                            copyUrl = target.webUrl()
                        } else {
                            loading?.visibility = View.GONE
                            renderVideo(v)
                        }
                    }
                }
            }
            is BiliParser.Target.ShortLink -> {
                loading?.text = "链接解析中…"
                BiliParser.resolveShort(target.code) { resolved ->
                    runOnUi {
                        if (resolved == null) {
                            // 无网络/短链失效：直接展示链接本身，点击可用浏览器打开。
                            loading?.visibility = View.GONE
                            header.text = "B站链接"
                            body.addView(TextView(this@BiliDetailActivity).apply {
                                text = "短链：${target.webUrl()}"
                                setTextColor(M3.onSurface)
                                sp(this, 12.5f)
                                setPadding(0, 0, 0, dp(6))
                            }, LinearLayout.LayoutParams(FILL, WRAP))
                            body.addView(TextView(this@BiliDetailActivity).apply {
                                text = "（网络不可用，无法解析为具体视频/动态）"
                                setTextColor(M3.onSurfaceVariant)
                                sp(this, 10.5f)
                            }, LinearLayout.LayoutParams(FILL, WRAP))
                            if (knownBvid != null) {
                                btnOpen?.text = "打开客户端"
                                openAction = { BiliCard.openClientByBvid(this@BiliDetailActivity, knownBvid!!) }
                            } else {
                                btnOpen?.text = "打开链接"
                                openAction = { Utils.openUrl(target.webUrl()) }
                            }
                            copyUrl = target.webUrl()
                        } else {
                            loadTarget(resolved)
                        }
                    }
                }
            }
            is BiliParser.Target.Dynamic -> {
                loading?.text = "动态加载中…"
                BiliParser.fetchDynamic(target.id) { info ->
                    runOnUi {
                        if (info == null) {
                            loading?.text = "动态获取失败"
                        } else {
                            loading?.visibility = View.GONE
                            renderDynamic(info)
                        }
                    }
                }
            }
            is BiliParser.Target.Article -> {
                loading?.text = "专栏加载中…"
                BiliParser.fetchArticle(target.id) { info ->
                    runOnUi {
                        if (info == null) {
                            loading?.text = "专栏获取失败"
                        } else {
                            loading?.visibility = View.GONE
                            renderArticle(info)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 视频

    private fun renderVideo(v: BiliCard.VideoInfo) {
        header.text = "B站视频"
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
                setPadding(0, dp2(4), 0, dp2(4))
            }, LinearLayout.LayoutParams(FILL, WRAP))
        }

        btnOpen?.text = "打开客户端"
        openAction = { BiliCard.openClient(ctx, v) }
        copyUrl = v.webUrl()
    }

    // ------------------------------------------------------------------ 动态

    private fun renderDynamic(info: BiliParser.DynamicInfo) {
        header.text = "B站动态"
        val ctx = this
        val density = resources.displayMetrics.density
        fun dp2(x: Int) = (x * density).toInt()

        body.addView(TextView(ctx).apply {
            text = buildString {
                if (info.author.isNotBlank()) append(info.author)
                val t = info.timeText()
                if (t.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(t)
                }
            }.ifBlank { "未知作者" }
            setTextColor(M3.onSurface)
            sp(this, 14f)
            setPadding(0, 0, 0, dp2(6))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        body.addView(TextView(ctx).apply {
            text = info.content.ifBlank { "（无文字内容）" }
            setTextColor(M3.onSurface)
            sp(this, 12.5f)
            setPadding(0, 0, 0, dp2(6))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        info.pictures.take(9).forEach { url ->
            body.addView(ImageView(ctx).apply {
                maxHeight = dp2(160)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                loadPicUrl(url, cacheFileName = "bilidyn${url.hashCode()}")
            }, LinearLayout.LayoutParams(FILL, WRAP).apply {
                bottomMargin = dp2(4)
            })
        }

        body.addView(TextView(ctx).apply {
            text = info.webUrl()
            setTextColor(M3.primary)
            sp(this, 10f)
            setPadding(0, dp2(4), 0, dp2(2))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        btnOpen?.text = "打开链接"
        openAction = { Utils.openUrl(info.webUrl()) }
        copyUrl = info.webUrl()
    }

    // ------------------------------------------------------------------ 专栏

    private fun renderArticle(info: BiliParser.ArticleInfo) {
        header.text = "B站专栏"
        val ctx = this
        val density = resources.displayMetrics.density
        fun dp2(x: Int) = (x * density).toInt()

        if (!info.cover.isNullOrBlank()) {
            body.addView(ImageView(ctx).apply {
                maxHeight = dp2(160)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                loadPicUrl(info.cover, cacheFileName = "biliart${info.id}")
            }, LinearLayout.LayoutParams(FILL, WRAP).apply {
                bottomMargin = dp2(6)
            })
        }

        body.addView(TextView(ctx).apply {
            text = info.title.ifBlank { "（无标题）" }
            setTextColor(M3.onSurface)
            sp(this, 14f)
            setPadding(0, 0, 0, dp2(4))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        body.addView(TextView(ctx).apply {
            text = "作者：${info.author}"
            setTextColor(M3.onSurfaceVariant)
            sp(this, 10.5f)
            setPadding(0, 0, 0, dp2(6))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        if (info.summary.isNotBlank()) {
            body.addView(TextView(ctx).apply {
                text = info.summary
                setTextColor(M3.onSurface)
                sp(this, 12f)
                setPadding(0, 0, 0, dp2(6))
            }, LinearLayout.LayoutParams(FILL, WRAP))
        }

        body.addView(TextView(ctx).apply {
            text = info.webUrl()
            setTextColor(M3.primary)
            sp(this, 10f)
            setPadding(0, dp2(4), 0, dp2(2))
        }, LinearLayout.LayoutParams(FILL, WRAP))

        btnOpen?.text = "打开链接"
        openAction = { Utils.openUrl(info.webUrl()) }
        copyUrl = info.webUrl()
    }

    // ------------------------------------------------------------------ 通用

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
            }, LinearLayout.LayoutParams(FILL, WRAP))
            cell.addView(TextView(this).apply {
                text = label
                setTextColor(M3.onSurfaceVariant)
                sp(this, 9.5f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(FILL, WRAP))
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
            val act = openAction
            if (act == null) {
                Utils.toast(this@BiliDetailActivity, "内容未加载，请稍后重试")
                return@button
            }
            act()
        }.also { btnOpen = it }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(4) })
        row.addView(button("复制链接") {
            if (copyUrl.isBlank()) {
                Utils.toast(this@BiliDetailActivity, "内容未加载，请稍后重试")
                return@button
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bilibili", copyUrl))
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
