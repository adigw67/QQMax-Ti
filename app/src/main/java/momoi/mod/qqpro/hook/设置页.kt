package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.tencent.widget.Switch
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Pref
import momoi.mod.qqpro.applyOrientationSetting
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.lib.RoundWatch
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.aio_cell.BubbleCorner
import momoi.mod.qqpro.hook.aio_cell.GroupAvatarHook
import momoi.mod.qqpro.hook.style.CARD_MARGIN_DP
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Switch
import momoi.mod.qqpro.lib.material.MaterialColors
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.lib.material.showColorPicker
import momoi.mod.qqpro.hook.view.buildAboutView
import momoi.mod.qqpro.StyleChooserActivity
import momoi.mod.qqpro.MenuEditorActivity
import momoi.mod.qqpro.lib.height
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.onCheckedChange
import momoi.mod.qqpro.lib.onClick
import momoi.mod.qqpro.lib.rippleTouch
import momoi.mod.qqpro.lib.onProgressChanged
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.progressMax
import momoi.mod.qqpro.lib.size
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.SettingsBackup
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import momoi.mod.qqpro.watchdog.LogExporter
import momoi.mod.qqpro.watchdog.DebugActivity
import momoi.mod.qqpro.watchdog.WatchdogTestActivity
import moye.wearqq.SettingsActivity
import kotlin.math.roundToInt
import kotlin.concurrent.thread

// Routed through the single M3 token source so a retheme flows from lib/material/Material.kt.
private val ACCENT get() = M3.primary
private val TRACK_INACTIVE = M3.outline
private const val REQ_PICK_CHAT_BG = 0x9B01
private const val REQ_CROP_CHAT_BG = 0x9B02
private const val REQ_IMPORT_SETTINGS = 0x9B02
private const val REQ_PICK_MONET_IMG = 0x9B04

// Kept at file scope (not a @Mixin field — those can't have initializers) so
// onActivityResult can refresh the picker's status text after picking/clearing.
private var bgStatusLabel: TextView? = null
private var pendingBgPeer: String? = null
private var fontStatusLabel: TextView? = null

// Two-level navigation state, also at file scope to avoid @Mixin field initializers.
// The level-1 category list ([settingsRoot] inside [settingsScroll]) stays PERSISTENT in the
// [settingsContainer] FrameLayout; opening a category overlays a separate detail layer
// ([detailLayer], its own swipe-back) ON TOP. So swiping the detail back reveals the live level-1
// list behind it (not the black window) and keeps its scroll position. inSettingsDetail tells the
// hardware-back / outer-swipe gestures whether a detail layer is up.
private var settingsRoot: LinearLayout? = null
private var settingsScroll: ScrollView? = null
private var settingsContainer: FrameLayout? = null
private var detailLayer: View? = null
private var inSettingsDetail = false

/**
 * One entry in the top-level list; [build] fills the detail page with that category's rows.
 * Must be public (not private): the @Mixin body merges into moye.wearqq.SettingsActivity, and a
 * package-private class here would throw IllegalAccessError on cross-package runtime access.
 */
class SettingsCategory(
    val title: String,
    val subtitle: String,
    val build: momoi.mod.qqpro.lib.LinearScope.() -> Unit,
)

@Mixin
class 设置页 : SettingsActivity() {
    @SuppressLint("ResourceType", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 横屏模式：本设置页也跟随开关立即切换方向（onCreate 重建时再确认一次）。
        runCatching { applyOrientationSetting() }
            .onFailure { Utils.log("设置页: 横屏模式应用失败: $it") }
        // 圆表 UI（可选）：设置页盖圆表遮罩。
        runCatching { RoundWatch.apply(window.decorView) }
            .onFailure { Utils.log("设置页: 圆表遮罩失败: $it") }

        // Make this activity translucent + drop the system open/close transition: the SwipeBackLayout's
        // own fade+slide is our transition, and translucency lets a swipe-back reveal the screen
        // underneath instead of black. convertToTranslucent is @hide, so reach it reflectively.
        runCatching {
            val m = android.app.Activity::class.java.declaredMethods
                .filter { it.name == "convertToTranslucent" }
                .minByOrNull { it.parameterTypes.size } ?: return@runCatching
            m.isAccessible = true
            m.invoke(this, *arrayOfNulls<Any?>(m.parameterTypes.size))
        }.onFailure { Utils.log("设置页: convertToTranslucent failed: $it") }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        overridePendingTransition(0, 0)

        // Level-1 category list — persistent; stays behind any detail layer.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(M3.surface)
            // 圆屏安全区（可选）：顶/底留出圆边裁切区，内容不被圆角裁到。
            if (Settings.md3eRound.value) {
                val inset = RoundWatch.safeTopBottomPx(this@设置页)
                setPadding(0, inset, 0, inset)
            }
        }
        val root = LinearLayout(this)
            .vertical()
            .padding(left = (2 * CARD_MARGIN_DP).dp, top = 10.dp, right = (2 * CARD_MARGIN_DP).dp, bottom = 10.dp)
        scroll.addView(root, FILL, WRAP)

        // Container that stacks the level-1 list and (when open) a detail layer on top.
        val container = FrameLayout(this).apply { setBackgroundColor(M3.surface) }
        container.addView(scroll, FrameLayout.LayoutParams(FILL, FILL))

        // Watches without a hardware back button rely on a left-to-right swipe to leave a
        // screen (the main activity gets this from QQ's fling framework). Wrap the settings
        // content so the same gesture finishes this plain Activity.
        val swipeBack = SwipeBackLayout(this).apply {
            // The settings page must always be swipeable, even when "屏蔽应用内右滑返回" is on —
            // otherwise a watch with no hardware back button can't leave this screen to turn it off.
            ignoreDisableSetting = true
            setBackgroundColor(M3.surface)
            addView(container, FILL, FILL)
            // Only handles the level-1 swipe (leave the app). In a detail page the detail layer's own
            // swipe-back handles it, so suppress this one to avoid both grabbing the gesture.
            canSwipe = { !inSettingsDetail }
            onSwipeBack = { finish() }
        }
        setContentView(swipeBack)

