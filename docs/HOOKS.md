# Hook 方法技术文档

## 1. 注入机制（ApkMixin）

本项目不修改原版 APK 资源/签名，而是在**构建期**把自研 Kotlin 代码注入原版 dex。核心实现在
`源码/ApkMixin/src/main/java/momoi/plugin/apkmixin/MixinProcessor.kt`。

### 1.1 编译期桩（compile-only stub）

`app/libs/source.jar` 是原版 QQ 的类桩（由原版 APK 提取，**不随本包分发**）。App 源码以
`compileOnly` 方式引用它：

- 开发时直接写 `com.tencent.qqnt.account.login.ui.QrLoginFragment` 等 QQ 类，编译不报错；
- 桩里的方法名/字段名与原版 APK 的 R8 混淆名一致（如 `Y()` = onCreateView、`f` = viewBinding）；
- 桩只参与编译，**不进最终包**。

### 1.2 @Mixin（类合并，最常用）

```kotlin
@Mixin
class LoginQrZoom : QrLoginFragment() { ... }
```

处理流程（`processClasses` → `mixinToTargetClass`）：

1. 找到被合并的目标类（mixin 的父类在目标 APK 中的同名类）；
2. 把 mixin 类的方法**按名字+签名**合并进目标类：
   - 目标类若有同名方法，先改名为 `name_0`、`name_0_1`…（原逻辑保留）；
   - mixin 方法体里的 `invoke-super` 被改写为对改名后原方法的调用；
3. mixin 类自身不会进入最终包；合并结果写入 `app/build/mixinDex/`。

效果示例：`QrLoginFragment.Y()`（原版 onCreateView）被改名为 `Y_0()`，新的 `Y()` 由 hook
实现（可先调 `Y_0()` 拿原版 View 再改造）。

### 1.3 @StaticHook（静态方法注入）

在方法体里标注 `@StaticHook(SomeClass::class)` 时，该方法会被标记为 `static` 并注入到目标类，
用于给 QQ 类补静态入口（`MixinProcessor.processMethodStaticHook`）。

### 1.4 @ConstructorHook（构造器注入）

把标注方法的指令**拼接到目标类同名构造器末尾**（只能使用参数寄存器 p0..，不能使用局部变量 vN），
用于初始化 hook 状态（`injectConstructorHook`）。

### 1.5 @PrivateCall

标注后方法会从 public 改为 private，用于覆盖目标类私有方法（`processMethodBody`）。

## 2. 输出与替换

`MixinApk-debug` 任务输出：

- `app/build/mixinDex/classes*.dex`：**完整重写的目标 APK 类集合**（原版全部类 + 合并后的修改类 + 新增类）；
- `app/dist/mixin.apk`：原版 APK + 新 dex + 注入资源 + 合并清单，**保留腾讯原签名**；
- 最终 5-dex 安装包由 `文档/BUILD.md` 的组装步骤生成（原 4 dex + 1 模块 dex）。

## 3. 关键 Hook 清单（按功能）

> 文件路径相对 `源码/app/src/main/java/`。标注 * 的为本次 API 19 兼容修复重点。

### 3.1 登录

| 文件 | 目标类 | 作用 |
| --- | --- | --- |
| `momoi/mod/qqpro/hook/LoginQrZoom.kt` * | `QrLoginFragment` | 登录页 Hook 入口：点击放大二维码；M3 重建入口 |
| `momoi/mod/qqpro/hook/login/LoginM3.kt` * | —（辅助） | 扫码页 M3 重绘（品牌、白卡二维码、扫码后显示账号头像） |
| `momoi/mod/qqpro/hook/login/WelcomePage.kt` * | `LoginWithoutStatePage` | 首启欢迎页 M3 重绘（QQ Max 品牌页） |
| `momoi/mod/qqpro/hook/login/LoginState.kt` * | `LoginWithStateFragment` | 退出登录后的快捷登录页 M3 重绘 |
| `momoi/mod/qqpro/hook/login/QrCodeThemeColor.kt` * | `QUIColorfulQRCodeView` | 二维码跟随 M3 主题色（拦截 `b()` 换色） |
| `momoi/mod/qqpro/hook/login/LoginScanObserver.kt` | `LoginQrCode$wtLoginObserver$1` | 捕获扫码后的账号 uin，提前显示身份 |
| `com/tencent/qqnt/account/login/qrcode/LoginQrCode$wtLoginObserver$1.java` | 桩 | 供 @Mixin 继承的桩类 |

