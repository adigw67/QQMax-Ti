# 圆屏模式 UI 全面适配说明

> 目标：在保留全部功能的前提下，让 QQMax-Ti 在圆形（及圆角方形）儿童手表上获得完整、流畅的
> 视觉与操作体验。所有改动均集中在 `app/src/main/java/momoi/` 与 `app/src/main/java/com/tencent/`
> 之下，全部兼容 Android 4.4 (API 19)，并延续项目已有的 Material 3 设计语言。

## 1. 设计总览

圆屏适配被收敛到**一个统一工具层** `momoi.mod.qqpro.lib.RoundWatch`，其它界面/组件只通过它读取
圆屏几何与安全区，不再各自写零散的判环逻辑。这样改主题、改适配力度都只动一处。

判定入口只有一个：

```kotlin
RoundWatch.enabled  // = Settings.md3eRound.value || Utils.isRoundScreen
```

- **真圆屏 / 方形屏设备**：`Utils.isRoundScreen` 为真 → 自动启用全套圆屏适配（开箱即用）；
- **其它设备**（普通手机等）：默认不启用，可在设置里打开「圆屏模式」强制启用（便于预览/调测）。

设置入口：**设置 → 外观主题 → 圆屏模式**（原「圆表适配（实验性）」，文案已更新）。

## 2. 圆屏检测（`util/Utils.kt`）

`isRoundScreen` 现在在 API 19 发行版上也能正确判定：

- **API 23+**：直接读系统 `Configuration.isScreenRound`；
- **API 19（本表）**：系统无该字段，改用**形态启发式** `isSquareScreen`——圆表/方表的显示矩阵是
  正方形或近正方形（圆脸内切于其中，且方形屏同样存在四角被圆边裁切的问题），长宽差 ≤ 长边的 1/8
  即视为圆屏。

> 刻意**移除了旧的 `isDebug` 兜底**：debug 包也可能跑在手机上，若把 `isDebug` 当圆屏会让手机调试
> 时被误套圆表遮罩。方形启发式已覆盖绝大多数圆表（含带下巴的圆表，其显示矩阵仍为方形）。

新增：

- `Utils.widthPixels`、`Utils.isSquareScreen`。

## 3. 统一工具层（`lib/RoundWatch.kt`）

重写为圆屏模式的单一事实来源，全部 API 19 安全（无 `RippleDrawable`/`Outline`/`PathInterpolator`）：

| API | 说明 |
| --- | --- |
| `enabled` | 圆屏模式是否生效（自动检测 或 手动开关，取或） |
| `radiusPx(ctx)` | 屏幕内切圆半径 |
| `cornerInsetPx(ctx)` | 圆边在 45° 角切进屏幕的最大距离 = r·(1 − 1/√2) ≈ 0.293r（「内切正方形」安全边界） |
| `horizontalInsetPx(ctx)` | 列表/滚动内容水平安全边距（取 `cornerInsetPx` 的 55%） |
| `safeTopBottomPx(ctx)` | 顶/底安全区：带下巴屏沿用 (高−宽)/2 的 18%，方形屏取 `cornerInsetPx` 的 60% |
| `safeInsets(ctx)` | 四边安全区（left/top/right/bottom） |
| `applySafePadding(view)` | 给滚动容器叠加安全 padding（幂等、与已有 padding 相加） |
| `hitTargetPx(ctx)` | 圆屏建议最小点击目标（48dp） |
| `apply(root)` | 页面根视图盖「表盘遮罩」（内切圆外画黑、内圈柔和阴影，幂等） |
| `circleClip(drawable)` | 把 drawable 裁剪到内切圆（背景图等） |

## 4. 各界面/组件接入点

| 位置 | 改动 |
| --- | --- |
| `util/ChatBackground.kt` | 背景图圆裁剪改用 `RoundWatch.enabled` + `RoundWatch.circleClip`（原私有 `CircleClipDrawable` 上移复用） |
| `lib/material/M3Card.kt` | 圆屏用更大表达性圆角（`radiusXl`）的门控改为 `RoundWatch.enabled` |
| `hook/MainNav.kt` | 底部导航「浮起胶囊」样式的门控改为 `RoundWatch.enabled` |
| `lib/material/M3ListItem.kt` | 圆屏时行高 56→60dp、纵向 10→12dp（`dense` 48→52dp / 6→8dp），放大可点击目标 |
| `lib/material/M3Dialog.kt` | 圆屏时对话框四周改套 `safeInsets`（至少 20dp），内容不被圆边裁切 |
| `lib/material/Material.kt` | `MaterialIconButton` 圆屏时图标 22→28dp、最小命中区域 48dp |
| `hook/设置页.kt` | 一级列表与详情层均套 `applySafePadding`；「圆屏模式」开关文案更新 |
| `hook/RichTitlebar.kt` | 标题栏/未读红标左右边距 = `max(用户设置, horizontalInsetPx)` |
| `hook/style/聊天底部按钮调整.kt` | 输入框左右边距 = `max(用户设置, horizontalInsetPx)`，行内 padding 门控改为 `RoundWatch.enabled` |

