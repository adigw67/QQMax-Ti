# Group admin (kick / mute) + friend online status — feasibility findings

Status: **APIs explored and confirmed on-device** (real watch, 2026-07-01). Not yet implemented as
features — this is the implementation-ready reference. All three verdicts below were proven with a
temporary probe (`hook/ApiProbe.kt`, since reverted) that logged server result codes to
`qqpro_debug.log`.

## Summary verdict

| Capability | Method | On-device result | Viable? |
|---|---|---|---|
| Kick group member | `IGroupService.kickMember` | `code=0 msg=success`, member really removed | ✅ Yes |
| Whole-group mute (全员禁言) | `IKernelGroupService.setGroupShutUp` | `code=0` | ✅ Yes |
| Per-member mute (禁言单人) | `IKernelGroupService.setMemberShutUp` | `-10122 "Product does not have permission"` (OidbSvcTrpcTcp.0x1253) | ❌ **Blocked** — do not attempt |
| Friend/member online status | profile status subsystem (see below) | live presence for ~28 contacts, rich `termDesc` | ✅ Yes |

Note the asymmetry: **whole-group** mute is a *different, permitted* server command than
**per-member** mute (`0x1253`), which the watch product is not authorized for. The disabled
`hook/禁言.kt` was chasing per-member mute — that path is dead; whole-group is the live one.

---

## 1. Kick member — ✅

Exposed directly on the wrapper service, no impl cast needed.

```kotlin
val gs = KernelServiceUtil.b()   // IGroupService (runtime type: api.impl.GroupService)
gs?.kickMember(
    groupCode,                    // Long, e.g. 2166036744
    arrayListOf(uid),             // ArrayList<String> of member UIDs (u_xxx), NOT uins
    /* refuseForever = */ false,  // true = permanent ban
    /* kickReason   = */ "",
) { code, msg, results ->         // IKickMemberOperateCallback
    // code=0 msg=success; results: ArrayList<KickMemberResult> each { uid, result:Int }
}
```

- Input is **UID**, not uin. Resolve uin→uid from the group member list
  (`getAllMemberList` → `GroupMemberListResult.infos` is `HashMap<uid, MemberInfo>`; each
  `MemberInfo` has `.uin: Long`, `.uid: String`, `.role: MemberRole`, `.nick`).
- Callback: `IKickMemberOperateCallback.onResult(int code, String msg, ArrayList<KickMemberResult>)`.
- `KickMemberResult { int result; String uid; }` — per-target result (0 = ok).

### Suggested UX
- Gate on self role = `MemberRole.OWNER || MemberRole.ADMIN` (see role check pattern in `hook/禁言.kt`
  via `CurrentGroupMembers.get(SelfContact.peerUid) { it.role }`).
- Add a **移出本群** action to the member profile card (`ProfileCardFragment`, same injection point
  `禁言.kt` uses) and/or the member long-press menu. Confirm dialog before firing.

---

## 2. Whole-group mute (全员禁言) — ✅

Native-only method — reach it by casting the wrapper to its impl and pulling the kernel service off
`BaseService`:

```kotlin
val gs = KernelServiceUtil.b() ?: return
val native = (gs as com.tencent.qqnt.kernel.api.impl.GroupService).service   // IKernelGroupService?
native?.setGroupShutUp(groupCode, /* shutUp = */ true) { code, msg ->        // IOperateCallback
    // code=0 => accepted
}
```