### 3.2 群管理（改群名 / 禁言 / 踢人 / 在线状态）

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/style/M3SettingsRedesign.kt` | M3 设置页；"改群名"入口路由到输入弹窗 |
| `momoi/mod/qqpro/lib/material/M3QQEditText.kt` * | 输入弹窗控件（修复 `getSystemService(Class)` API 23+ 崩溃） |
| `momoi/mod/qqpro/hook/GroupMute.kt` | 全员禁言 / 指定成员禁言 |
| `momoi/mod/qqpro/hook/action/OnlineStatus.kt` | 群成员在线状态轮询与展示 |
| `momoi/mod/qqpro/hook/GroupMemberSelectSearch.kt` / `MemberListSearch.kt` | 成员选择与搜索（禁言/踢人用） |
| `momoi/mod/qqpro/hook/KickMemberIconFix.kt` | 踢人图标修正 |
| `momoi/mod/qqpro/hook/view/MemberPickerFragment.kt` * | 成员选择器（头像圆形裁剪，API 19 已兼容） |

改群名内核调用：`IGroupService.modifyGroupName(long, String, boolean, IOperateCallback)`（桩在
`app/libs/source.jar`，构建期解析）。

### 3.3 聊天页

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/InlineImeRoute.kt` / `InlineEmojiPanel.kt` | 行内输入 / 行内表情选择 |
| `momoi/mod/qqpro/hook/style/聊天底部按钮调整.kt` * | 聊天底部输入栏（含 `pro/` 图标资源注入） |
| `momoi/mod/qqpro/hook/style/长按菜单调整.kt` * | 长按消息菜单 M3 化（ripple 已 API 19 兼容） |
| `momoi/mod/qqpro/hook/ChatSearch.kt` / `ChatListLongClickMenu.kt` | 聊天内搜索 / 会话长按菜单 |
| `momoi/mod/qqpro/hook/ReplyWithAt.kt` / `aio_cell/ReplyView.kt` | 回复消息（已知 bug #3：回复双卡片） |
| `momoi/mod/qqpro/hook/aio_cell/AIOCell.kt` / `AIOMsgEx.kt` | 聊天消息单元格 / 扩展 |
| `momoi/mod/qqpro/hook/action/CurrentContact.kt` / `RecentContacts.kt` * | 当前会话 / 最近会话（右侧横幅） |
| `momoi/mod/qqpro/hook/style/修复回复带图显示.kt` | 回复带图显示修复 |

### 3.4 说说 / 空间（QZone）

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/qzone/QzoneFeedM3.kt` * | 说说动态卡片 M3 化（头像圆形、卡片圆角，API 19 已兼容） |
| `momoi/mod/qqpro/hook/qzone/QzoneMediaPicker.kt` * | 空间图片选择 |
| `momoi/mod/qqpro/hook/QZoneTopBar.kt` * / `QzoneFriendPicker.kt` * | 空间顶栏 / 好友选择 |
| `momoi/mod/qqpro/hook/QZoneMainFrameHook.kt` / `QZoneMineFragmentHook.kt` | 空间主框架 / 我的页 |
| `momoi/mod/qqpro/hook/action/CurrentMsgList.kt` | 说说消息流 |

### 3.5 主框架与导航

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/MainNav.kt` * | 主界面底部导航（M3 动效；PathInterpolator 已兼容） |
| `momoi/mod/qqpro/hook/MainPageNav.kt` | 主页面导航 |
| `momoi/mod/qqpro/lib/material/M3Motion.kt` * | M3 动效曲线（6 条 PathInterpolator 全部 API 19 兼容） |
| `momoi/mod/qqpro/hook/StatusBarOption.kt` | 状态栏（仅 API 30+ 生效） |

### 3.6 多 dex 加载（Android 4.4 关键）

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/JavaMultiDex.kt` | 纯 Java 方式安装次 dex；**按进程隔离目录 `jm_<进程>`** 避免跨进程竞争 |
| `WatchApplication`（合并结果） | 在 `attachBaseContext` 中先装次 dex 再走原逻辑 |
| `BoostMultiDex`（空壳补丁） | 禁用原版 BoostMultiDex，防止双套多 dex 加载互相竞争（详见 API19-COMPAT.md） |

### 3.7 通话 / 通知 / 其它

| 文件 | 说明 |
| --- | --- |
| `momoi/mod/qqpro/hook/call/ScreenShareService.kt` / `ScreenShare.kt` * | 音视频通话投屏（`startForegroundService` 已兼容 API 19） |
| `momoi/mod/qqpro/hook/call/MaterialCallUi.kt` * | 通话界面 M3 化 |
| `momoi/mod/qqpro/hook/NotificationAlert.kt` / `ResidentNotification.kt` | 消息提醒 / 常驻通知 |
| `momoi/mod/qqpro/hook/NotificationReply.kt` | 通知快捷回复 |
| `momoi/mod/qqpro/watchdog/*` | 崩溃/卡死捕获与报告页（独立 `:crash` 进程） |

## 4. 新增类 vs 替换类

- **替换类（targets）**：原版已有的类（QQ 类），hook 后由 `BuildFinal2` 替换回原 4 dex —— 清单见
  `文档/BUILD.md`（当前 147 个）；
- **新增类**：`momoi.*`、kotlinx、androidx 等原版没有的类，全部放入第 5 个 dex（classes5），由
  `JavaMultiDex` 在启动时安装。

## 5. 常见坑

1. **方法名是 R8 混淆名**：必须通过桩 jar 对齐，改桩名等于丢 hook（合并后目标类方法会变成 `Y_0` 等）；
2. **不要在原版类里留 lambda 匿名类**：@Mixin 方法体会被复制进 QQ 包，lambda 生成类会变成
   `QQ包$1` 之类，与桩不一致 —— 一律委托到 `momoi.*` 下的 object/helper；
3. **API 19 限制**：`RippleDrawable` / `PathInterpolator` / `ViewOutlineProvider` / `setClipToOutline` /
   `finishAndRemoveTask` / `getSystemService(Class)` / `createNotificationChannel` / `startForegroundService`
   均不存在或需要 SDK 判断，见 `API19-COMPAT.md`。
