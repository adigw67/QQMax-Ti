package momoi.mod.qqpro.hook.action

import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import momoi.mod.qqpro.QQNT
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Persistent cache of group member lists, keyed by group id. Lets the member COUNT (rich titlebar)
 * and @mention linkify ([relinkifyAtMembers]) work the INSTANT a group chat opens — served from the
 * cache — instead of blank until the async server fetch returns.
 *
 * On open we still kick off a background [QQNT.Group.getMemberList]; the fresh result silently keeps
 * the cache current, but consumers are only re-rendered when the member COUNT actually changed (per
 * request) — an unchanged group never re-renders/flickers.
 *
 * [MemberInfo] is [java.io.Serializable], so the whole map is Java-serialized to filesDir (survives
 * app restarts and cache clears). Deserialize is guarded: any class-shape mismatch just drops the
 * stale file and falls back to a fresh server fetch.
 */
object GroupMemberCache {
    private val mem = ConcurrentHashMap<Long, Map<String, MemberInfo>>()
    // Single background thread: serializes disk writes so concurrent opens never race on a file.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qqpro-group-member-cache").apply { isDaemon = true }
    }

    private val dir: File by lazy {
        File(Utils.application.filesDir, "qqpro_group_members").apply { mkdirs() }
    }
    private fun file(groupId: Long) = File(dir, "$groupId.ser")

    /** Cached members for [groupId] (in-memory, lazily loaded from disk), or null if none cached. */
    fun cached(groupId: Long): Map<String, MemberInfo>? {
        mem[groupId]?.let { return it }
        return loadFromDisk(groupId)?.also { mem[groupId] = it }
    }

    /**
     * Serve [onResult] with the cached list immediately (when present), then fetch from the server.
     * The fresh result always replaces the cache, but [onResult] is invoked a SECOND time only when
     * the member count changed (or there was no cache to begin with) — so a re-entry into an
     * unchanged group renders once, from cache, with no server-driven flicker.
     */
    fun load(groupId: Long, onResult: (Map<String, MemberInfo>) -> Unit) {
        val cached = cached(groupId)
        if (cached != null) onResult(cached)
        runCatching {
            QQNT.Group.getMemberList(groupId) { res ->
                val fresh = res.infos ?: return@getMemberList
                val changed = cached == null || cached.size != fresh.size
                store(groupId, fresh)
                if (changed) onResult(fresh)
            }
        }.onFailure { Utils.log("GroupMemberCache.load($groupId) failed: $it") }
    }

    fun store(groupId: Long, members: Map<String, MemberInfo>) {
        mem[groupId] = members
        // Snapshot now (on the calling thread) so a later mutation can't corrupt the serialized form.
        val snapshot = HashMap(members)
        io.execute {
            runCatching {
                ObjectOutputStream(file(groupId).outputStream().buffered()).use { it.writeObject(snapshot) }
            }.onFailure { Utils.log("GroupMemberCache save($groupId) failed: $it") }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadFromDisk(groupId: Long): Map<String, MemberInfo>? {
        val f = file(groupId)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            ObjectInputStream(f.inputStream().buffered()).use { it.readObject() as HashMap<String, MemberInfo> }
        }.getOrElse {
            Utils.log("GroupMemberCache load($groupId) failed (dropping stale file): $it")
            runCatching { f.delete() }
            null
        }
    }
}
