package momoi.mod.qqpro.hook.aio_cell

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import momoi.anno.mixin.Mixin

@Mixin
class MsgRecordEx : MsgRecord() {
    var forwardData: ForwardMsgData? = null
}