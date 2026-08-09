package momoi.mod.qqpro.msg

import android.net.Uri
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.ext.PicContentViewUtil
import com.tencent.watch.aio_impl.ui.cell.pic.WatchPicMsgItem

fun PicElement.getImageUrl(): String {
    val parsedUrl = "$IMAGE_HTTP_HOST$originImageUrl".toUri()
    val imageAppid = parsedUrl.getQueryParameter("appid")
    val isNTV2 = imageAppid in listOf("1406", "1407")
    val imageFileId = parsedUrl.getQueryParameter("fileid")

    if (originImageUrl.isNotEmpty() && isNTV2 && imageFileId != null) {
        val rkeyData = getRkeyData()
        return "$IMAGE_HTTP_HOST$originImageUrl"
    }
    return getImageUrlFromMd5(md5HexStr, md5HexStr)
}

const val IMAGE_HTTP_HOST = "https://gchat.qpic.cn"
const val IMAGE_HTTP_HOST_NT = "https://multimedia.nt.qq.com.cn"

private class RKeyData(
    var private_rkey: String = "CAQSKAB6JWENi5LM_xp9vumLbuThJSaYf-yzMrbZsuq7Uz2qEc3Rbib9LP4",
    var group_rkey: String = "",
    var online_rkey: Boolean = false
)

private fun getRkeyData() = RKeyData()
private fun getImageUrlFromParsedUrl(imgFileId: String, appid: String, rkeyData: RKeyData): String {
    val rkey = if (appid == "1406") rkeyData.private_rkey else rkeyData.group_rkey
    return if (rkeyData.online_rkey) {
        "$IMAGE_HTTP_HOST_NT/download?appid=$appid&fileid=$imgFileId&rkey=$rkey"
    } else {
        "$IMAGE_HTTP_HOST/download?appid=$appid&fileid=$imgFileId&rkey=$rkey&spec=0"
    }
}
private fun getImageUrlFromMd5(fileMd5: String?, md5HexStr: String?): String {
    if (fileMd5 != null || md5HexStr != null) {
        return "$IMAGE_HTTP_HOST/gchatpic_new/0/0-0-${(fileMd5 ?: md5HexStr ?: "").uppercase()}/0"
    }
    return ""
}