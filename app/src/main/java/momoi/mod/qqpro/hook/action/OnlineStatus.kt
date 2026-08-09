package momoi.mod.qqpro.hook.action

import com.tencent.qqnt.kernel.api.impl.ProfileService
import com.tencent.qqnt.kernel.nativeinterface.CoreInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelProfileListener
import com.tencent.qqnt.kernel.nativeinterface.StatusInfo
import com.tencent.qqnt.kernel.nativeinterface.UserDetailInfo
import com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global online-presence cache for friends and group members. Presence is NOT on the synchronous
 * `getCoreAndBaseInfo` (that `.status` is always empty) — it lives in the profile *status* subsystem
 * and is delivered by push. See GROUP_ADMIN_AND_ONLINE_STATUS_PLAN.md §3 for the full findings.
 *
 * Flow: [start] registers one shared [IKernelProfileListener] on the profile wrapper ([KernelServiceUtil.d]
 * `.H`) and calls native `startStatusPolling(true)` (REQUIRED — the kernel won't push otherwise). Bulk
 * presence then arrives on `onStatusUpdate(HashMap<uid, StatusInfo>)`; we merge it into [cache] and poke
 * [observers] so any visible UI (list rows, titlebar, profile) re-renders. [prime] warms the cache
 * synchronously for specific uids once polling is running.
 *
 * Kept as a top-level `object` (mirrors [CurrentGroupMembers] etc.) — the listener must NOT be an
 * anonymous class inside a @Mixin body (that crashes with IllegalAccessError).
 */
object OnlineStatus {
    private val cache = ConcurrentHashMap<String, StatusInfo>()
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var started = false

    /** Register a UI callback fired (on the UI thread) whenever presence data changes. Idempotent. */
    fun addObserver(cb: () -> Unit) { if (cb !in observers) observers.add(cb) }
    fun removeObserver(cb: () -> Unit) { observers.remove(cb) }

    fun get(uid: String?): StatusInfo? = uid?.let { cache[it] }

    /** status: 10 = online, 20 = offline (observed). Treat anything that isn't offline/unknown as online. */
    fun isOnline(uid: String?): Boolean {
        val s = uid?.let { cache[it] } ?: return false
        return s.status != 0 && s.status != 20
    }

    /** True once we have ANY presence record for [uid] (so callers can show a grey "offline" dot only
     *  when the state is actually known, not merely un-fetched). */
    fun known(uid: String?): Boolean = uid != null && cache.containsKey(uid)

    /** Ready-to-display description, e.g. "手机在线"; falls back to a generic 在线/离线 when termDesc is
     *  empty (the kernel sometimes omits it even while online). null when nothing is known yet. */
    fun describe(uid: String?): String? {
        val s = uid?.let { cache[it] } ?: return null
        val d = s.termDesc
        if (!d.isNullOrEmpty()) return d
        return if (s.status != 0 && s.status != 20) "在线" else "离线"
    }

    /**
     * Register the listener + turn on polling. Safe to call repeatedly — only latches [started] once
     * polling has ACTUALLY begun, so an early call before the kernel is ready (e.g. at app launch)
     * doesn't wedge it off; a later call from a status surface retries.
     */
    fun start() {
        if (started) return
        val profile = runCatching { KernelServiceUtil.d() }.getOrNull() ?: return
        val native = runCatching { (profile as? ProfileService)?.service }.getOrNull() ?: return
        runCatching {
            profile.H(Listener)
            native.startStatusPolling(true)
            started = true
            Utils.log("OnlineStatus: started (status polling on)")
        }.onFailure { Utils.log("OnlineStatus: start failed: $it") }
    }

    /** Warm the cache for [uids] (getStatusInfo returns the kernel's cached map once polling is warm). */
    fun prime(uids: Collection<String>) {
        if (uids.isEmpty()) return
        val native = runCatching { (KernelServiceUtil.d() as? ProfileService)?.service }.getOrNull() ?: return
        runCatching {
            val map = native.getStatusInfo("qqpro", ArrayList(uids.toSet()))
            if (!map.isNullOrEmpty()) { merge(map); notifyObservers() }
        }.onFailure { Utils.log("OnlineStatus: prime failed: $it") }
    }

    private fun merge(map: HashMap<String, StatusInfo>) {
        for ((uid, info) in map) if (!uid.isNullOrEmpty() && info != null) cache[uid] = info
    }

    private fun notifyObservers() {
        runOnUi { observers.forEach { runCatching { it() } } }
    }

    private object Listener : IKernelProfileListener {
        override fun onStatusUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) { merge(map); notifyObservers() }
        }
        override fun onStatusAsyncFieldUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) { merge(map); notifyObservers() }
        }
        override fun onSelfStatusChanged(statusInfo: StatusInfo?) {}
        override fun onProfileSimpleChanged(map: HashMap<String, UserSimpleInfo>?) {
            // UserSimpleInfo carries .status too, but termDesc/termType live on StatusInfo — ignore here.
        }
        override fun onStrangerRemarkChanged(map: HashMap<String, CoreInfo>?) {}
        override fun onUserDetailInfoChanged(userDetailInfo: UserDetailInfo?) {}
    }
}
