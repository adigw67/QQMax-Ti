package momoi.mod.qqpro

import android.view.View
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.msg.api.impl.MsgServiceImpl
import com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl
import com.tencent.watch.aio_impl.ext.MsgListUtilKt
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.hook.openAddSearch
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.util.Json
import kotlin.random.Random

object MsgUtil {
    val msgService = MsgServiceImpl()
    val msgUtilApi = MsgUtilApiImpl()

    fun summary(elements: List<MsgElement>): CharSequence {
        if (elements.isEmpty() || elements[0].elementType == ElementType.GREY_TIP) {
            //subElementType == 1
            return "[原消息已被撤回]"
        }
        elements.forEach { ele ->
            ele.picElement?.let {
                ele.picElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = "[图片]"
                }
            }
            ele.multiForwardMsgElement?.let {
                ele.multiForwardMsgElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = "[聊天记录]"
                }
            }
            ele.videoElement?.let {
                ele.videoElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = "[视频]"
                }
            }
            ele.fileElement?.let {
                ele.fileElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = "[文件]"
                }
            }
            ele.pttElement?.let {
                ele.pttElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = "[语音]"
                }
            }
            ele.arkElement?.let {
                ele.arkElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = Json(it.bytesData).str("prompt") ?: "[卡片信息]"
                }
            }
            /*
            ele.faceElement?.let {
                ele.faceElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = it.faceText
                }
            }
             */
            ele.marketFaceElement?.let {
                ele.marketFaceElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = it.faceName
                }
            }
            /*
            ele.faceBubbleElement?.let {
                ele.faceBubbleElement = null
                ele.elementType = ElementType.TEXT
                ele.textElement = TextElement().apply {
                    content = it.faceSummary
                }
            }
            */
        }
        return MsgListUtilKt.a(elements)
    }

    fun summary(record: MsgRecord) = summary(record.elements)
}

fun View.showDialog(dialog: MyDialogFragment) {
    dialog.show(
        WatchPicElementExtKt.W(this)!!.childFragmentManager,
        Random.nextInt().toString()
    )
}

fun View.showFragment(fragment: androidx.fragment.app.DialogFragment) {
    fragment.show(
        WatchPicElementExtKt.W(this)!!.childFragmentManager,
        Random.nextInt().toString()
    )
}

/** Confirm before opening [url] in the browser, via a full-screen watch dialog. */
fun View.confirmOpenUrl(url: String) {
    runCatching {
        showDialog(momoi.mod.qqpro.hook.view.LinkOpenFragment(url))
    }.onFailure {
        // No host fragment to attach to — open directly rather than swallow the tap.
        momoi.mod.qqpro.util.Utils.openUrl(url)
    }
}

/**
 * Confirm before searching a tapped number (6–15 digits). On confirm, opens the
 * add-friend/group search pad prefilled with [number] (search returns both).
 */
fun View.confirmSearchNumber(number: String) {
    val v = this
    runCatching {
        showDialog(momoi.mod.qqpro.hook.view.SearchNumberFragment(number) {
            v.openAddSearch(number)
        })
    }
}