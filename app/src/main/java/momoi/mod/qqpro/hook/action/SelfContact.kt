package momoi.mod.qqpro.hook.action

import android.os.Bundle
import com.tencent.aio.main.fragment.ChatFragment
import com.tencent.qqnt.aio.adapter.api.impl.ContactApiNtImpl
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.msg.MsgUtil
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.util.Utils
import mqq.app.AppRuntime
import mqq.app.MobileQQ

val SelfContact = Contact(ChatType.PRIVATE, "", "").also {
    it.peerUid = MobileQQ.getMobileQQ().peekAppRuntime().currentUid
}