## 5. 已经具备、本次仅“复用修正检测”的圆屏适配

这些界面此前已按 `Utils.isRoundScreen` 做圆屏专属布局，本次**检测修复后自动在发行版生效**，无需再改：

- `hook/ImagePickerActivity.kt`：圆屏网格 2 列（方屏 3 列）、边缘 14dp；
- `hook/qzone/`（`QzoneFriendPicker`、`QzoneMediaPicker`、`QzoneLocationPicker`、`QzoneOverflowFragment`、
  `QzoneConfirmDialog`、`QzoneCompose`、`QzoneCommentThread`）：圆屏更大的边缘留白；
- `hook/sticker/StickerPickerFragment.kt`、`hook/summarize/SummaryViewer.kt`、`hook/view/PartialCopyFragment.kt`：
  圆屏更大的边缘留白；
- `hook/图片查看圆屏适配.kt`：大图查看器按圆屏内切圆加 padding；
- `hook/滚轮适配.kt`：表冠（rotary encoder）滚动 → 列表/ScrollView/大图缩放。

## 6. 兼容性与性能

- 所有新增代码仅用 API 19 及以下 API（`Path.clipPath`、`Region.Op`、`GradientDrawable` 等），
  不触碰 `RippleDrawable`/`Outline`/`setClipToOutline`/`WindowInsets` 等 21+ API；
- 圆屏几何按需计算、无缓存失效点，均为 O(1)；`applySafePadding` 用 keyed tag 幂等，不重复叠加；
- 遮罩/裁剪只做一次路径构建，无逐帧分配。

## 7. 构建与验证

```bash
cd /home/adugw/qqmax-ti
GRADLE_USER_HOME=/home/adugw/.gradle ./gradlew :app:compileDebugKotlin --offline
# 完整打包：./gradlew MixinApk-debug -PuseProcessorCountAsThreadCount=true --offline
```

验证要点（真圆表上）：

1. 设置 → 外观主题，确认「圆屏模式」开关存在；
2. 聊天/联系人/动态/我 四个主页内容不被圆边裁切，四角为表盘遮罩而非内容溢出；
3. 设置页一级列表与任意详情页滚动时顶/底行不被圆边裁切；
4. 对话框（选项选择、数字输入、关于页）四周留白充足、按钮可点；
5. 列表行/图标按钮点击目标明显变大，圆边处不易误触；
6. 深色模式正常（无整行纯白——v22 已修，本次未引入回归）；
7. 联网字体包、群管理、语音、大表情等特色功能不受影响（本次仅改布局/边距，未触碰功能逻辑）。

## 8. 改动文件清单

- `app/src/main/java/momoi/mod/qqpro/util/Utils.kt`
- `app/src/main/java/momoi/mod/qqpro/lib/RoundWatch.kt`
- `app/src/main/java/momoi/mod/qqpro/Settings.kt`
- `app/src/main/java/momoi/mod/qqpro/util/ChatBackground.kt`
- `app/src/main/java/momoi/mod/qqpro/lib/material/M3Card.kt`
- `app/src/main/java/momoi/mod/qqpro/lib/material/M3ListItem.kt`
- `app/src/main/java/momoi/mod/qqpro/lib/material/M3Dialog.kt`
- `app/src/main/java/momoi/mod/qqpro/lib/material/Material.kt`
- `app/src/main/java/momoi/mod/qqpro/hook/MainNav.kt`
- `app/src/main/java/momoi/mod/qqpro/hook/设置页.kt`
- `app/src/main/java/momoi/mod/qqpro/hook/RichTitlebar.kt`
- `app/src/main/java/momoi/mod/qqpro/hook/style/聊天底部按钮调整.kt`
- `docs/ROUND-SCREEN.md`（本文件）
- `README.md`（补充说明）
