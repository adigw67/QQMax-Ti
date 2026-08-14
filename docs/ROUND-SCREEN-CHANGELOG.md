# 圆屏适配 Changelog（问题逐项修复）

> 本轮针对 4 个具体圆屏问题做定点修复。全部改动位于 `app/src/main/java/momoi/`，兼容
> Android 4.4 (API 19)，延续 Material 3 深色风格，未改动任何业务/功能逻辑（联网字体包、群管理、
> 语音、大表情等不受影响）。

## 0. 说明：本项目没有 XML 布局

QQMax-Ti 的 UI 全部用 **Kotlin DSL 程序化构建**（`momoi.mod.qqpro.lib.*` + `lib/material/*`），
仓库里**不存在** `app/src/main/res/layout/`，也没有 `activity_chat.xml` / `activity_main.xml`。
QQ 原版的 XML 布局在被打补丁的 `app/mixin/source.apk` 里（本项目不编译、不随包分发 XML）。
因此「修改布局」等价于在 `momoi/` 的 Kotlin hook 层，按下面的统一公式动态设置 padding / margin。
圆屏几何/安全区的唯一事实来源是 `lib/RoundWatch.kt`。

## 0.1 统一安全边距公式（本文件核心基准）

按你给定的圆形内切矩形公式实现，四边等值、无硬编码像素：

```kotlin
// lib/RoundWatch.kt
fun insetPx(ctx: Context): Int {
    val dm = ctx.resources.displayMetrics              // 即 WindowManager 的屏幕宽高
    val screenMin = minOf(dm.widthPixels, dm.heightPixels)
    val usable = (screenMin / Math.sqrt(2.0)).toInt()
    return (screenMin - usable) / 2
}
// horizontalInsetPx / safeTopBottomPx / safeInsets 现在全部 = insetPx（对称）
// applyUniformInset(view) = view.setPadding(p, p, p, p)
```

---

## 问题 1：顶部状态栏 / 底部导航栏 / 聊天输入框显示不全（四角被圆边裁切）

| 修改点 | 文件 | 原因 |
| --- | --- | --- |
| 底部导航栏左右各留圆屏安全 padding | `hook/MainNav.kt` | 导航栏贴底、`方形铺开` 时两侧图标落入圆角盲区；加 `horizontalInsetPx` 内收 |
| 聊天标题栏左右边距取 `max(用户设置, 圆屏安全区)` | `hook/RichTitlebar.kt` | 标题/未读红标不贴圆角（上一轮已做，本轮保持） |
| 聊天输入框左右边距取 `max(用户设置, 圆屏安全区)`、行内 padding 改走 `RoundWatch.enabled` | `hook/style/聊天底部按钮调整.kt` | 输入框两侧按钮不贴圆角（上一轮已做，本轮保持） |

> 系统状态栏说明：`Settings.showStatusBar` 默认关闭（全屏），且 `StatusBarOption` 在 API<30 直接
> 早退——API 19 手表上系统状态栏本就不显示，不存在“顶部时间被裁”问题；圆屏上顶部正中的时间在
> 安全区内也不会裁切。主界面遮罩由 `更新检查.kt` 的 `RoundWatch.apply(decorView)` 覆盖。

## 问题 2：主页（主界面）列表/网格内容超出圆形可见区域

| 修改点 | 文件 | 原因 |
| --- | --- | --- |
| 会话列表（第 1 页）根视图套 `applySafePadding`（顶/底/左右圆屏安全区） | `hook/action/RecentContacts.kt`（`ChatListMaterial.Y()`） | 首尾会话行与行卡片不贴圆边 |
| 联系人列表（第 2 页）左右套圆屏安全区、顶 padding 叠加圆屏顶安全区 | `hook/contact/ContactTopBar.kt` | 好友/群聊行卡片不贴圆边 |

> 列表行自带左右卡片边距（`CardMarginUnify.CARD_MARGIN_DP`），因此列表容器只补圆屏安全区，
> 不覆盖行自身边距；网格（图片选择器 `ImagePickerActivity` 2 列、qzone 选择器）此前已按
> `Utils.isRoundScreen` 减列加边距，本轮修复的检测（`Utils.isSquareScreen`）使其在发行版自动生效。

## 问题 3：联系人页按钮缺失（“添加好友/群聊入口”等边缘按钮被裁切）

| 修改点 | 文件 | 原因 |
| --- | --- | --- |
| 顶栏 4 个图标按钮行：左右 + 顶部各留圆屏安全区 | `hook/contact/ContactTopBar.kt`（`buildActionBar`） | 「加好友/好友通知/群通知/搜索」不再落入圆角盲区 |
| 搜索行（返回 + 搜索框）：左右留圆屏安全区 | `hook/contact/ContactTopBar.kt`（`buildSearchRow`） | 展开搜索时输入框不被圆边裁切 |

> 这些按钮本身已是 `MaterialIconButton`（上一轮圆屏时放大到 28dp 图标 + 48dp 命中区域），
> 本轮把它们的**容器边距**一并内收，彻底解决“贴边不可点”。

## 问题 4：长按菜单显示不全（PopupMenu/上下文菜单条目被挤出屏幕）

| 修改点 | 文件 | 原因 |
| --- | --- | --- |
| 聊天消息长按菜单：卡片左右 + 上下边距改为圆屏安全区（内容超高在 ScrollView 内滚动） | `hook/style/长按菜单调整.kt`（`LongPressMenu.build`） | 居中菜单卡片整体内收进内切圆，所有条目可见可点 |
| 附件「+」面板：卡片四边距改为圆屏安全区 | `hook/MenuPanelLayout.kt` | 相册/拍照/录像/表情等条目不被圆边裁切 |
| 会话列表长按弹窗 / 好友/群设置弹窗 / 图片查看器长按弹窗（复用同一滚动容器）左右 + 顶/底套圆屏安全区 | `hook/style/M3SettingsRedesign.kt`（`newScroll`） | 删除/置顶/免打扰/清空等条目不被圆边裁切 |

