# 已知 Bug 清单

> 状态截至 v17（2026-08-10）。

| # | 现象 | 涉及模块 | 状态 |
| --- | --- | --- | --- |
| 1 | 「我的」页下方操作横幅、群聊右滑横幅在深色模式下为纯白 | `M3.ripple(null)` API 19 默认态 | ✅ v22 已修：`StateListDrawable` 未按压默认态误画白色 mask → 改为透明，与 API 21+ 一致；实测「我的」页横幅纯白 92% → 0% |
| 2 | AI 总结报错 502 | AI 总结请求/网关 | ✅ v16.2 实测通过：填写自己的 API Key（OpenAI 兼容，默认 DeepSeek）后直连，不再依赖内置 Onyx 服务 |
| 3 | 回复消息时同时显示两个回复卡片 | `aio_cell/AIOCell.kt`、`ReplyView.kt` | ✅ v11 已修（tag 去重 + 原生行隐藏加强）；v14/v15 再修引用卡文字被误藏、原生时间残留，已实测 |
| 4 | 群公告只能看到最新一条 | `api/GroupBulletinApi.kt` | 待修复（内核接口限制，需实测历史接口） |
| 5 | 说说不能添加表情 | qzone 发布页 | 待修复 |
| 6 | 聊天页不能显示群/好友名称 | `RichTitlebar.kt`（setElevation） | ✅ v9 已修并实测（群名+成员数正常显示） |
| 7 | mitu4 主界面顶部部分没有显示 | 主框架顶部区域 | 待修复 |
| 8 | 不能禁言特定用户 | `RichProfilePage.kt`、`GroupMute.kt`、`MemberMutePage.kt` | ❌ 服务端限制：`setMemberShutUp`（OIDB 0x1253）返回 -10122 "Product does not have permission"——手表产品无此权限，客户端不可修复；v16.3 已做成“资料卡按钮 + 独立整页”（按钮不再被挤出屏幕）并加明确提示；全员禁言 `setGroupShutUp` 可用 |
| 9 | 头衔等级框有概率不显示 | `RichProfilePage.kt`（setElevation） | ✅ v9 已修资料卡崩溃，实测打开正常；显示概率问题待观察 |
| 10 | 多选会黑屏 | `ChatMultiSelect.kt` | ✅ v13 已修并实测（Canvas.drawRoundRect 7 参是 API 21+，API 19 上 onDrawOver 崩 → 改 RectF） |
| 11 | 不能发起音视频通话（卡死） | `call/CallAudio.kt`（AudioDeviceCallback API 23+） | ✅ v8 已修，待真机发起通话验证 |
| 12 | 点击回复卡片跳转时卡死 | `view/SmoothScrollTo.kt` | ✅ v16.2 已修并定位：跳转后的高亮用 View.setForeground（API 23+），API 19 上 NoSuchMethodError 崩主线程 → 低版本改行 alpha 脉冲 |
| 13 | 收到的图片全部白框损坏、发不出图片 | 图片下载/上传链路 | ✅ 已修复（v16.2 打开内核自动下载开关 + 会话恢复后实测正常；根因含内核 xg_auto_download=false 与富媒体会话鉴权过期） |
| 14 | 防撤回开启后聊天界面卡死 / “已撤回”标记不显示 | `hook/action/CurrentMsgList.kt`、`aio_cell/AIOCell.kt` | ✅ v31 防撤回修复3 已修并实测：① 卡死根因是 `HashMap.putIfAbsent`（Java 8/API 24+）在 API 19 上每次恢复都抛 NoSuchMethodError——恢复逻辑改成 API 19 兼容写法，且只在收到撤回灰条的帧执行、失败回退内核原列表；② “已撤回”小字不显示是因为恢复发生在渲染前、适配器对比新旧列表时原消息“没变”不重新 bind——改为渲染后主动给可见的已撤回气泡补标（`markRecalledVisible`），bind 路径同时改成递归找正文 TextView |
| 15 | 聊天页背景会串（A 会话背景显示到 B 会话） | `hook/WatchAIOPageReset.kt`、`hook/action/CurrentContact.kt` | ✅ v31 背景修复已修：背景原来在 `WatchAIOFragment.onViewCreated` 里读全局单例 `CurrentContact.peerUid` 应用，而它更新在后面的 `ChatPie.a()`——切聊天时取到的常是上一个会话的值。改为在 `ChatPie` 钩子拿到本会话真实 peer 后用 `applyPeerBackground` 重挂（独立背景优先→全局→M3 surface），幂等覆盖 |

## 说明

- **v17 新增**：联网字体包（MiSans 优先 + Unifont 兜底，仅本应用生效）——设置 → 外观主题 → 下载字体包，已实测三件字体全部下载成功并生效；
- 已修并实测：2（v16.2）、3（v11+v15）、6（v9）、10（v13）、12（v16.2）；
- 已修待实测：9（v9）、11（v8）；
- 待修复：1 / 4 / 5 / 7；
- 每个 bug 修复后：重新构建 → 打多 dex 补丁 → 组装 → 装机验证 → 更新本文件与安装包。