- `setGroupShutUp(long groupCode, boolean shutUp, IOperateCallback)`.
- Probe called it with `false` (harmless un-mute) and got `code=0`, confirming the watch product HAS
  permission for this command. Muting for real (`true`) should be verified visually once during
  implementation (it affects the whole group / notifies members, so don't toggle it gratuitously).
- Current mute state is readable from the group detail (`shutUpAllTimestamp` — see the existing
  `group-mute-input-bar` feature / `GroupAIOHelper.b[peerUid].shutUpAllTimestamp`) to render the
  toggle's on/off state and to know when to hide the input bar.

### Suggested UX
- Owner/admin-only **全员禁言** switch in group settings (or the long-press/attachment menu).
- Do **not** revive per-member mute UI — `setMemberShutUp` is server-blocked (`-10122`).

### The native-service cast pattern (reusable)
`KernelServiceUtil.b()` returns `IGroupService`, whose runtime type is
`com.tencent.qqnt.kernel.api.impl.GroupService extends BaseService<IKernelGroupListener,
IKernelGroupService>`. `BaseService.getService()` (Kotlin `.service`) returns the native
`IKernelGroupService`, which exposes the full method set (`setGroupShutUp`, `setMemberShutUp`,
`modifyMemberRole`, `kickMember`, …) that the wrapper interface only partially re-exports.
`.service` is **nullable** — use `?.`.

The same pattern reaches the native profile service (below): `(KernelServiceUtil.d() as
com.tencent.qqnt.kernel.api.impl.ProfileService).service : IKernelProfileService?`.

---

## 3. Friend / member online status — ✅

The synchronous `getCoreAndBaseInfo` returns a `UserSimpleInfo` whose `.status` is **empty** — do not
rely on it. Presence lives in the profile *status* subsystem and is delivered by push.

### Reliable path
```kotlin
val profile = KernelServiceUtil.d() ?: return              // IProfileService (impl: ProfileService)
profile.H(listener)                                        // register IKernelProfileListener (H = add)
val native = (profile as com.tencent.qqnt.kernel.api.impl.ProfileService).service
native?.startStatusPolling(true)                           // REQUIRED — kernel won't push otherwise

// Bulk push arrives on the listener (fires in bulk shortly after polling starts):
//   IKernelProfileListener.onStatusUpdate(HashMap<uid, StatusInfo>)
// Also available:
//   native?.getStatusInfo("caller", arrayListOf(uid...))  // sync HashMap<uid, StatusInfo> once warm
//   native?.getStatus(uid) { code, msg -> }               // async single; result via onStatusUpdate
//   native?.getSelfStatus { code, msg -> }                // self; result via onSelfStatusChanged
```

`IKernelProfileListener` (6 methods; the relevant ones):
- `onStatusUpdate(HashMap<String, StatusInfo>)` — **primary** presence push.
- `onStatusAsyncFieldUpdate(HashMap<String, StatusInfo>)` — late-arriving fields.
- `onSelfStatusChanged(StatusInfo)`.
- `onProfileSimpleChanged(HashMap<String, UserSimpleInfo>)` — carries `.status` too.
- (`onStrangerRemarkChanged`, `onUserDetailInfoChanged` — unused for this.)

### `StatusInfo` fields (all public, un-obfuscated)
- `status: Int` — **10 = online, 20 = offline** (observed). Other values exist for away/busy states.
- `extStatus: Int` — non-zero = a custom/extended status (observed 1000, 1011, 2019, …).
- `termDesc: String` — ready-to-display, e.g. **"手机在线", "电脑在线", "平板在线", "TIM在线"** (may be
  empty even when online — fall back to a generic dot on `status==10`).
- `termType: Int` — device code (e.g. 65799 phone, 65793 pc, 65805 tablet, 77570 TIM, 78082 …) — use
  to pick a device icon.
- `netType: Int` — network code; also `eNetworkType`, `batteryStatus`, `iconType`, `customStatus`,
  `musicInfo`, `uin`, `uid`, `showName`, `setTime`.

### Observed sample (real, from the probe)
```
status=10 termDesc=手机在线 termType=65799   (online, phone)
status=10 termDesc=电脑在线 termType=65793   (online, pc)
status=10 termDesc=平板在线 termType=65805   (online, tablet)
status=10 termDesc=TIM在线  termType=77570   (online via TIM)
status=20 termDesc=        termType=0       (offline)
```

### Suggested UX / architecture
- On app start (or when the contacts list / a chat opens), register one shared listener + call
  `startStatusPolling(true)` once; keep a global `uid -> StatusInfo` cache updated from
  `onStatusUpdate` (mirror of the `CurrentGroupMembers`/singleton pattern in `hook/action/`).
- Render: online dot + `termDesc` on the **profile card**; dot / grey-out per row in the **contact
  list**; peer status line in the **chat titlebar** (private chats). Group members' presence is also
  delivered, so group @mention / member lists can show it too.
- Listener class must be a top-level/`object` helper (public) — not an anonymous class inside a
  `@Mixin` method body (that crashes with `IllegalAccessError`; see the mixin-anon-class rule).

---

## Reference: how to obtain each service
`com.tencent.qqnt.msg.KernelServiceUtil` static getters:
`a()`=buddy, `b()`=group, `c()`=msg, `d()`=profile, `e()`=richmedia, `f()`=kernelservice,
`g()`=wrappersession. Each wrapper impl extends `BaseService<Listener, NativeService>` and exposes the
full native interface via `.service` (getService()).

## Test harness note (for redoing the probe)
The probe was `hook/ApiProbe.kt` + a one-line trigger in `hook/action/CurrentContact.kt`
(`if (it.c == "<groupCode>") ApiProbe.runOnce()`), fired once when the target group opened, logging
via `Utils.log` to `/sdcard/Android/data/com.tencent.qqlite/cache/qqpro_debug.log`. Both were reverted
after confirming the findings above. Rebuild/install with `./gradlew MixinApk-debug` then
`adb pull` the log.
