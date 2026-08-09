package momoi.mod.qqpro.hook.contact

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.watch.contact.mvi.ContactListIntent
import com.tencent.qqnt.watch.contact.ui.ContactListFragment
import com.tencent.qqnt.watch.contact.ui.item.AddFriendItem
import com.tencent.qqnt.watch.contact.ui.item.ContactBaseItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.findNavControllerFromTree
import momoi.mod.qqpro.util.Utils

/**
 * Contacts page (2nd main page) redesign for a small screen — see [Settings.contactSections].
 *
 * The stock list is a flat [加好友/群聊, 我的通知, ...friends..., ...groups...] with a single
 * combined notification entry whose navigation opens an ambiguous selector. This hook:
 *  - inserts "好友" / "群聊" section headers so friends and groups are visually grouped
 *    (which is why [GroupItemHook] can drop the redundant trailing icon on every group row);
 *  - replaces the single "我的通知" with two entries, "好友通知" and "群通知", each carrying its own
 *    unread count and navigating straight to the right screen (handled in [e0]).
 *
 * Implemented by observing the same view-model state and re-submitting a rebuilt list to the
 * adapter (subclassing the ListAdapter/ViewModel directly is unsafe — their generic bridge methods
 * collide under the bytecode patcher). The stock observer still runs first; our submitList wins.
 *
 * The target classes are R8-minified (no usable Kotlin metadata), so obfuscated fields are read by
 * their raw single-letter names via reflection:
 *  - ContactListFragment.h = viewModel (BaseViewModel), .i = adapter (ListAdapter)
 *  - BaseViewModel.c = mUiState (LiveData<ContactListState>)
 *  - ContactListState.a = friends (List<ContactItem>), .b = groups (List<GroupItem>), .c = combined count
 *  - ContactVM.e = repo (ContactRepo); ContactRepo.h = friend unread, .i = group unread
 *  - ContactListIntent.OnUseClick.a = the clicked item
 */
