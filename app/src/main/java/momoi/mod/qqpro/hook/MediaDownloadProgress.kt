package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.watch.aio_impl.coreImpl.payLoad.AIOMsgItemPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared rich-media download progress (0..1) per message, keyed by msgId. The cell hooks
 * ([VideoPlay], [ImagePlay]) publish it from the kernel's RICH_MEDIA bind payload as the cell rebinds;
 * the open viewer reads it to show DETERMINATE progress.
 *
 * The rebind payload is a `HashMap` keyed by `RICH_MEDIA_PAYLOAD` whose value is a
 * [AIOMsgItemPayload.RichMediaPayload] carrying a [FileTransNotifyInfo] (its field `a`) with
 * `fileProgress`/`totalSize`. [capture] digs that out of whatever payload shape arrives.
 */
object MediaDownloadProgress {
    private val map = ConcurrentHashMap<Long, Float>()

    fun get(msgId: Long): Float? = map[msgId]
    fun clear(msgId: Long) { map.remove(msgId) }

    /** Extract download progress from bind [payloads] and store it under [msgId]. Fail-safe. */
    fun capture(msgId: Long, payloads: List<Any>) {
        runCatching {
            for (p in payloads) {
                val candidates: Collection<Any?> = if (p is Map<*, *>) p.values else listOf(p)
                for (c in candidates) {
                    val info: FileTransNotifyInfo? = when (c) {
                        is AIOMsgItemPayload.RichMediaPayload -> c.a
                        is FileTransNotifyInfo -> c
                        else -> null
                    }
                    if (info != null && info.totalSize > 0) {
                        map[msgId] = (info.fileProgress.toFloat() / info.totalSize).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
}
