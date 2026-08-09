package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.watch.gallery.preview.RFWLayerLaunchUtilKt
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import download
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.hook.view.addChatSearchEntry
import momoi.mod.qqpro.hook.summarize.addSummaryHistoryEntry
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * 聊天设置页(SettingFrame)点击头像查看大图——原生只对单聊(chatType==1)开启；群聊(chatType==2)
 * 即使强行启用原生预览也会黑屏，因为群头像不是按 uid 缓存的，getAvatarPath 取不到大图。
 * 这里给群头像单独绑定点击：下载群头像大图(qlogo)后，复用原生大图浏览器(RFWLayerLaunchUtil)展示。
 */
@Mixin
class GroupAvatarPreview : SettingFrame() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 在右侧设置页(群聊与单聊都有)加入"搜索聊天记录"入口。
        addChatSearchEntry(this)
        // 加入"总结历史"入口,查看本会话过往的聊天总结记录(任一总结功能开启时显示)。
        addSummaryHistoryEntry(this)
        // 单聊设置页底部加入"TA的空间"入口,跳转该好友的QQ空间。
        addQzoneEntry(this)
        // 单聊设置页在原生性别/生日下方补充 年龄·星座·生肖 / 地区 / 签名(随增强资料卡开关)。
        if (Settings.useRichProfile.value) addDmExtraInfo(this)
        // 群聊设置页加入"群公告"入口,查看该群的当前公告。
        addGroupBulletinEntry(this)
        val args = arguments ?: return
        val peerId = args.getString("key_bundle_peer_id")
        val chatType = args.getInt("key_bundle_chat_type")
        if (chatType == 2 && !peerId.isNullOrEmpty()) {
            Utils.log("GroupAvatarPreview: bind group avatar preview, group=$peerId")
            bindGroupAvatarPreview(this, this.f, peerId)
        }
        // 长按头像(群头像或单聊对方头像)保存大图到相册。群聊用群号、单聊用对方 uin 取大图。
        AvatarSave.attach(this.f) { AvatarSave.contactUrl(chatType, peerId) }
    }
}

// 普通(非 @Mixin)函数：内部创建的匿名类(OnClickListener / 下载回调)会生成在本包，
// 不会被 ApkMixin 拷贝进目标包，从而避免匿名类构造器跨包不可访问的 IllegalAccessError。
private fun bindGroupAvatarPreview(fragment: SettingFrame, avatarView: View, groupCode: String) {
    avatarView.setOnClickListener {
        val ctx = avatarView.context
        val cacheDir = ctx.safeCacheDir
        if (cacheDir == null) {
            Utils.log("GroupAvatarPreview: no cache dir available, skip")
            return@setOnClickListener
        }
        val cacheFile = cacheDir.child("group_avatar_$groupCode.jpg")
        val show = {
            val host = WatchPicElementExtKt.X(fragment)
            if (host == null) {
                Utils.log("GroupAvatarPreview: gallery host null")
            } else {
                val media = RFWLayerLaunchUtilKt.f(cacheFile.absolutePath)
                val bundle = Bundle().apply {
                    putBoolean("key_support_long_click", true)
                    putBoolean("key_need_clear_cache", true)
                    putStringArrayList("key_menu_item", arrayListOf("SavePic"))
                }
                RFWLayerLaunchUtilKt.d(ctx, host, null, listOf(media), 0, bundle)
            }
        }
        if (cacheFile.exists()) {
            show()
        } else {
            val url = "https://p.qlogo.cn/gh/$groupCode/$groupCode/0"
            Utils.log("GroupAvatarPreview: downloading $url")
            download(url, cacheFile) { ok ->
                runOnUi {
                    if (ok) show() else Utils.toast(ctx, "头像加载失败")
                }
            }
        }
    }
}