@Mixin
class ContactListFragmentHook : ContactListFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Publish ourselves so a repeat-tap on the contacts nav cell can open our notification screen.
        momoi.mod.qqpro.hook.MainNav.contactFragment = java.lang.ref.WeakReference<Any>(this)
        if (!Settings.contactSections.value) return
        runCatching {
            val viewModel = field("h")!!
            val adapter = field("i")!!
            @Suppress("UNCHECKED_CAST")
            val liveData = viewModel.field("c") as LiveData<Any?>
            // The stock fragment registers its own observer in super.onCreate that submits the
            // un-injected list ([加好友, 我的通知, ...]) on every emit. If we just add a second
            // observer, BOTH fire on each emit — and on a ViewPager resume LiveData re-delivers the
            // last state, so the stock list briefly replaces ours (dropping our injected 通知/section
            // rows from the top) before we re-inject, which yanks the scroll by two positions.
            // Remove the stock observer so OURS is the single submit path: one diff per real state
            // change, and an idempotent resume re-deliver becomes a no-op (no scroll movement).
            liveData.removeObservers(this)
            liveData.observe(this, Observer { state ->
                state ?: return@Observer
                runCatching {
                    rebuild(state, viewModel, adapter)
                }.onFailure { Utils.log("ContactListFragmentHook: rebuild/submit failed: $it") }
            })
        }.onFailure { Utils.log("ContactListFragmentHook onCreate: $it") }
    }

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = super.Y(inflater, container, savedInstanceState)
        // Append the full online-status description to friend rows (independent of contactSections).
        if (Settings.onlineStatusContactList.value && v is RecyclerView) {
            runCatching {
                momoi.mod.qqpro.hook.action.ContactOnlineStatusStyler(v, field("i")!!).install()
            }.onFailure { Utils.log("ContactListFragmentHook status styler: $it") }
        }
        if (Settings.contactSections.value && v is RecyclerView) {
            runCatching { v.addOnChildAttachStateChangeListener(HeaderStyler(v, field("i")!!)) }
                .onFailure { Utils.log("ContactListFragmentHook Y: $it") }
            // Y() returns a bare RecyclerView; wrap it with the Material top bar and return the
            // WRAPPER as the fragment's view, so ViewPager2's FragmentStateAdapter keeps the bar on
            // every rebind (a post-hoc reparent gets orphaned — the bar vanished on page switch).
            if (Settings.materialContactsList.value) {
                return runCatching { ContactTopBar.wrap(this, v) }
                    .onFailure { Utils.log("ContactListFragmentHook wrap bar: $it") }
                    .getOrDefault(v)
            }
        }
        return v
    }

    /**
     * Rebuild the list (好友/群聊 sections only — the 加好友/通知 action rows now live in
     * [ContactTopBar]) and hand it, plus the notification counts, to the bar. The bar owns the actual
     * (search-filtered) submit so a state change and a search keystroke share one submit path.
     */
    private fun rebuild(state: Any, viewModel: Any, adapter: Any) {
        @Suppress("UNCHECKED_CAST")
        val friends = state.field("a") as List<ContactBaseItem>
        @Suppress("UNCHECKED_CAST")
        val groups = state.field("b") as List<ContactBaseItem>
        val (friendCount, groupCount) = readNotifyCounts(state, viewModel)
        // Publish to the main-page navigation so the contacts tab can show an unread badge, and so a
        // repeat-tap on the contacts nav cell can open whichever notification screen has a count.
        momoi.mod.qqpro.hook.MainNav.contactUnread = friendCount + groupCount
        momoi.mod.qqpro.hook.MainNav.contactFriendUnread = friendCount
        momoi.mod.qqpro.hook.MainNav.contactGroupUnread = groupCount
        momoi.mod.qqpro.hook.MainNav.refresh()

        Utils.log("ContactListFragmentHook rebuild: friends=${friends.size} groups=${groups.size}")
        val out = ArrayList<ContactBaseItem>(friends.size + groups.size + 5)
        val material = Settings.materialContactsList.value
        if (!material) {
            // Action rows live in the top bar when Material mode is on; restore them inline when off.
            out.add(AddFriendItem())
            out.add(FriendNotifyItem(friendCount))
            out.add(GroupNotifyItem(groupCount))
        }
        // Stable item-0 anchor — fixes "contacts list starts scrolled to the groups section". The VM
        // emits state incrementally (groups can land before friends), and when the friends section
        // later prepends above an already-rendered groups section, LinearLayoutManager preserves the
        // groups view as the anchor, leaving the list scrolled past the friends. Stock never hits this
        // because its item 0 (加好友) is always present; in Material mode the action rows moved to the
        // top bar, so we keep the 好友 header permanently as the first item to anchor the list at top.
        // (No scrollToPosition — the anchor keeps it pinned; see the single-observer path in onCreate.)
        if (friends.isNotEmpty() || material) {
            out.add(SectionHeaderItem("好友"))
            out.addAll(friends)
        }
        if (groups.isNotEmpty()) {
            out.add(SectionHeaderItem("群聊"))
            out.addAll(groups)
        }
        if (Settings.materialContactsList.value) {
            ContactTopBar.updateList(adapter, out, friendCount, groupCount)
        } else {
            adapter.javaClass.getMethod("submitList", List::class.java).invoke(adapter, out)
        }
    }

    /** Top-bar 加好友/群聊 button — reuse the stock dispatch (it opens the add-friend option selector). */
    fun topAddFriend() {
        runCatching { e0(ContactListIntent.OnUseClick(AddFriendItem())) }
            .onFailure { Utils.log("ContactListFragmentHook topAddFriend: $it") }
    }

    /** Top-bar 好友通知 button — friend-request notification screen. */
    fun topFriendNotify() {
        navigate("select_fragment_to_add_friend", Bundle().apply { putInt("type", 5) })
    }

    /** Top-bar 群通知 button — group join/notification screen. */
    fun topGroupNotify() {
        navigate("aio_fragment_to_troop_nav", Bundle().apply { putInt("NAVIGATE_TYPE", 3) })
    }

    /** Friend vs group notification counts. The repo (ContactVM.e) tracks them separately as
     *  ContactRepo.h / .i; fall back to the combined state count (ContactListState.c) if unreadable. */
    private fun readNotifyCounts(state: Any, viewModel: Any): Pair<Int, Int> = runCatching {
        val repo = viewModel.field("e")!!
        (repo.field("h") as Int) to (repo.field("i") as Int)
    }.getOrElse { (state.field("c") as? Int ?: 0) to 0 }

    override fun e0(intent: ContactListIntent) {
        if (Settings.contactSections.value && intent is ContactListIntent.OnUseClick) {
            when (runCatching { intent.field("a") }.getOrNull()) {
                is FriendNotifyItem -> {
                    // add_friend graph, type=5 → friend notification screen
                    navigate("select_fragment_to_add_friend", Bundle().apply { putInt("type", 5) })
                    return
                }
                is GroupNotifyItem -> {
                    // troop graph, NAVIGATE_TYPE=3 → group notification screen
                    navigate("aio_fragment_to_troop_nav", Bundle().apply { putInt("NAVIGATE_TYPE", 3) })
                    return
                }
            }
        }
        super.e0(intent)
    }

    /** Navigate a nav-graph action (resolved by resource name) via the obfuscated NavController. */
    private fun navigate(actionResName: String, args: Bundle) {
        runCatching {
            val v = view ?: return
            val nav = v.findNavControllerFromTree() ?: run {
                Utils.log("ContactListFragmentHook: NavController not found"); return
            }
            val actionId = v.context.resources.getIdentifier(actionResName, "id", v.context.packageName)
            if (actionId == 0) { Utils.log("ContactListFragmentHook: id $actionResName not found"); return }
            // navigate(int destId, Bundle args, NavOptions options) — name obfuscated.
            val navigate = nav.javaClass.methods.firstOrNull { m ->
                val p = m.parameterTypes
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Bundle::class.java
            } ?: run { Utils.log("ContactListFragmentHook: navigate(int,Bundle,..) not found"); return }
            navigate.invoke(nav, actionId, args, null)
        }.onFailure { Utils.log("ContactListFragmentHook navigate $actionResName: $it") }
    }

    /** Read an obfuscated public field by its raw name (searches the class hierarchy). */
    private fun Any.field(name: String): Any? = javaClass.getField(name).get(this)
}
