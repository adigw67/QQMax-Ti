package momoi.mod.qqpro.hook

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.kernel.api.impl.GroupService
import com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.profile.ProfileData
import com.tencent.qqnt.watch.profile.ui.ProfileCardFragment
import com.tencent.qqnt.watch.ui.componet.button.WatchButton
import momoi.mod.qqpro.Colors
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.GroupScope
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.create
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/* 傻逼腾讯，你妈死了
NTKernel                com.tencent.qqlite                   I  [KernelServlet-MSF]seq(200)---- onReceive cmd= OidbSvcTrpcTcp.0x1253_1
NTKernel                com.tencent.qqlite                   I  [KernelServlet-MSF]seq(201)---- onReceive error: cmd=OidbSvcTrpcTcp.0x1253_1 response result=-10122 errMsg=Product does not have permission to access cmd
NTKernel                com.tencent.qqlite                   I  [SDK_LOG]seq(10052)---- [I] mobile_qq_wrapper_session.cc(718)::onSendSSOReply [NTWrapperSession]->onSendSSOReply. ssoSeq 124 cmd OidbSvcTrpcTcp.0x1253_1 result -10122 errorCode -10122 error_msg Product does not have permission to access cmd n_errMsg Product does not have permission to access cmd rsp_buffer_size 0
NTKernel                com.tencent.qqlite                   E  [SDK_LOG]seq(10053)---- [E] group_member_base_worker.cc(64)::SendOidbReq [group_member_mgr]->[2025-06-14 05:59:25 PM] request cmd:OidbSvcTrpcTcp.0x1253 service_type:1 failed, retCode:-10122, errMsg:Product does not have permission to access cmd
NTKernel                com.tencent.qqlite                   I  [SDK_LOG]seq(10054)---- [I] group_member_mgr.cc(460)::SetMemberShutUp [group_member_mgr]->send request code : -10122, msg : Product does not have permission to access cmd
NTKernel                com.tencent.qqlite                   I  [SDK_LOG]seq(10055)---- [I] easily_str_to_pbmsg_storage.cc(91)::OnFlush StrToPbMsgStorage start flush into db:msg_unread_info_table!
*/
class 苦呀嘻嘻 : ProfileCardFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?) = super.Y(p0, p1, p2)!!.apply {
        val uid = this@苦呀嘻嘻.requireArguments().getParcelable<ProfileData>("profile_data")!!.e
        CurrentGroupMembers.get(SelfContact.peerUid) {
            if (it.role == MemberRole.OWNER || it.role == MemberRole.ADMIN) {
                this.post {
                    runCatching {
                        val base = (this as ViewGroup).findAll { it is WatchButton } ?: return@post
                        val linear = base.parent as LinearLayout
                        (linear.parent as ScrollView).layoutParams.height = 2 * Utils.heightPixels
                        val index = linear.indexOfChild(base)
                        linear.addView(
                            create<LinearLayout>(linear.context)
                                .width(FILL)
                                .gravity(Gravity.CENTER_VERTICAL)
                                .padding(4.dp)
                                .content {
                                    Text("禁言")
                                    val d = Edit()
                                    Text("天")
                                    val h = Edit()
                                    Text("时")
                                    val m = Edit()
                                    Text("分")
                                    Text(" 执行 ")
                                        .background(
                                            roundCornerDrawable(
                                                color = Colors.btn,
                                                radius = 2.dpf
                                            )
                                        )
                                        .padding(2.dp)
                                        .clickable {
                                            val days = d.text.toString().toIntOrNull() ?: 0
                                            val hours = h.text.toString().toIntOrNull() ?: 0
                                            val minutes = m.text.toString().toIntOrNull() ?: 0
                                            val seconds = (days * 24 + hours) * 60 + minutes * 60
                                            if (seconds <= 0) {
                                                Utils.toast(context, "请输入禁言时长")
                                                return@clickable
                                            }
                                            val svc = (KernelServiceUtil.b() as? GroupService)?.service
                                            if (svc == null) {
                                                Utils.toast(context, "内核服务不可用")
                                                return@clickable
                                            }
                                            val groupCode = GroupMute.groupCode(CurrentContact.peerUid)
                                            Utils.log("禁言: setMemberShutUp gc=$groupCode uid=$uid sec=$seconds")
                                            runCatching {
                                                svc.setMemberShutUp(
                                                    groupCode,
                                                    arrayListOf(
                                                        GroupMemberShutUpInfo().apply {
                                                            this.uid = uid
                                                            timeStamp = seconds
                                                        }
                                                    )
                                                ) { c, msg ->
                                                    Utils.log("禁言: result code=$c msg=$msg uid=$uid")
                                                    runOnUi {
                                                        Utils.toast(
                                                            context,
                                                            if (c == 0) "已禁言 ${days}天${hours}时${minutes}分"
                                                            else "禁言失败 $c $msg"
                                                        )
                                                    }
                                                }
                                            }.onFailure {
                                                Utils.log("禁言: setMemberShutUp threw: $it")
                                                Utils.toast(context, "禁言调用异常: ${it.javaClass.simpleName}")
                                            }
                                        }
                                },
                            index
                        )
                    }.onFailure { Utils.log("禁言: profile row build failed: $it") }
                }
            }
        }
    }
}

private fun GroupScope.Text(label: String) = add<TextView>()
    .text(label)
    .textSize(8f)
    .textColor(M3.onSurface)
private fun GroupScope.Edit() = add<EditText>()
    .textSize(8f)
    .textColor(M3.onSurface)
    .apply {
        inputType = InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
