package momoi.mod.qqpro.hook.action

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tencent.aio.api.factory.IAIOFactory
import com.tencent.aio.base.chat.ChatPie
import com.tencent.aio.main.fragment.ChatFragment
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.hook.ChatBgApplier
import momoi.mod.qqpro.util.Utils

val Contact.isGroup get() = this.chatType == ChatType.GROUP
val CurrentContact = Contact(0, "", "")

// Per-member info + our OWN stub-filtering level cache.
//
// The kernel's per-member detail cache is unreliable: getMemberInfoForMqq(false) returns the REAL
// memberLevel on a clean entry, but getAllMemberList (needed for @mention) overwrites entries with a
// level=1 STUB, after which reads return 1. We can't stop that native overwrite. So we keep our own
// cache of the best (highest) level ever observed per (group, uid): a stub (1) can never lower a real
// value, and the value is persisted so it survives app restarts (otherwise reopen shows stubs until a
// rare clean read happens). Genuine level-1 members simply stay 1.
object CurrentMemberInfo {
    val map = mutableMapOf<String, MemberInfo>()
    private val levelPrefs by lazy {
        Utils.application.getSharedPreferences("qqpro_levels", android.content.Context.MODE_PRIVATE)
    }

    private fun key(uid: String) = "${CurrentContact.peerUid}_$uid"

    fun get(uid: String, callback: (MemberInfo) -> Unit) {
        if (!CurrentContact.isGroup) {
            return
        }
        // Confident cached value (a real level > 1) — return without re-querying.
        map[uid]?.takeIf { it.memberLevel > 1 }?.let {
            callback(it)
            return
        }
        KernelServiceUtil.b()?.getMemberInfoForMqq(
            CurrentContact.peerUid.toLong(),
            arrayListOf(uid),
            false
        ) { _, _, result ->
            // infos is a HashMap<uid, MemberInfo>. Look up the EXACT uid we asked for — never
            // .values.firstOrNull(): the kernel can return a batch (or, for an uncached uid, an
            // arbitrary cached member), and firstOrNull() then hands back the SAME wrong member for
            // many different uids → every cell shows one identical (wrong) name, cached under the
            // wrong uid so it survives scrolling. The avatar path (bindAvatar, keyed on senderUid)
            // is unaffected, which is why only the name was wrong.
            val info = result.infos[uid] ?: run {
                map[uid]?.let(callback)
                return@getMemberInfoForMqq
            }
            // Filter the stub: never let a lower (stub) level overwrite a higher real one. Best =
            // max(this read, persisted, in-memory). Persist whenever it grows so reopen keeps it.
            val raw = info.memberLevel
            val stored = levelPrefs.getInt(key(uid), 0)
            val best = maxOf(raw, stored, map[uid]?.memberLevel ?: 0)
            if (info.memberLevel != best) info.memberLevel = best
            if (best > stored) levelPrefs.edit().putInt(key(uid), best).apply()
            Utils.log("CurrentMemberInfo: uid=$uid raw=$raw stored=$stored best=$best nick=${info.nick}")
            map[uid] = info
            callback(info)
        }
    }
}

object CurrentGroupMembers {
    // Full member list (names only — levels here are stubs), kept for the @mention autocomplete and
    // member-picker features. NOT used for the level path; that goes through CurrentMemberInfo.
    var info: Map<String, MemberInfo>? = null
    val callbacks = mutableListOf<()-> Unit>()

    // clearLevels: only true when the group actually changed. Keeping the per-member level map
    // across same-group re-entries makes correct levels immune to any later cache pollution.
    fun reset(clearLevels: Boolean) {
        info = null
        callbacks.clear()
        if (clearLevels) CurrentMemberInfo.map.clear()
    }

    fun get(id: String, callback: (MemberInfo)-> Unit) {
        if (!CurrentContact.isGroup) return
        // Level ON: per-member fetch (CurrentMemberInfo) for the correct, stub-filtered level.
        // Level OFF: role/title/name only — serve from the bulk member list (no per-member queries),
        // waiting for it to load if needed.
        val ready = info
        if (ready != null && ready[id] != null) {
            // Bulk list serves name + role/头衔 immediately — it does NOT carry memberLevel
            // (0/stub there), so with levels enabled the tag would be missing until the async
            // per-member fetch lands. Show the role tag now, then upgrade with LV when it does.
            callback(ready[id]!!)
            if (Settings.showMemberLevel.value) {
                CurrentMemberInfo.get(id) { leveled ->
                    if (leveled.memberLevel > 0) callback(leveled)
                }
            }
        } else if (Settings.showMemberLevel.value) {
            // Bulk not loaded yet — fall back to the per-member query only.
            CurrentMemberInfo.get(id, callback)
        } else {
            if (ready == null) {
                callbacks.add { info?.get(id)?.let(callback) }
            } else {
                ready[id]?.let(callback)
            }
        }
    }
}

@Mixin
class Hook(p0: IAIOFactory) : ChatPie(p0) {
    override fun a(
        fragment: ChatFragment,
        inflater: LayoutInflater,
        container: ViewGroup,
        isPreload: Boolean
    ): View {
        e?.b?.b?.let {
            val groupChanged = CurrentContact.peerUid != it.c
            CurrentContact.chatType = it.b
            CurrentContact.peerUid = it.c
            CurrentContact.guildId = it.d
            CurrentGroupMembers.reset(clearLevels = groupChanged)
            // 聊天背景按本会话真实 peer 应用——onViewCreated 时 CurrentContact 可能还是上一个
            // 会话的值（背景串的根因），这里拿到准确联系人后重挂一次（幂等）。
            ChatBgApplier.applyPeerBackground(it.c)
            if (it.b == ChatType.GROUP) {
                // Bulk member list for @mention / picker only (no levels). It WILL overwrite the
                // kernel per-member cache with level=1 stubs, but CurrentMemberInfo now filters
                // those out (keeps the max level ever seen, persisted), so it no longer matters.
                //
                // Served from GroupMemberCache: on re-entry the cached list lands SYNCHRONOUSLY here
                // so @mention linkify works immediately; the background server fetch only re-fires
                // this block when the member count changed.
                GroupMemberCache.load(CurrentContact.peerUid.toLong()) { members ->
                    CurrentGroupMembers.info = members
                    CurrentGroupMembers.callbacks.forEach { it() }
                    CurrentGroupMembers.callbacks.clear()
                    // Re-linkify cells that were bound before the member list arrived.
                    momoi.mod.qqpro.hook.relinkifyAtMembers()
                }
            }
        }
        return super.a(fragment, inflater, container, isPreload)
    }
}