        settingsScroll = scroll
        settingsRoot = root
        settingsContainer = container
        showCategoryList()
        // 字体包启用时，把设置页文字也强制换成 MiSans（反射换默认字体在部分 ROM 无效）。
        runCatching { window.decorView.post { FontPack.applyAll(window.decorView) } }
    }

    override fun onResume() {
        super.onResume()
        runCatching { FontPack.applyAll(window.decorView) }
    }

    /** Build the persistent top-level list: app header + one tappable card per category. */
    private fun showCategoryList() {
        val root = settingsRoot ?: return
        root.removeAllViews()
        root.content {
            section("QQMax-Ti", "by 爅峫 · java30433 · AILIFE · adigw67")
            for (cat in buildCategories()) {
                actionCard(cat.title, cat.subtitle) { showCategory(cat) }
            }
            add<View>()
                .height(64.dp)
        }
    }

    /**
     * Open a category as a detail layer overlaid on the (still-present) level-1 list: its own
     * ScrollView in its own [SwipeBackLayout], so swiping it back reveals the live list behind and
     * leaves the list's scroll position untouched. Replaces any layer already up.
     */
    private fun showCategory(cat: SettingsCategory) {
        val container = settingsContainer ?: return
        detailLayer?.let { container.removeView(it) }

        val detailScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(M3.surface)
        }
        val detailRoot = LinearLayout(this)
            .vertical()
            .padding(left = (2 * CARD_MARGIN_DP).dp, top = 10.dp, right = (2 * CARD_MARGIN_DP).dp, bottom = 10.dp)
        detailScroll.addView(detailRoot, FILL, WRAP)
        detailRoot.content {
            backHeader(cat.title, cat.subtitle) { popCategory() }
            cat.build(this)
            add<View>()
                .height(64.dp)
        }

        val layer = SwipeBackLayout(this).apply {
            ignoreDisableSetting = true
            // Opaque so it fully covers the list until it slides/fades on swipe.
            setBackgroundColor(M3.surface)
            addView(detailScroll, FILL, FILL)
            onSwipeBack = { popCategory() }
        }
        container.addView(layer, FrameLayout.LayoutParams(FILL, FILL))
        detailLayer = layer
        inSettingsDetail = true
    }

    /** Remove the detail layer, revealing the level-1 list at its preserved scroll position. */
    private fun popCategory() {
        val container = settingsContainer ?: return
        detailLayer?.let { container.removeView(it) }
        detailLayer = null
        inSettingsDetail = false
    }

    // Hardware/system back mirrors the swipe gesture: pop to the list before leaving.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inSettingsDetail) popCategory() else super.onBackPressed()
    }

    // No system close transition — we handle the exit visually (swipe slide/fade).
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    // Rotary-encoder (crown) scrolling. This is a separate Activity from the chat MainActivity, so it
    // needs its own dispatchGenericMotionEvent — without it the crown never reaches these ScrollViews.
    // Scope to the layer that's actually on top: the detail layer when open, else the level-1 list.
    // Resolve fresh each event (the tree is tiny) so we never keep scrolling the level-1 list behind
    // an open detail page. Reuses findTarget/applyRotaryScroll from 滚轮适配.kt.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val root = if (inSettingsDetail) detailLayer else settingsContainer
        root?.let { findTarget(it) }?.let { applyRotaryScroll(ev, it) }
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Tappable header at the top of a detail page; [onBack] returns to the category list. */
    private fun GroupScopeFix.backHeader(title: String, subtitle: String, onBack: () -> Unit) {
        val row = add<LinearLayout>()
            .width(FILL)
            .padding(left = 2.dp, top = 6.dp, right = 4.dp, bottom = 8.dp)
        row.gravity(Gravity.CENTER_VERTICAL)
        row.rippleTouch(clip = false)
        row.onClick { onBack() }
        row.content {
            add<TextView>()
                .textColor(ACCENT)
                .padding(left = 2.dp, right = 12.dp)
                .leadingSymbol(MaterialSymbols.chevron_left, ACCENT, sizeDp = 24, gap = 0)
            add<LinearLayout>()
                .vertical()
                .content {
                    add<TextView>()
                        .text(title)
                        .textSize(17f)
                        .textColor(M3.onSurface)
                    if (subtitle.isNotEmpty()) {
                        add<TextView>()
                            .text(subtitle)
                            .textSize(10f)
                            .textColor(M3.hint)
                    }
                }
        }
    }

    /** The full settings tree, grouped into categories for the two-level navigation. */
    private fun buildCategories(): List<SettingsCategory> = listOf(
        SettingsCategory("外观主题", "Material 颜色 (M3)") {
            val note = "重进页面生效"
            selector("外观模式", "Material 配色的明暗基调；浅色采用 M3 推荐的浅色基线配色，$note",
                listOf("深色", "浅色"),
                current = { if (Settings.lightMode.value) 1 else 0 }) { which ->
                Settings.lightMode.value = which == 1
            }
            switch("横屏模式", "开启后整个应用固定横屏显示（不跟随传感器自动旋转），关闭固定竖屏。切换后本页与主界面立即按新方向重建。", Settings.landscapeMode) {
                runCatching { applyOrientationSetting() }
                    .onFailure { Utils.log("设置页: 横屏模式切换失败: $it") }
            }
            switch("跟随系统主题色", "在支持 Material You 动态取色的设备(Android 12 / API 31 及以上)上，未单独设置的颜色自动取用系统壁纸配色(按明暗基调映射)；单独设置的颜色仍优先。旧系统(多数手表)无动态取色则用内置配色。$note", Settings.followSystemTheme)
            section("联网字体包", "可选：从官方源下载 MiSans（优先显示）+ Unifont（缺失字形兜底），仅本应用生效，防止生僻字缺失；字重按 MD 标准映射（常规 Regular / 中等 Medium / 粗体 Bold）；下载后重启应用全部界面生效")
            switch("启用联网字体包", "默认关闭；下载字体包后开启，应用内文字优先用 MiSans 渲染", Settings.fontPackEnabled)
            card { card ->
                card.vertical()
                card.content {
                    titleColumn("字体包状态", "MiSans 官方 hyperos.mi.com + Unifont 官方 GNU 镜像").width(FILL)
                    fontStatusLabel = add<TextView>()
                        .textSize(11f)
                        .textColor(M3.onSurfaceVariant)
                        .padding(top = 4.dp)
                    fontStatusLabel?.text = FontPack.statusText()
                }
            }
            actionCard("下载字体包", "只拉取 MiSans Regular/Medium/Bold（约 16MB）与 Unifont（约 5MB），仅需一次") {
                FontPack.download { s ->
                    fontStatusLabel?.text = s
                    Utils.toast(this@设置页, s, longDuration = true)
                }
            }
            actionCard("清除字体包", "删除已下载字体；进程内已替换的字体需重启应用恢复") {
                FontPack.clear()
                fontStatusLabel?.text = FontPack.statusText()
                Utils.toast(this@设置页, "已清除，重启应用后恢复系统字体")
            }
            colorPicker("主题色 Primary", "主强调色：按钮/开关/链接/高亮；跟随系统时留空自动取系统色", Settings.themeColor, MaterialColors.ACCENT, { M3.primary }, note)
            colorPicker("主色前景 On Primary", "强调色上的文字/图标，留空自动", Settings.themeOnPrimary, MaterialColors.ON, { M3.onPrimary }, note)
            colorPicker("主色容器 Primary Container", "次级强调表面，留空由主题色生成", Settings.themePrimaryContainer, MaterialColors.ACCENT, { M3.primaryContainer }, note)
            colorPicker("容器前景 On Primary Container", "主色容器上的文字，留空自动", Settings.themeOnPrimaryContainer, MaterialColors.ACCENT, { M3.onPrimaryContainer }, note)
            colorPicker("背景 Surface", "页面背景色", Settings.themeSurface, MaterialColors.SURFACE, { M3.surface }, note)
            colorPicker("卡片 Surface Container", "卡片/输入框/下沉表面背景", Settings.themeSurfaceContainer, MaterialColors.SURFACE, { M3.surfaceContainer }, note)
            colorPicker("凸起卡片 Surface Container High", "凸起卡片/按下态", Settings.themeSurfaceContainerHigh, MaterialColors.SURFACE, { M3.surfaceContainerHigh }, note)
            colorPicker("表面变体 Surface Variant", "次级表面", Settings.themeSurfaceVariant, MaterialColors.SURFACE, { M3.surfaceVariant }, note)
            colorPicker("前景文字 On Surface", "主要文字/图标", Settings.themeOnSurface, MaterialColors.ON, { M3.onSurface }, note)
            colorPicker("次要文字 On Surface Variant", "次要文字/未激活图标", Settings.themeOnSurfaceVariant, MaterialColors.ON, { M3.onSurfaceVariant }, note)
            colorPicker("提示文字 Tip", "副文本/提示", Settings.themeOnSurfaceTip, MaterialColors.ON, { M3.onSurfaceTip }, note)
            colorPicker("占位文字 Hint", "输入框占位文字", Settings.themeHint, MaterialColors.ON, { M3.hint }, note)
            colorPicker("描边 Outline", "边框/分隔线", Settings.themeOutline, MaterialColors.ON, { M3.outline }, note)
            colorPicker("描边变体 Outline Variant", "次级边框", Settings.themeOutlineVariant, MaterialColors.SURFACE, { M3.outlineVariant }, note)
            colorPicker("错误色 Error", "错误/危险操作/未读红标", Settings.themeError, MaterialColors.ERROR, { M3.error }, note)
            section("预设配色", "一键套用常用主题色：自动清除自定义颜色，容器/前景等派生色随主题色重新生成")
            card { card ->
                card.content {
                    val presets = arrayOf(
                        "#1976D2" to "经典蓝", "#3F51B5" to "靛蓝", "#6750A4" to "紫罗兰", "#00897B" to "青绿",
                        "#2E7D32" to "草绿", "#E86A17" to "橙", "#B3261E" to "红", "#C2185B" to "玫粉",
                    )
                    fun applyPreset(hex: String, name: String) {
                        Settings.themeTokens.forEach { it.value = "" }
                        Settings.themeColor.value = hex
                        Utils.toast(this@设置页, "已应用预设：$name（重进页面生效）")
                        showCategoryList()
                    }
                    for (r in 0 until 2) {
                        val row = LinearLayout(this@设置页).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(FILL, WRAP)
                        }
                        for (c in 0 until 4) {
                            val (hex, name) = presets[r * 4 + c]
                            val sw = View(this@设置页).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(M3.parseColor(hex, M3.primary))
                                    setStroke(2.dp, M3.onSurfaceVariant)
                                }
                                setOnClickListener { applyPreset(hex, name) }
                                contentDescription = name
                            }
                            row.addView(sw, LinearLayout.LayoutParams(34.dp, 34.dp).apply {
                                weight = 1f
                                gravity = Gravity.CENTER_HORIZONTAL
                                topMargin = 8.dp
                                bottomMargin = 8.dp
                            })
                        }
                        add(row)
                    }
                }
            }
            actionCard("恢复默认配色", "清除以上所有自定义颜色，恢复内置 M3 配色") {
                Settings.themeTokens.forEach { it.value = "" }
                Utils.toast(this@设置页, "已恢复默认配色，重进页面生效")
                showCategoryList()
            }
        },
        SettingsCategory("Material 化", "界面 Material 风格重设计开关") {
            actionCard("界面风格", "在 Material 与 原始 设计之间一键切换(选择后自动重启)") {
                startActivity(Intent(this@设置页, StyleChooserActivity::class.java))
            }
            switch("Material 聊天界面", "聊天输入框、回复/编辑横幅、消息发送中转圈应用 Material 风格(表面填充输入框、M3 符号图标、主题色发送键、M3 转圈)；关闭则保留原样。需开启“聊天页直接输入”，重进聊天页生效", Settings.materializeChat)
            switch("Material 附件菜单", "附件「+」菜单应用 Material 风格：M3 符号图标(主题色)、表面卡片与涟漪，与长按菜单一致；关闭则保留原图标(重进聊天页生效)", Settings.materialAttachmentMenu)
            switch("Material 长按菜单", "消息长按菜单应用 Material 风格(表面卡片+主题色)；关闭则用半透明深色行+白色文字", Settings.materialLongPressMenu)
            switch("Material 联系人页", "联系人页应用 Material 风格：顶栏含搜索/加好友/通知按钮，需同时开启「联系人分组」", Settings.materialContactsList)
            switch("Material 消息列表", "消息列表(会话列表，第1页)应用 Material 风格：深色背景、表面卡片行、M3 文字颜色(名称/时间/预览)、置顶图标改为 Material 图章、会话长按菜单改为 Material 列表(重进消息页生效)", Settings.materialChatList)
            switch("Material 动态页顶栏", "动态页(第3页)应用 Material 顶栏：把发布/通知/我的空间三个条目替换为紧凑图标按钮", Settings.materialQZoneBar)
            switch("完全重做空间(实验)", "用全新 Material 3 界面从头重做整个空间(QQ空间)前端：动态卡片(主页第3页与个人空间)、个人空间资料头部、评论/回复独立页(底部 M3 输入框可打字或语音)、以及发表说说改为单页直接输入。关闭则保留原生界面(仅上面的顶栏/点赞/小程序等就地微调)。默认关闭(重进空间生效)", Settings.materializeQzone)
            switch("使用增强资料卡", "用全新 Material 风格资料卡替换原资料页，额外显示年龄/生日/星座/地区/签名(从内核获取)，关闭则保留原页面(重进资料页生效)", Settings.useRichProfile)
            switch("Material 设置页面", "把自我页(主页第4页)、好友/群聊设置页重绘为全新 Material 风格(头部卡片+M3列表+主题色开关)，并把改群名/备注/昵称改为 Material 输入弹窗，不再打开全屏键盘页(重进对应页面生效)", Settings.useM3Settings)
            switch("Material 登录页", "把扫码登录页重绘为全新 Material 风格(QQMax-Ti 品牌+应用图标、加框二维码、M3 状态提示、扫码确认后显示登录账号头像与号码、刷新/手机号登录按钮)，并跳过首次启动的隐私协议确认页。原生登录逻辑不变(下次登录生效)", Settings.materializeLogin)
        },
        SettingsCategory("菜单自定义", "长按菜单与附件菜单的排序与显示") {
            section("自定义菜单", "拖动左侧手柄排序；拖到分隔线下方的项目将不再显示。例如可隐藏“翻译/截图/总结/多选”等。")
            actionCard("长按消息菜单", "排序/显示 回复·复制·撤回·转发·翻译·截图·总结·多选·删除 等") {
                startActivity(Intent(this@设置页, MenuEditorActivity::class.java)
                    .putExtra(MenuEditorActivity.EXTRA_MENU, MenuEditorActivity.MENU_LONGPRESS))
            }
            actionCard("附件「+」菜单", "排序/显示 表情·贴纸·艾特·相册·拍照·录像·音频·通话 等") {
                startActivity(Intent(this@设置页, MenuEditorActivity::class.java)
                    .putExtra(MenuEditorActivity.EXTRA_MENU, MenuEditorActivity.MENU_ATTACHMENT))
            }
            switch("多选按时间排序", "多选转发/截图等批量操作时，选中消息按聊天时间顺序处理；关闭则按点选先后顺序", Settings.multiSelectTimeOrder)
            switch("多选复制含发送者", "多选复制/部分复制时，每条消息前加上发送者名字(“名字：内容”)", Settings.multiSelectCopySender)
        },
        SettingsCategory("聊天输入", "输入框、发送方式与表情") {
            switch("聊天页直接输入", "在聊天页用输入框替换键盘键，有文字时麦克风键变发送键", Settings.inlineChatInput)
            switch("完全行内输入", "彻底不打开输入法页面：@、图片、回复、编辑、语音转文字都在输入框内完成。@xxx 与 [图片] 整体删除，回复/编辑在输入框上方显示横幅可点击取消(需开启“聊天页直接输入”)", Settings.fullInlineInput)
            switch("行内发送按钮", "输入页将发送键移到输入框右侧，左侧加关闭键取消发送", Settings.inlineSendButton)
            switch("行内表情按钮", "聊天页输入有文字时左侧显示表情键，点击收起键盘弹出表情选择器插入表情，点输入框恢复键盘", Settings.inlineEmojiButton)
            switch("记住输入草稿", "分会话记住未发送的输入框内容(文字、@、图片、回复目标)，离开聊天再返回时自动恢复，发送后清除(需开启“完全行内输入”)", Settings.rememberDraft)
            switch("表情选择器插入输入框", "从系统表情选择器选择表情/图片/GIF 时不立即发送，而是作为 [表情]/[图片] 插入输入框，可继续编辑一起发送(需开启“完全行内输入”)", Settings.emojiPickerToInput)
            switch("附件浮层", "用输入框左侧 + 键打开附件浮层，移除附件翻页；表情移入附件列表(重进聊天页生效)", Settings.attachmentOverlay)
            switch("单行输入", "输入框固定为单行显示", Settings.singleLineInput)
            switch("滚动时保持输入栏", "向上翻看历史消息时保持底部输入栏(及输入框)显示；关闭(默认)则像原生一样滚动时收起为上箭头(重进聊天页生效)", Settings.keepInputBarOnScroll)
            switch("输入键居中", "在聊天页面将输入键居中放置", Settings.swapCenterKeyboard)
            switch("隐藏语音按钮", "在聊天页隐藏语音(麦克风)按钮，所有输入模式下均生效", Settings.hideVoiceButton)
            switch("全员禁言隐藏输入栏", "群全员禁言且自己非群主/管理员时，隐藏底部输入栏，改为显示“全员禁言中”提示", Settings.muteHideInputBar)
            switch("群全员禁言开关", "在群聊设置中为群主/管理员显示「全员禁言」开关，可查看并切换当前禁言状态（需开启 Material 设置）", Settings.groupWholeMute)
            switch("图片随消息发送", "发送文字时一并发送已选图片", Settings.sendWithImage)
            switch("回复带艾特", "回复消息时自动艾特对方", Settings.replyWithAt)
            switch("气泡 +1 按钮", "最新一条与上一条内容相同时，在气泡上显示「+1」小按钮，点按可再次发送该内容；关闭则不显示", Settings.plusOneButton)
            // 双击消息的动作，三选一。底层仍是 double_speak / double_reply 两个开关(基座 app 读取)，
            // 这里把它们合并成一个下拉：无 / 回复 / 朗读。朗读优先于回复(两者都开时朗读生效)。
            selector("双击消息", "双击聊天消息时的动作", listOf("无", "回复", "朗读"),
                current = {
                    when {
                        Settings.doubleSpeak.value -> 2
                        Settings.doubleReply.value -> 1
                        else -> 0
                    }
                }) { which ->
                Settings.doubleSpeak.value = which == 2
                Settings.doubleReply.value = which == 1
            }
            slider("屏幕圆角直径", "在输入框左右各留出此宽度的空白，避免圆屏圆角裁切两侧按钮", Settings.screenCornerDiameter, min = 0f, max = 48f)
            textInput("语音键文字", "聊天页语音键上显示的文字", Settings.voiceBtnText)
        },
        SettingsCategory("聊天显示", "缩放、气泡、图片与背景") {
            slider("缩放倍数", "整体界面缩放，返回聊天页即时生效", Settings.scale)
            slider("聊天文本缩放", "聊天气泡内文字大小", Settings.chatScale)
            slider("图片最大高度", "聊天图片最大显示高度(占屏幕高度比例)，默认 0.5", Settings.picMaxHeightRatio, min = 0.3f, max = 1f)
            switch("长截图支持", "查看大图时，若图片高度≥屏幕2倍(长截图)，默认按屏幕宽度铺满并定位到顶部，可上下滚动阅读；双击循环缩放：1档整图高度·2档铺满宽度·3档进一步放大", Settings.longScreenshot)
            slider("气泡圆角半径", "聊天气泡远离发送方的一侧用此大圆角、发送侧用其 40% 小圆角（MD3 造型）；合并转发/聊天记录块与回复块仍为统一圆角(dp)", Settings.bubbleCornerRadius, min = 0f, max = 24f)
            colorPicker("我的气泡颜色", "留空为材料色（不透明主色容器）", Settings.bubbleColorSelf, MaterialColors.ACCENT,
                { M3.parseColorOrNull(Settings.bubbleColorSelf.value) ?: M3.tonalSolid })
            colorPicker("对方气泡颜色", "留空为材料色", Settings.bubbleColorOther, MaterialColors.SURFACE + MaterialColors.ACCENT,
                { M3.parseColorOrNull(Settings.bubbleColorOther.value) ?: M3.surfaceContainer })
            colorPicker("我的文字颜色", "留空为自动对比我的气泡色", Settings.textColorSelf, MaterialColors.ON,
                { M3.parseColorOrNull(Settings.textColorSelf.value) ?: M3.onColor(BubbleCorner.resolvedBubbleColor(1)) })
            colorPicker("对方文字颜色", "留空为自动对比对方气泡色", Settings.textColor, MaterialColors.ON,
                { M3.parseColorOrNull(Settings.textColor.value) ?: M3.onColor(BubbleCorner.resolvedBubbleColor(0)) })
            switch("聊天背景（实验性）", "启用聊天页背景图：每个会话可单独设置，另有全局背景兜底；设置时强制裁剪到屏幕比例、可拖动调整位置", Settings.chatBgEnabled)
            actionCard("用图片取色（莫奈）", "上传一张图片（不必是聊天背景），取其主色设为 UI 主题色；会清除自定义配色") {
                pickMonetImage()
            }
            switch("背景半透明", "开启后背景图按下方透明度值半透明显示，露出 M3 surface；关闭则背景图不透明。重进聊天页生效", Settings.chatBgTranslucent)
            switch("圆表适配（实验性）", "背景图按圆屏内切圆裁剪，四角露出 M3 surface（圆表安全区）；重进聊天页生效", Settings.md3eRound)
            chatBackgroundPicker()
            slider("背景图片透明度", "背景图自身半透明程度（越小越透，露出下方 M3 surface），需开启「背景半透明」；重进聊天页生效", Settings.chatBgAlpha, min = 0.3f, max = 1f)
            slider("背景变暗程度", "调暗背景图以便看清文字，重进聊天页生效", Settings.chatBgDarken, min = 0f, max = 0.9f)
        },
        SettingsCategory("群聊头像", "群聊中的头像与昵称") {
            switch("群聊显示头像", "在群聊消息中显示用户头像和两行昵称", Settings.showGroupAvatar)
            switch("自己也显示头像", "自己的消息也显示头像和两行昵称，与他人一致", Settings.showSelfAvatar)
            slider("头像大小", "群聊头像大小(相对昵称文字的倍数)，默认 2.5", Settings.avatarSizeScale, min = 1.5f, max = 6f)
            switch("合并连续消息头", "同一人连发多条时，只在第一条显示头像和昵称", Settings.hideRepeatedSender)
            switch("替换发送者名字", "用解析到的群名片/备注/昵称替换群消息发送者名字；关闭(默认)则保留原生名字。群主/管理员/头衔标签不受此开关影响，始终显示", Settings.replaceGroupNick)
            switch("群聊成员等级", "在群聊消息昵称旁显示成员等级（异步拉取成员信息，默认关闭）", Settings.showMemberLevel)
            actionCard("清除头像缓存", "删除已缓存的头像，重进聊天页将重新下载最新头像") {
                val n = GroupAvatarHook.clearAvatarCache()
                Utils.toast(this@设置页, "已清除 $n 个头像缓存")
            }
        },
        SettingsCategory("标题栏与未读", "聊天顶部标题栏") {
            switch("富标题栏", "聊天顶部显示返回键、其它会话未读数、群成员数与群名(重进聊天页生效)", Settings.enableTitlebar)
            switch("仅聊天页显示标题栏", "标题栏作为浮层叠在聊天列表上方，翻到聊天设置页时自动隐藏；关闭则叠在所有页之上(重进聊天页生效)", Settings.titlebarChatOnly)
            switch("标题栏显示未读数", "在富标题栏显示其它会话的未读数红标(重进聊天页生效)", Settings.titlebarShowUnread)
            switch("未读数浮在聊天左上角", "其它会话未读数红标浮在聊天页左上角而非标题栏内，无标题栏也可用(重进聊天页生效)", Settings.floatUnreadInChat)
            switch("逐条跳转重要消息", "“↑X条新消息”按钮依次跳到 @我/回复/新文件/新公告 等重要消息(自下而上)，到达后自动切换下一条，最后停在第一条未读", Settings.chatImportantJump)
            switch("打字时隐藏标题栏", "弹出软键盘输入时隐藏标题栏腾出屏幕空间，键盘收起后自动恢复", Settings.hideTitlebarWhenTyping)
            switch("标题栏名称滚动", "标题栏名称过长时滚动显示，而非省略号截断(重进聊天页生效)", Settings.titlebarMarquee)
            switch("标题栏渐变背景", "标题栏背景向下渐隐(聊天内容滚上时逐渐隐没在栏后)；关闭则为纯色不透明填充(重进聊天页生效)", Settings.titlebarGradient)
            slider("标题栏高度", "富标题栏高度(dp)，默认 16", Settings.titlebarHeight, min = 16f, max = 32f)
            slider("标题栏左右边距", "富标题栏与未读红标左右留出的空白(dp)，避免圆屏圆角裁切", Settings.titlebarSideMargin, min = 0f, max = 48f)
        },
        SettingsCategory("主页导航", "主页底部翻页导航栏") {
            switch("自定义导航栏", "用重绘的导航栏替换原生翻页指示器；关闭则保留原生样式，以下选项不生效", Settings.mainNavCustom)
            switch("导航在底部", "主页(会话列表)翻页导航移到屏幕底部", Settings.bottomMainNav)
            switch("文字标签导航", "手机风格底部导航：图标下方显示页面名称(消息/联系人/动态/我)，适合在手机上使用。图标大小与文字均随“导航高度”缩放，开启后显示所有页面图标", Settings.mainNavLabels)
            slider("导航高度", "主页导航图标/栏高度(dp)，默认 16；文字标签导航开启时同样控制图标与文字大小", Settings.mainNavHeight, min = 12f, max = 64f)
            switch("方形铺开", "导航图标在整行均匀铺开，而非聚在中间", Settings.mainNavSquare)
            switch("显示所有页面图标", "每个页面都显示图标，当前页蓝色高亮；关闭则只显示当前页图标、其余为圆点", Settings.mainNavAllIcons)
            switch("显示未读数", "在导航各页面图标上显示未读数红标(设置页除外)：消息页为总未读，联系人页为好友+群通知数", Settings.mainNavUnread)
            switch("点击当前页跳转未读", "已在某页时再次点击该页图标：消息页把下一条未读会话滚到顶部(循环)，联系人/动态页打开通知页面。需开启“显示未读数”", Settings.mainNavUnreadJump)
        },
        SettingsCategory("导航与滚动", "翻页、返回与表冠") {
            switch("显示系统状态栏", "显示系统状态栏(时间/电量)，内容自动下移避让(适配安卓15+全面屏)；关闭则全屏隐藏状态栏。切换后需重启应用生效", Settings.showStatusBar)
            switch("返回先回首页", "不在首页时按返回先滑回第一页，已在首页才退出", Settings.backToFirstPage)
            switch("回复跳转加载全部", "点击回复跳转到源消息时不限制翻页次数，一直向上加载直到找到或到达历史顶端(较旧的源消息也能定位，可能较慢)", Settings.replyFullSearch)
            switch("屏蔽返回键", "用于把右滑当作返回的手表（如米兔）", Settings.blockBack)
            switch("屏蔽应用内右滑返回", "关闭 QQ 应用内右滑返回上一页的手势", Settings.disableSwipeBack)
            switch("平滑表冠滚动", "表冠滚动没有动画时开启", Settings.enableSmoothScroll)
            slider("表冠滚动速度", "表冠滚动距离倍率，默认 1.0", Settings.encoderScrollSpeed, min = 0.3f, max = 4f)
        },
        SettingsCategory("通知与提醒", "消息通知、声音与震动") {
            switch("允许通知", "允许显示消息通知", Settings.allowNotification)
            switch("常驻通知", "保留常驻通知（更耗电）", Settings.residentNotification)
            selector("提醒声音", "新消息提示音：关闭 / 应用内音效 / 系统通知音", Settings.notifySoundMode,
                listOf("关闭", "应用内", "系统"))
            selector("提醒震动", "新消息震动：关闭 / 应用内模式 / 系统模式", Settings.notifyVibrateMode,
                listOf("关闭", "应用内", "系统"))
        },
        SettingsCategory("通话", "语音/视频通话") {
            section("通话", "音视频通话的通知、蓝牙与界面。部分选项重进通话生效。")
            switch("来电通知修复", "确保来电有通知：QQ 只在后台且未被机型黑名单时才弹来电，且常失败。开启后由 QQMax-Ti 自行弹出高优先级来电通知，带来电人头像/昵称与「接听/拒绝」按钮", Settings.callNotifyFix)
            switch("使用全屏来电", "来电时直接全屏拉起原生接听界面(类似电话来电)；关闭则只显示带按钮的横幅通知。需开启「来电通知修复」，并授予下方悬浮窗权限(否则安卓10+无法从后台全屏拉起)", Settings.callFullScreenIntent)
            actionCard("授予悬浮窗权限", "允许「全屏来电」在锁屏/后台直接拉起接听界面。未授予时仅显示横幅通知") {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 23 &&
                        !android.provider.Settings.canDrawOverlays(this@设置页)
                    ) {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    } else {
                        Utils.toast(this@设置页, "已授予悬浮窗权限")
                    }
                }.onFailure { Utils.log("overlay perm request failed: $it") }
            }
            switch("蓝牙耳机路由", "通话音频自动路由到已连接的蓝牙耳机(SCO/通信设备)，并在通话界面显示输出切换(蓝牙/扬声器/听筒)；关闭则保留 QQ 仅扬声器/听筒的路由", Settings.callBluetoothRoute)
            switch("无摄像头也可视频通话", "没有摄像头的手表也能发起视频通话(至少看到对方)。QQ 会以「当前设备不支持」在两处拦截，开启后绕过并发起只收不发的视频通话(隐藏本地摄像头控制)", Settings.callCameralessVideo)
            switch("全新通话界面", "把来电与通话中界面重做为全新的 Material 3 界面(内嵌原生视频画面，沿用 QQ 的通话服务/流程)；关闭则保留原生通话界面。默认关闭，重进通话生效", Settings.materializeCall)
        },
        SettingsCategory("联系人", "联系人页面") {
            switch("联系人分组", "联系人页用「好友」「群聊」标题分组，通知拆成好友/群两项各带数量，去掉群行末尾图标", Settings.contactSections)
            switch("动态顶栏按钮分散排列", "三个图标按钮平均分布到顶栏全宽(分散)；关闭则紧靠居中排列，适合圆形表盘", Settings.qzoneBarSpread)
            switch("单视频帖子内联播放", "动态里只含一个视频的帖子，点击直接在列表内播放(再次点击暂停)，不再打开全屏播放器", Settings.qzoneInlineVideo)
            switch("小程序卡片渲染", "动态里的小程序(轻应用)分享不再显示「请在手机QQ查看」，改为抓取分享页解析出真实的名称/图标/简介并渲染成卡片(联网)", Settings.qzoneMiniAppCard)
            switch("动态正文折叠", "重做空间里超长的说说正文最多显示5行，点「查看全文」展开；关闭则始终显示全文", Settings.qzoneTruncatePost)
            switch("多图折叠为两张", "重做空间里多图说说只显示两张方图，第二张变暗显示「+N」；关闭则按三列方图网格显示全部", Settings.qzoneTruncateImages)
            switch("资料页姓名多行可复制", "好友/群资料设置页及成员资料卡的名称过长时换行显示，长按名称可复制；设置页内QQ号与昵称分两行、可分别长按复制(重进资料页生效)", Settings.profileNameMultiline)
        },
        SettingsCategory("在线状态", "显示好友/群成员的在线状态") {
            switch("消息列表在线圆点", "会话列表中，单聊(好友)会话的头像左上角显示彩色在线圆点(在线绿色/离线灰色)", Settings.onlineStatusMainList)
            switch("联系人列表在线描述", "好友列表中，在每个好友名字后附上在线状态描述(如「手机在线」)", Settings.onlineStatusContactList)
            switch("聊天标题栏在线状态", "单聊(好友)聊天页的富标题栏中，名字旁显示对方在线状态(需开启标题栏)", Settings.onlineStatusTitlebar)
            switch("资料卡在线状态", "好友/群成员资料卡中显示在线状态描述", Settings.onlineStatusProfile)
        },
        SettingsCategory("相机与媒体", "拍照、相册与音频") {
            switch("使用应用内相机", "开启后拍照/录像都用应用内相机，关闭后改用系统/第三方相机", Settings.useInAppCamera)
            switch("相册按拍摄时间排序", "图片选择器按拍摄时间排序，关闭则按文件修改时间(默认)", Settings.gallerySortByDateTaken)
            switch("图片/视频快速发送", "单击相册图片/视频立即发送。关闭后相册始终为多选模式，单击勾选(可只选一个)再点发送", Settings.galleryQuickSend)
            switch("发送前预览单图", "发送单张图片时先进入预览，可选择直接发送或先编辑(旋转/翻转/裁剪/涂鸦/文字/贴纸/调整)", Settings.editSingleImageBeforeSend)
            switch("使用系统图片选择器", "相册改用系统图片选择器/文件选择器，无需相册权限，可多选，修复部分设备问题", Settings.useSystemImagePicker)
            switch("使用系统音频选择器", "音频文件改用系统文件选择器，关闭则用应用内音频浏览器(列出本机音频文件)", Settings.useSystemAudioPicker)
        },
        SettingsCategory("链接", "消息中的网址") {
            switch("点击链接确认", "点击消息中的链接时，弹窗询问是否用浏览器打开", Settings.confirmOpenLink)
            switch("识别无前缀链接", "同时识别不带 http(s):// 的网址，如 example.com/x", Settings.wideUrlMatch)
            switch("识别号码", "把消息中的 6-15 位数字(QQ号/群号)变为可点击，点击可搜索好友/群", Settings.parseNumber)
            switch("识别@成员", "群聊中把 @成员 及灰条提示中的成员名变为可点击，点击打开其资料卡(同点头像/昵称)", Settings.parseAtMember)
            switch("高亮@我", "群聊中 @我自己 的提及用错误色高亮，使之更醒目(需开启识别@成员)", Settings.highlightSelfMention)
            switch("链接预览", "消息含链接时尝试解析网站图标、标题与简介，显示在消息下方", Settings.enableLinkPreview)
            colorPicker("链接颜色", "可点击链接/号码/@成员的文字颜色，留空为默认", Settings.linkColor, MaterialColors.ACCENT,
                { M3.parseColorOrNull(Settings.linkColor.value) ?: M3.primary })
        },
        SettingsCategory("翻译", "消息翻译与发送翻译") {
            val translateProviders = listOf("custom", "baidu", "mymemory", "ms", "google")
            val translateProviderNames = listOf("自定义AI", "百度翻译", "MyMemory(免费)", "微软翻译", "谷歌(海外)")
            selector(
                "翻译服务", "自定义AI=复用「聊天总结」的 API Key（默认，国内推荐）；百度翻译=需下方 APP ID+密钥；MyMemory=免费免 Key；微软翻译=Azure 需 Key；谷歌=海外",
                translateProviderNames,
                current = { translateProviders.indexOf(Settings.translateProvider.value).coerceAtLeast(0) },
                onPick = { which -> Settings.translateProvider.value = translateProviders[which] },
            )
            textInput("百度翻译 APP ID", "百度翻译开放平台的 APP ID，仅「百度翻译」服务使用", Settings.translateBaiduAppId)
            textInput("百度翻译密钥", "百度翻译开放平台的密钥（用于签名），仅「百度翻译」服务使用", Settings.translateBaiduKey)
            textInput("微软翻译 Key", "Azure Translator 密钥(Ocp-Apim-Subscription-Key)，仅「微软翻译」服务使用", Settings.translateMsKey)
            textInput("微软区域(可选)", "Azure 区域标识，如 eastasia；中国区 Key 必填", Settings.translateMsRegion)
            langSelector("查看语言", "把对方消息翻译成的目标语言", Settings.translateViewLang)
            switch("对方消息原地替换", "翻译对方消息时直接用译文替换原文；关闭(默认)则保留原文，在其下方用分隔线显示译文", Settings.translateReplaceInPlace)
            switch("显示“翻译全部”开关", "在好友/群聊设置页显示“翻译全部消息”开关，可逐会话开启：开启后该会话内所有可见文字消息自动翻译成查看语言(重进设置页生效)", Settings.translateShowAllSwitch)
            switch("翻译全部时包含自己", "“翻译全部消息”开启时是否也翻译自己发送的消息；关闭(默认)只翻译对方消息(长按翻译不受影响)", Settings.translateOwnMessages)
            switch("长按发送键翻译", "长按发送键把输入框内容翻译成发送语言(不发送)，翻译过程中输入框显示加载提示", Settings.translateSendButton)
            langSelector("发送语言", "长按发送键时把输入内容翻译成的目标语言", Settings.translateSendLang)
        },
        SettingsCategory("聊天截图", "把选中的消息渲染成长图") {
            section("聊天截图", "在「菜单自定义 › 长按消息菜单」可显示/隐藏“截图”项。以下选项调整渲染出的长图样式。")
            switch("渲染标题栏", "截图顶部显示群名/对方名标题栏(仅群名，无圆角/未读)；关闭则不渲染顶部标题", Settings.screenshotTitlebar)
            switch("渲染输入框", "截图底部显示空输入框装饰；关闭则不渲染底部输入框", Settings.screenshotInputBar)
            switch("自己显示在左侧", "把自己发送的消息也渲染在左侧(第三方视角)；默认关闭(自己在右)", Settings.screenshotSelfAsOther)
            switch("显示昵称与头像", "截图中显示真实昵称与头像；关闭则匿名化为 A/B/C 与随机色字母头像", Settings.screenshotShowIdentity)
            switch("生成水印", "在截图底部添加“由 QQMax-Ti 生成”水印", Settings.screenshotWatermark)
        },
        SettingsCategory("聊天总结", "用 AI 总结聊天记录") {
            section("聊天总结", "在「菜单自定义 › 长按消息菜单」可显示/隐藏“总结”项。以下选项调整总结的风格与语言。")
            switch("总结未读消息", "未读超过 20 条时，在“↑X条新消息”气泡下方显示“总结未读”按钮，从第一条未读总结到末尾", Settings.summarizeUnreadButton)
            selector("总结风格", "要点(Markdown列表)/一句话/详细", Settings.summarizeStyle, listOf("要点", "一句话", "详细"))
            summarizeLangSelector("总结语言", "总结输出的语言；自动=跟随会话本身的语言。过往总结记录可在好友/群聊设置页的“总结历史”查看")
            section("自定义服务", "默认走内置 Onyx 服务（每日限量）。填写 API Key 后改为直连你自己的 OpenAI 兼容接口——内置服务报 502 时用它绕开。")
            textInput("API Key", "留空=内置服务；填写后直连自定义接口（如 DeepSeek / OpenAI 的 Key）", Settings.summarizeApiKey)
            textInput("接口地址", "OpenAI 兼容接口地址，默认 DeepSeek（自动补全 /chat/completions）", Settings.summarizeApiBase)
            textInput("模型", "使用的模型名，留空用默认", Settings.summarizeApiModel)
        },
        SettingsCategory("关于与更新", "版本更新") {
            actionCard("关于", "版本信息与致谢") {
                showAbout()
            }
        },
        SettingsCategory("调试", "诊断与日志") {
            switch("卡死监控", "监测主线程卡死并弹出报告，崩溃捕获始终开启。手表休眠可能误报，可关闭(重启应用生效)", Settings.watchdogEnabled)
            switch("启用日志", "记录调试日志到文件用于排查问题，发行版默认关闭", Settings.enableLog)
            actionCard("保存日志到下载", "将调试日志文件保存到下载文件夹") {
                val saved = Utils.saveLogToDownloads()
                Utils.toast(
                    this@设置页,
                    when {
                        saved == null -> "保存失败(无日志文件?)"
                        saved.inDownloads -> "已保存到下载文件夹:\n${saved.location}"
                        else -> "无法写入下载，已保存到:\n${saved.location}"
                    },
                    longDuration = true
                )
            }
            actionCard("导出设置", "将本页所有设置导出为文件保存到下载文件夹，可选 JSON 或 XML") {
                exportSettings()
            }
            actionCard("导入设置", "从文件恢复本页设置(自动识别 JSON / XML)，部分需重启应用生效") {
                importSettings()
            }
            actionCard("调试菜单", "查看设备信息与调试日志，可复制/分享/保存") {
                startActivity(Intent(this@设置页, DebugActivity::class.java))
            }
            actionCard("崩溃 / 卡死测试", "主动触发崩溃或卡死，验证报告功能") {
                startActivity(Intent(this@设置页, WatchdogTestActivity::class.java))
            }
        },
    )

    private fun GroupScopeFix.section(title: String, subtitle: String) {
        add<TextView>()
            .text(title)
            .textSize(15f)
            .textColor(ACCENT)
            .padding(left = 4.dp, top = 14.dp, right = 4.dp, bottom = 0.dp)
        add<TextView>()
            .text(subtitle)
            .textSize(10f)
            .textColor(M3.hint)
            .padding(left = 4.dp, top = 0.dp, right = 4.dp, bottom = 6.dp)
    }

    private fun GroupScopeFix.switch(
        title: String,
        desc: String,
        pref: Pref<Boolean>,
        onChange: ((Boolean) -> Unit)? = null,
    ) = card { card ->
        card.content {
            titleColumn(title, desc).weight(1f)
            // Our own Material switch (M3Switch), themed from the M3 accent — replaces the base app's
            // native toggle so it follows the user's theme color.
            val sw = M3Switch(this@设置页)
            sw.setChecked(pref.value, notify = false)
            sw.onChange = { pref.value = it; onChange?.invoke(it) }
            add(sw)
        }
    }

    /** A tappable card row with a ›-style affordance; runs [onTap] when pressed. */
    private fun GroupScopeFix.actionCard(
        title: String,
        desc: String,
        onTap: () -> Unit
    ) = card { card ->
        card.content {
            titleColumn(title, desc).weight(1f)
            add<TextView>()
                .textColor(ACCENT)
                .gravity(Gravity.CENTER_VERTICAL)
                .leadingSymbol(MaterialSymbols.chevron_right, ACCENT, sizeDp = 18, gap = 0)
        }
        card.rippleTouch()
        card.onClick { onTap() }
    }

    /**
     * Dropdown-style row: shows the current option (with a ▾ affordance) on the right; tapping opens
     * a hand-built dark option picker. Used for multi-state settings (e.g. 关闭 / 应用内 / 系统).
     * Built entirely with the view DSL — the app's bundled appcompat is far too old for a Material
     * dialog to look right.
     */
    private fun GroupScopeFix.selector(
        title: String,
        desc: String,
        pref: Pref<Int>,
        options: List<String>
    ) = selector(title, desc, options, { pref.value }) { pref.value = it }

    /**
     * Dropdown row backed by an explicit getter/setter instead of a single [Pref]. Lets one dropdown
     * drive a derived value — e.g. the 双击操作 row maps three options onto two separate boolean prefs.
     */
    private fun GroupScopeFix.selector(
        title: String,
        desc: String,
        options: List<String>,
        current: () -> Int,
        onPick: (Int) -> Unit,
    ) = card { card ->
        lateinit var valueLabel: TextView
        card.content {
            titleColumn(title, desc).weight(1f)
            valueLabel = add<TextView>()
                .text(selectorLabel(options, current()))
                .textSize(14f)
                .textColor(ACCENT)
                .gravity(Gravity.CENTER_VERTICAL)
            // Trailing ▾ affordance as a real Material drop-down icon (persists across text updates).
            valueLabel.setCompoundDrawables(
                null, null,
                MaterialSymbol(MaterialSymbols.arrow_drop_down, ACCENT).apply { setBounds(0, 0, 18.dp, 18.dp) },
                null,
            )
            valueLabel.compoundDrawablePadding = 2.dp
        }
        card.rippleTouch()
        card.onClick {
            showOptionPicker(title, options, current()) { which ->
                onPick(which)
                valueLabel.text = selectorLabel(options, current())
            }
        }
    }

    /** Language picker backed by a [StringPref] holding the API language code (maps code ↔ index). */
    private fun GroupScopeFix.langSelector(
        title: String,
        desc: String,
        pref: Pref<String>,
    ) {
        val codes = momoi.mod.qqpro.hook.translate.Translator.TARGETS.map { it.first }
        val names = momoi.mod.qqpro.hook.translate.Translator.TARGETS.map { it.second }
        selector(title, desc, names,
            current = { codes.indexOf(pref.value).coerceAtLeast(0) }) { which ->
            pref.value = codes[which]
        }
    }

    /** Language picker for summaries: same codes as [langSelector] but with a leading "自动" (auto). */
    private fun GroupScopeFix.summarizeLangSelector(title: String, desc: String) {
        val codes = listOf("auto") + momoi.mod.qqpro.hook.translate.Translator.TARGETS.map { it.first }
        val names = listOf("自动") + momoi.mod.qqpro.hook.translate.Translator.TARGETS.map { it.second }
        selector(title, desc, names,
            current = { codes.indexOf(Settings.summarizeLang.value).coerceAtLeast(0) }) { which ->
            Settings.summarizeLang.value = codes[which]
        }
    }

    private fun selectorLabel(options: List<String>, index: Int) =
        options.getOrElse(index) { options.first() }

    /** A hand-drawn dark single-choice popup styled like the rest of the settings cards. */
    private fun showOptionPicker(
        title: String,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this)
            .vertical()
            .padding(top = 16.dp, bottom = 8.dp)
        panel.background(GradientDrawable().apply {
            setColor(M3.surfaceContainerHigh)
            cornerRadius = 22.dp.toFloat()
        })

        panel.content {
            add<TextView>()
                .text(title)
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(left = 16.dp, right = 16.dp, bottom = 12.dp)

            options.forEachIndexed { index, label ->
                val isSel = index == selected
                val row = add<LinearLayout>()
                    .width(FILL)
                    .padding(left = 16.dp, top = 13.dp, right = 16.dp, bottom = 13.dp)
                row.gravity(Gravity.CENTER_VERTICAL)
                row.margin(left = 8.dp, right = 8.dp, top = 2.dp, bottom = 2.dp)
                if (isSel) {
                    row.background(GradientDrawable().apply {
                        setColor(M3.primaryContainer)
                        cornerRadius = 14.dp.toFloat()
                    })
                }
                row.content {
                    // Radio indicator: filled accent disc when selected, hollow grey ring otherwise.
                    add<View>()
                        .size(16.dp)
                        .background(GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (isSel) {
                                setColor(ACCENT)
                            } else {
                                setColor(0x00_000000)
                                setStroke(2.dp, M3.outline)
                            }
                        })
                    add<TextView>()
                        .text(label)
                        .textSize(14f)
                        .textColor(if (isSel) ACCENT else M3.onSurface)
                        .margin(left = 14.dp)
                }
                row.rippleTouch(clip = isSel)
                row.onClick {
                    onPick(index)
                    dialog.dismiss()
                }
            }
        }

        // Wrap the panel in a ScrollView so a long option list (or a short screen) can scroll instead
        // of being clipped. The rounded background stays on the panel; the ScrollView is transparent.
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            // Cap the dialog height so the ScrollView gets a bounded viewport (an unbounded WRAP_CONTENT
            // dialog measures to the full content height and just clips off-screen, never scrolling).
            val maxH = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val h = if (panel.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            setLayout(w, h)
        }
        dialog.show()
    }

    /**
     * A small numeric-input popup for a slider value: tap the value chip to type an exact number
     * (clamped to [min]..[max]) instead of scrubbing the seek bar. Styled like [showOptionPicker].
     */
    private fun showNumberInput(
        title: String,
        current: Float,
        min: Float,
        max: Float,
        onValue: (Float) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this)
            .vertical()
            .padding(20.dp)
        panel.background(GradientDrawable().apply {
            setColor(M3.surfaceContainerHigh)
            cornerRadius = 22.dp.toFloat()
        })

        lateinit var edit: EditText
        val confirm = {
            val v = edit.text?.toString()?.trim()?.toFloatOrNull()
            if (v == null) {
                Utils.toast(this@设置页, "请输入有效数字")
            } else {
                onValue(v.coerceIn(min, max))
                dialog.dismiss()
            }
        }
        panel.content {
            add<TextView>()
                .text(title)
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(bottom = 4.dp)
            add<TextView>()
                .text("范围 ${format(min)} ~ ${format(max)}")
                .textSize(11f)
                .textColor(M3.onSurfaceVariant)
                .gravity(Gravity.CENTER)
                .padding(bottom = 14.dp)

            edit = add<EditText>().width(FILL)
            edit.apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                isSingleLine = true
                // Pressing the keyboard's done/enter confirms — so the value commits even when the
                // soft keyboard covers the buttons on the short watch screen.
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, _, _ -> confirm(); true }
                setText(format(current))
                setSelection(text.length)
                textSize = 18f
                setTextColor(ACCENT)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(M3.surfaceContainer)
                    cornerRadius = 12.dp.toFloat()
                }
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            }

            add<LinearLayout>()
                .width(FILL)
                .padding(top = 16.dp)
                .content {
                    fun textButton(label: String, fg: Int, onTap: () -> Unit) {
                        val b = add<TextView>()
                            .text(label)
                            .textSize(14f)
                            .textColor(fg)
                            .gravity(Gravity.CENTER)
                            .padding(top = 11.dp, bottom = 11.dp)
                        (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
                        b.margin(left = 4.dp, right = 4.dp)
                        b.background(GradientDrawable().apply {
                            setColor(M3.surfaceContainer)
                            cornerRadius = 14.dp.toFloat()
                        })
                        b.rippleTouch()
                        b.onClick(onTap)
                    }
                    textButton("取消", M3.onSurfaceVariant) { dialog.dismiss() }
                    textButton("确定", ACCENT) { confirm() }
                }
        }

        // Wrap in a ScrollView so that when the soft keyboard shrinks the window (ADJUST_RESIZE), the
        // 取消/确定 buttons stay reachable by scrolling instead of being clipped off the bottom on the
        // 480x480 watch screen. The rounded background stays on the panel; the ScrollView is transparent.
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            val maxH = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val h = if (panel.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            setLayout(w, h)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()
        edit.requestFocus()
    }

    /**
     * Show the About page (version + credits + 检查更新) as a full-screen raw [Dialog]. The settings
     * activity is a plain [android.app.Activity] (no androidx FragmentManager), so it can't show the
     * [AboutFragment] DialogFragment — instead it hosts the shared [buildAboutView] content directly,
     * with a 关闭 button since there's no swipe-back wrapper here.
     */
    private fun showAbout() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = buildAboutView(
            this,
            onClose = { dialog.dismiss() },
        )
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(M3.surface))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        dialog.show()
    }

    private fun updateBgStatus() {
        bgStatusLabel?.text = buildString {
            append("总开关：${if (ChatBackground.enabled()) "开" else "关"} · ")
            append("全局：${if (ChatBackground.globalSet()) "已设置" else "未设置"} · ")
            val peer = CurrentContact.peerUid
            append("当前会话：${if (peer.isNotBlank() && ChatBackground.peerSet(peer)) "已设置" else "未设置"}")
        }
    }

    private fun GroupScopeFix.chatBackgroundPicker() = card { card ->
        card.vertical()
        card.content {
            titleColumn("聊天背景图片（实验性）", "每群独立背景 + 全局兜底；选图后强制裁剪到屏幕比例，可拖动/缩放调整位置").width(FILL)
            bgStatusLabel = add<TextView>()
                .textSize(11f)
                .textColor(M3.onSurfaceVariant)
                .padding(top = 4.dp)
            updateBgStatus()
            add<LinearLayout>()
                .width(FILL)
                .padding(top = 8.dp)
                .content {
                    pillButton("当前群背景", ACCENT) {
                        if (CurrentContact.peerUid.isBlank()) {
                            Utils.toast(this@设置页, "请先进入一个聊天再设置")
                        } else {
                            pendingBgPeer = CurrentContact.peerUid
                            pickChatBackground()
                        }
                    }
                        .weight(1f)
                        .margin(right = 4.dp)
                    pillButton("清当前群", M3.error) {
                        if (CurrentContact.peerUid.isBlank()) {
                            Utils.toast(this@设置页, "请先进入一个聊天再清除")
                        } else {
                            ChatBackground.clear(CurrentContact.peerUid)
                            updateBgStatus()
                            Utils.toast(this@设置页, "已清除当前会话背景")
                        }
                    }.weight(1f).margin(left = 4.dp)
                }
            add<LinearLayout>()
                .width(FILL)
                .padding(top = 8.dp)
                .content {
                    pillButton("全局背景", ACCENT) {
                        pendingBgPeer = ""
                        pickChatBackground()
                    }
                        .weight(1f)
                        .margin(right = 4.dp)
                    pillButton("清全局", M3.error) {
                        ChatBackground.clear(null)
                        updateBgStatus()
                        Utils.toast(this@设置页, "已清除全局背景")
                    }.weight(1f).margin(left = 4.dp)
                }
        }
    }

    /**
     * A color-setting row: title/description on the left, the current hex (or "默认") and a round
     * preview chip on the right. Tapping opens the full Material [showColorPicker] (HSV wheel +
     * brightness, preset swatches, hex field). [current] returns the live effective color (for the
     * chip + the wheel's starting point when [pref] is blank); [presets] are the offered swatches;
     * [note] (if set) is toast on change.
     */
    private fun GroupScopeFix.colorPicker(
        title: String,
        desc: String,
        pref: Pref<String>,
        presets: List<String>,
        current: () -> Int,
        note: String? = null,
    ) = card { card ->
        lateinit var chip: View
        lateinit var valueLabel: TextView
        fun refresh() {
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(current())
                setStroke(2.dp, M3.outline)
            }
            valueLabel.text = if (pref.value.isBlank()) "默认" else pref.value.trim()
        }
        card.content {
            titleColumn(title, desc).weight(1f)
            valueLabel = add<TextView>()
                .textSize(12f)
                .textColor(M3.onSurfaceVariant)
                .gravity(Gravity.CENTER_VERTICAL)
            chip = add<View>()
                .size(24.dp)
                .margin(left = 10.dp)
        }
        refresh()
        card.rippleTouch()
        card.onClick {
            showColorPicker(this@设置页, title, pref.value, current(), allowDefault = true, presets = presets) { picked ->
                pref.value = picked
                refresh()
                if (note != null) Utils.toast(this@设置页, note)
            }
        }
    }

    private fun GroupScopeFix.pillButton(
        label: String,
        color: Int,
        onTap: () -> Unit
    ): TextView {
        val btn = add<TextView>()
            .text(label)
            .textSize(13f)
            .textColor(M3.onColor(color)) // contrast against the pill's own fill (accent or error)
            .gravity(Gravity.CENTER)
            .padding(top = 8.dp, bottom = 8.dp)
        btn.background(GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18.dp.toFloat()
        })
        btn.rippleTouch()
        btn.onClick(onTap)
        return btn
    }

    private fun pickChatBackground() {
        try {
            startActivityForResult(Intent(this, ImagePickerActivity::class.java), REQ_PICK_CHAT_BG)
        } catch (e: Exception) {
            Utils.log("pickChatBackground failed: ${e.javaClass.simpleName}: ${e.message}")
            Utils.toast(this, "无法打开图片选择器")
        }
    }

    /** 莫奈取色：单独上传一张图片（与背景图无关），取其主色设为 UI 主题色。 */
    private fun pickMonetImage() {
        try {
            startActivityForResult(
                Intent(this, ImagePickerActivity::class.java)
                    .putExtra(ImagePickerActivity.EXTRA_TITLE, "选择取色图片"),
                REQ_PICK_MONET_IMG,
            )
        } catch (e: Exception) {
            Utils.log("pickMonetImage failed: ${e.javaClass.simpleName}: ${e.message}")
            Utils.toast(this, "无法打开图片选择器")
        }
    }

    /** 从本地图片路径提取主色并应用为 UI 主题色（后台解码，主线程应用）。 */
    private fun applyMonetFromPath(path: String) {
        thread {
            val color = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
                val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                val c = ChatBackground.monetColor(bmp)
                bmp.recycle()
                c
            }.getOrNull()
            runOnUi {
                if (color == null) {
                    Utils.toast(this@设置页, "图片解码失败")
                } else {
                    Settings.themeTokens.forEach { it.value = "" }
                    Settings.themeColor.value = "#%06X".format(color and 0xFFFFFF)
                    Utils.toast(this@设置页, "已应用图片主色（重进页面生效）")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_MONET_IMG -> {
                if (resultCode == Activity.RESULT_OK) {
                    val path = data?.getStringExtra(ImagePickerActivity.EXTRA_PATH)
                    if (!path.isNullOrBlank()) applyMonetFromPath(path)
                    else Utils.toast(this, "未选择图片")
                }
            }
            REQ_PICK_CHAT_BG -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    // 进入裁剪页：强制裁到屏幕比例，可拖动/缩放调整位置；确定后保存并（可选）莫奈取色。
                    val peer = pendingBgPeer ?: ""
                    val intent = Intent(this, CropBackgroundActivity::class.java).apply {
                        putExtra(CropBackgroundActivity.EXTRA_URI, uri)
                        putExtra(CropBackgroundActivity.EXTRA_PEER, peer)
                    }
                    runCatching { startActivityForResult(intent, REQ_CROP_CHAT_BG) }
                        .onFailure { Utils.toast(this, "无法打开裁剪页: ${it.message}") }
                } else {
                    Utils.toast(this, "未选择图片")
                }
                updateBgStatus()
            }
            REQ_CROP_CHAT_BG -> {
                if (resultCode == Activity.RESULT_OK) updateBgStatus()
            }
            REQ_IMPORT_SETTINGS -> {
                val uri = data?.data
                if (resultCode != Activity.RESULT_OK || uri == null) return
                runCatching {
                    contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }.onSuccess { text ->
                    runCatching { SettingsBackup.import(text) }
                        .onSuccess { n -> Utils.toast(this, "已导入 $n 项设置，部分需重启应用生效") }
                        .onFailure { Utils.toast(this, "导入失败: ${it.message}") }
                }.onFailure { Utils.toast(this, "读取文件失败: ${it.message}") }
            }
        }
    }

    /** Pick JSON or XML, build the backup, and save it to Downloads (with a share fallback). */
    private fun exportSettings() {
        showOptionPicker("导出设置格式", listOf("JSON", "XML"), 0) { which ->
            val json = which == 0
            val ext = if (json) "json" else "xml"
            runCatching {
                if (json) SettingsBackup.exportJson() else SettingsBackup.exportXml()
            }.onSuccess { content ->
                val saved = runCatching { LogExporter.save(this, "qqpro_settings", content, ext) }.getOrNull()
                if (saved != null) {
                    Utils.toast(this, "已保存:\n${saved.location}")
                } else {
                    Utils.toast(this, "保存失败")
                }
            }.onFailure { Utils.toast(this, "导出失败: ${it.message}") }
        }
    }

    /** Open the system file picker; the chosen file is applied in onActivityResult. */
    private fun importSettings() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择设置文件"), REQ_IMPORT_SETTINGS)
        } catch (e: Exception) {
            Utils.toast(this, "无法打开文件选择器")
        }
    }

    private fun GroupScopeFix.textInput(
        title: String,
        desc: String,
        pref: Pref<String>
    ) = card { card ->
        // Full-width input below the title/description — the right-aligned field is too
        // narrow to use on the watch screen.
        card.vertical()
        card.content {
            titleColumn(title, desc).width(FILL)
            add<EditText>()
                .text(pref.value)
                .textSize(13f)
                .textColor(M3.onSurface)
                .width(FILL)
                .doAfterTextChanged { pref.value = it?.toString() ?: "" }
        }
    }

    /** Value slider rendered full-width below the title row, for watch use. */
    private fun GroupScopeFix.slider(
        title: String,
        desc: String,
        pref: Pref<Float>,
        min: Float = 0.1f,
        max: Float = 1.2f
    ) = card { card ->
        card.vertical()
        lateinit var valueLabel: TextView
        val steps = ((max - min) * 100).roundToInt()
        lateinit var seek: SeekBar
        card.content {
            add<LinearLayout>()
                .width(FILL)
                .content {
                    titleColumn(title, desc).weight(1f)
                    // Tap the value to type an exact number (a precise alternative to dragging on the
                    // tiny watch screen). The faint rounded chip signals it's interactive.
                    valueLabel = add<TextView>()
                        .text(format(pref.value))
                        .textSize(14f)
                        .textColor(ACCENT)
                        .gravity(Gravity.CENTER)
                        .padding(left = 12.dp, top = 4.dp, right = 12.dp, bottom = 4.dp)
                    valueLabel.background(GradientDrawable().apply {
                        setColor(M3.surfaceContainerHigh)
                        cornerRadius = 10.dp.toFloat()
                    })
                    valueLabel.rippleTouch()
                    valueLabel.onClick {
                        showNumberInput(title, pref.value, min, max) { v ->
                            pref.value = v
                            valueLabel.text = format(v)
                            seek.progress = ((v - min) * 100).roundToInt().coerceIn(0, steps)
                        }
                    }
                }
        }
        seek = mdSeekBar()
            .progressMax(steps)
            .onProgressChanged { p, fromUser ->
                val v = min + p / 100f
                valueLabel.text = format(v)
                if (fromUser) pref.value = v
            }
        seek.progress = ((pref.value - min) * 100).roundToInt().coerceIn(0, steps)
        card.addView(seek, LinearLayout.LayoutParams(FILL, 36.dp).apply {
            topMargin = 4.dp
        })
    }

    /** Stock SeekBar dressed up MD3-style: thin rounded two-tone track + vertical pill thumb. */
    private fun mdSeekBar(): SeekBar {
        val trackH = 2.dp
        val inactive = GradientDrawable().apply {
            setColor(TRACK_INACTIVE)
            cornerRadius = trackH / 2f
            setSize(0, trackH)
        }
        val activeShape = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = trackH / 2f
            setSize(0, trackH)
        }
        val active = ClipDrawable(activeShape, Gravity.START, ClipDrawable.HORIZONTAL)
        val progress = LayerDrawable(arrayOf(inactive, active)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        // MD3 vertical pill thumb: taller than wide, fully rounded.
        val thumbW = 5.dp
        val thumbH = 22.dp
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ACCENT)
            cornerRadius = thumbW / 2f
            setSize(thumbW, thumbH)
        }
        return SeekBar(this@设置页).apply {
            progressDrawable = progress
            setThumb(thumb)
            thumbOffset = 0
            val v = thumbH / 2
            setPadding(10.dp, v, 10.dp, v)
        }
    }

    private fun format(v: Float) = String.format("%.2f", v)

    private fun GroupScopeFix.titleColumn(title: String, desc: String): LinearLayout {
        val column = add<LinearLayout>().vertical()
        column.content {
            add<TextView>()
                .text(title)
                .textSize(13f)
                .textColor(M3.onSurface)
            if (desc.isNotEmpty()) {
                add<TextView>()
                    .text(desc)
                    .textSize(10f)
                    .textColor(M3.onSurfaceVariant)
            }
        }
        return column
    }

    /** A rounded dark card row with consistent margins. */
    private inline fun GroupScopeFix.card(block: (LinearLayout) -> Unit) {
        val card = add<LinearLayout>()
            .width(FILL)
            .padding(12.dp)
        card.background(GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = 14.dp.toFloat()
        })
        card.margin(top = CARD_MARGIN_DP.dp, bottom = CARD_MARGIN_DP.dp)
        block(card)
    }
}

private typealias GroupScopeFix = momoi.mod.qqpro.lib.LinearScope
