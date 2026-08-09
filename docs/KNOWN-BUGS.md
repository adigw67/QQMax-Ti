# 已知 Bug 清单（待修复）

> 状态截至 v7（2026-08-09）。修复后将更新本文件并重新打包。

| # | 现象 | 涉及模块（推测） | 状态 |
| --- | --- | --- | --- |
| 1 | 「我的」页下方操作横幅、群聊右滑横幅在深色模式下为纯白 | 深色主题资源 / 横幅配色（`QZoneMineFragmentHook`、`RecentContacts`） | 待修复 |
| 2 | AI 总结报错 502 | AI 总结请求/网关（服务端或参数） | 待排查 |
| 3 | 回复消息时同时显示两个回复卡片 | 回复视图重复添加（`aio_cell/ReplyView.kt`、`ReplyWithAt.kt`） | 待修复 |
| 4 | 群公告只能看到最新一条 | 公告列表展示逻辑（`GroupBulletin.kt`） | 待修复 |
| 5 | 说说不能添加表情 | 说说发布的表情选择器（qzone 发布页） | 待修复 |
| 6 | 聊天页不能显示群/好友名称 | 聊天标题/会话名称（`RichTitlebar`、`CurrentContact`） | 待修复 |
| 7 | mitu4 主界面顶部部分没有显示 | 主框架顶部区域（`MainNav`/`MainPageNav` 布局） | 待修复 |
| 8 | 不能禁言特定用户 | 成员禁言流程（`GroupMute.kt`、`MemberPickerFragment`） | 待修复 |
| 9 | 头衔等级框有概率不显示 | 资料卡头衔/等级渲染（`ProfileExtraInfo`、`RichProfilePage`） | 待修复 |
| 10 | 多选会黑屏 | 多选界面（`ChatMultiSelect.kt`、`GalleryMultiSelect`） | 待修复 |
| 11 | 不能发起音视频通话（卡死） | 通话发起流程（`call/*`） | 待修复 |

## 说明

- 修复优先级建议：6 / 8 / 11（主链路）→ 3 / 10（聊天高频）→ 1 / 4 / 5 / 9（体验）→ 2 / 7（环境/布局）；
- 每个 bug 修复后：重新构建 → 打多 dex 补丁 → 组装 → 装机验证 → 更新本文件与安装包。