> 本项目**没有**使用系统 `PopupMenu` 锚点定位：聊天消息长按菜单是自绘的居中 `ScrollView` 卡片
> （`LongPressMenu`），会话列表长按菜单走 `rebuildSettingList` 的居中 M3 列表。因此修复方式是
> “内收边距 + 内部滚动”，而非重写 `PopupMenu` 定位。

---

## 布局结构对比（代码层面）

### 长按消息菜单（`LongPressMenu.build`）

修改前：
```kotlin
addView(scroll, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).apply {
    val m = 14.dp; leftMargin = m; rightMargin = m; topMargin = m; bottomMargin = m
})
```

修改后（圆屏时）：
```kotlin
val sideM = maxOf(14.dp, RoundWatch.horizontalInsetPx(ctx))   // 左右收进内切圆
val topM  = maxOf(14.dp, RoundWatch.safeTopBottomPx(ctx))      // 上下收进内切圆
addView(scroll, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).apply {
    leftMargin = sideM; rightMargin = sideM; topMargin = topM; bottomMargin = topM
})
```

### 联系人顶栏（`ContactTopBar.buildActionBar`）

修改前：
```kotlin
setPadding(8.dp, 6.dp, 8.dp, 4.dp)   // 固定 8dp，两侧按钮贴边
```
修改后（圆屏时）：
```kotlin
setPadding(8.dp + horizontalInsetPx, 6.dp + safeTopBottomPx, 8.dp + horizontalInsetPx, 4.dp)
```

### 会话列表（`ChatListMaterial.Y()`）

修改前：只设置背景色；修改后：追加 `RoundWatch.applySafePadding(root)`（四边安全区，幂等）。

---

## 改动文件清单（本轮）

- `lib/RoundWatch.kt` —— 新增 `popupMaxHeightPx`
- `hook/MainNav.kt`
- `hook/action/RecentContacts.kt`
- `hook/contact/ContactTopBar.kt`
- `hook/style/长按菜单调整.kt`
- `hook/MenuPanelLayout.kt`
- `hook/style/M3SettingsRedesign.kt`
- `docs/ROUND-SCREEN-CHANGELOG.md`（本文件）

（上一轮已交付的基础适配见 `docs/ROUND-SCREEN.md`，两轮合起来是完整方案。）

## 兼容性与性能

- 仅用 `View.setPadding`/`LayoutParams.setMargins`/`maxOf`/`max`，全部 API 19 及以下；
- 圆屏几何为 O(1) 计算、无缓存失效点；`applySafePadding` 用 keyed tag 幂等，不重复叠加；
- 未新增任何图片/位图、动画或逐帧逻辑，弱硬件无卡顿/ANR 风险。

---

# 精准布局修复（第 3 轮）：4 个具体 UI 异常

> 上一轮“全面适配”后，按设备实机反馈对 4 个具体异常做定点修复，统一改用**对称内切矩形缩进**，
> 消除任何单向偏移/顶部固定偏移。

## 1. 聊天页顶栏被裁剪（顶部返回按钮/联系人名被削半）

- 文件：`hook/RichTitlebar.kt`
- 修改：标题栏 `FrameLayout.LayoutParams` 增加 `topMargin = RoundWatch.insetPx(ctx)`（仅圆屏时），
  顶栏整体下移进内切正方形；左右边距继续取 `max(用户设置, horizontalInsetPx)`。

## 2. 聊天页输入框被裁剪（发送键/语音键底部被切）

- 文件：`hook/style/聊天底部按钮调整.kt`
- 修改：输入框根容器（`rootContainer`）`post` 一次，等原生装好 `layoutParams` 后设
  `bottomMargin = RoundWatch.insetPx(ctx)` —— 等价于把 `layout_gravity=bottom` 改为“安全区底部内侧”，
  输入框整体上移。

## 3. 主页上方空一块（内容下移过多、底部被裁）

- 文件：`hook/action/RecentContacts.kt`（`ChatListMaterial.Y()`）
- 修改：把原来的 `applySafePadding`（叠加式）改为 `applyUniformInset(root)` —— 直接
  `setPadding(p, p, p, p)` 对称缩进，不做单向 top 偏移；`RoundWatch` 的
  `horizontalInsetPx/safeTopBottomPx/safeInsets` 也统一为同一 `insetPx`，彻底对称。

## 4. 底栏（底部导航 Tab）显示不全

- 文件：`hook/MainNav.kt`
- 修改：`positionBar` 的保留高度 `reserve = barHeight + navInset`，底栏 `translationY = h - reserve`
  上移到内切正方形底部内侧；导航栏自身左右再加 `horizontalInsetPx`（避免「方形铺开」两侧图标被裁）。

## 改动文件清单（第 3 轮）

- `lib/RoundWatch.kt` —— 新增 `insetPx`（统一公式）+ `applyUniformInset`，`horizontalInsetPx`/
  `safeTopBottomPx`/`safeInsets` 全部改为对称 `insetPx`
- `hook/RichTitlebar.kt` —— 顶栏 `topMargin`
- `hook/style/聊天底部按钮调整.kt` —— 输入框 `bottomMargin`
- `hook/action/RecentContacts.kt` —— 会话列表 `applyUniformInset`
- `hook/MainNav.kt` —— 底栏上移 `reserve = barHeight + navInset`
- `docs/ROUND-SCREEN-CHANGELOG.md`（本文件）
