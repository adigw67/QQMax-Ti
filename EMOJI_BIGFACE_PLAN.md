# Plan: Make newer emoji (classic + 大表情 animated) render on the watch

Status: **PROVEN working on-device via root-pushed resources.** This file is the spec to
re-implement it cleanly and ship it (resources bundled in the APK, no root needed). All the
prior experiment code has been reverted; implement fresh per this plan.

Related memory: `emoji-sysface-config`, `emoji-animated-render-path`, `qqpro-mixin-anon-class`,
`build-use-debug`, `qqpro-logging-qlog`.

NOTE: For THIS feature the user explicitly authorized bundling resources into the APK and even
modifying the ApkMixin build tool (overrides the usual "never edit the APK" rule, which still
applies to everything else).

---

## Problem

The watch (com.tencent.qqlite, an older QQ NT build) ships an emoji set that is older than the
phone (com.tencent.mobileqq). Two symptoms:

1. **Classic small sysface** — ~39 standard faces (/足球 /礼物 /磕头 /闪电 /祈祷 /K歌 …) don't
   render in received messages. Their static PNGs (`R.drawable.f_static_<AQLid>`) ARE bundled in
   the watch APK; only the config mapping (serverId→localId) is missing.
2. **大表情 (animated sysface / AniSticker)** — newer ids (≈ 418–470, e.g. 424/468/469) show an
   "unknown image" placeholder in chat. The watch's downloader caps at id 417 and the watch build
   lacks the newer per-id NT download mechanism, so it never gets these resources.

The emoji SELECTOR renders everything (its own live loader); only CHAT is broken.

---

## How the watch renders faces (verified)

- Config parsed by `com.tencent.mobileqq.emoticon.QQSysFaceResImpl.parseConfigData(JSONObject face,
  JSONObject aniSticker)` → fills `mServerToLocalMap`, `mConfigItemMap`, etc. Lookup by **QSid**
  (server id). For the 262+ range, **AQLid == QSid**.
- **Classic static** drawable: `QQSysFaceUtil.e(localId)` → `EmoJIConstant.STATIC_SYS_EMOJI_RESOURCE[localId]`
  = `R.drawable.f_static_<NNN>`. Array length ~360 (ids 0..359). f_static_* PNGs exist in-APK.
- **Chat 大表情** (the failing case) render via the SUPERFACE AniSticker cell (confirmed by
  `uiautomator dump`: ImageView `id=anisticker_bubble_view_emoticon`):
  - `com/tencent/watch/aio_impl/ui/cell/superface/WatchAniStickerItemCell.o()` builds
    `AniStickerHelper.Builder{ localId, stickerInfo, svgLocalPath, placeholderDrawable=R.drawable.aio_face_default }`.
  - `WatchAniStickerItemCell.p()` → `AniStickerHelper.f17799a.e(builder)` loads a **Lottie** into
    `AniStickerLottieView` (an AppCompatImageView). If the Lottie file is MISSING → shows
    `aio_face_default` = the "unknown image".
  - Lottie path = `AniStickerInfo.b()` → `QQSysAndEmojiResMgr.getAniStickerResPath(packId, aniStickerId)`
    = `filesDir/qq_emoticon_res/qlottie/<packId>/<aniStickerId>/<aniStickerId>.json`.
  - `AniStickerInfo` is built (in `QQSysFaceResImpl.getAniStickerInfo`) from the config item's
    `AniStickerPackId` / `AniStickerId`. **We control these via injected config.**
- `WatchAniStickerItemCell`, `AniStickerHelper`, `AniStickerLottieView` are all `final` → do NOT try
  to @Mixin them. **Not needed:** the renderer works unmodified once the Lottie file exists at the
  computed path.

Resource roots on device (internal, app-writable by the app itself; `adb root` works on the watch
for manual testing): `/data/data/com.tencent.qqlite/files/qq_emoticon_res/`
- `sysface_res/static/s<id>.png` (static frame, type 2)
- `sysface_res/apng/s<id>.png` (apng, type 4) — dir may need creating
- `qlottie/<packId>/<aniStickerId>/<aniStickerId>.json` (lottie, type 5)

---

## Source of the resources

Rooted phone (com.tencent.mobileqq, runs under Island **user 11**) has everything downloaded at:
`/data/user/11/com.tencent.mobileqq/files/emoji/BaseEmojiSyastems/EmojiSystermResource/<id>/`
with subdirs `lottie/<id>.json`, `apng/<id>.png`, `png/<id>.png` + `png/<id>_0.png` (the true single
static frame). ids present (have lottie/) include 360..469 (gaps: 414,418,428,433-449 partial).
Pull with `adb -s <phone> root` then `su -c cp ... /sdcard/...` then `adb pull`.

The classic-static 39 entries are derived by diffing the phone APK's bundled `assets/face_config.json`
`sysface` array against the watch's (`app/decompiled/apktool/assets/face_config.json`):
```python
phone=json.load(open('phone/assets/face_config.json'))   # from the phone APK
watch=json.load(open('app/decompiled/apktool/assets/face_config.json'))
w={e['QSid'] for e in watch['sysface']}
missing=[e for e in phone['sysface'] if e['QSid'] not in w]   # the 39
```
(Phone APK used: /home/ailife/Downloads/9.2.80_7c7d1008a4510c3d.apk)

---

## Implementation (clean, shippable)

### Part A — Classic static emoji (config-only, already proven trivially)
`hook/EmojiConfigPatch.kt` = `@Mixin QQSysFaceResImpl`, override `parseConfigData(face, aniSticker)`:
before `super`, take `face.optJSONArray("sysface")` and append the 39 missing entries (dedup by QSid).
super builds the maps; existing `f_static_*` drawables render them. Confirmed log:
`EmojiConfigPatch: injected N missing sysface entries`.

### Part B — 大表情 animated (config injection + bundled Lottie zip, unzip on first launch)

1. **Data prep (one-time, offline):** build a zip `bigface.zip` whose internal layout mirrors the
   watch res dir, for every newer animated id `<id>` (those the watch lacks, ~418..469 + any
   360..417 the watch's downloaded config lacks):
   - `qlottie/1/<id>/<id>.json`  ← phone `EmojiSystermResource/<id>/lottie/<id>.json`
   - (optional belt-and-suspenders) `sysface_res/static/s<id>.png` ← phone `png/<id>_0.png`,
     `sysface_res/apng/s<id>.png` ← phone `apng/<id>.png`
   Use packId **1** (tested working: file at `qlottie/1/<id>/<id>.json`; verify no clash with the
   watch's real pack-1 aniStickerIds, which are small 1..41 — our ids are ≥360 so safe). Also build
   an index/manifest (e.g. `bigface_index.json`) listing the ids + their config fields so the runtime
   can inject config without hardcoding.

2. **Bundle the zip into the APK assets.** The mod's own assets aren't normally added by ApkMixin
   (it only patches dex). Modify the build tool to inject the zip as an asset:
   - `ApkMixin/.../utils/ZipUtil.kt` already has `addOrReplaceFilesInZip(zipFile, Map<entryName,File>)`.
   - In `MixinPlugin` (the MixinApk task), after the dex is written and before signing, call it to add
     `assets/bigface.zip` (+ `assets/bigface_index.json`) from a known repo path (e.g.
     `app/mixin/bigface/`). Keep them STORED (uncompressed) or default; assets need no resources.arsc.
   - The original `app/mixin/source.apk` stays untouched on disk; injection happens on the build
     output copy (consistent with how ZipUtil already replaces the dex).

3. **Runtime: inject config + unzip on first launch.**
   - In `EmojiConfigPatch.parseConfigData`, ALSO append the animated entries (from
     `bigface_index.json`, read from assets) into the `sysface` array: each entry
     `{QSid, AQLid (=QSid), AniStickerType:1, AniStickerPackId:"1", AniStickerId:"<id>", QDes, EMCode}`.
     (QDes/EMCode can be synthesized; only QSid/AQLid/AniStickerType/PackId/Id matter for rendering.)
   - First-launch unzip: in an early hook (e.g. application/init or lazily the first time
     parseConfigData runs), if a version marker file is absent, read `assets/bigface.zip` via
     `application.assets.open("bigface.zip")` and extract every entry into
     `application.filesDir/qq_emoticon_res/<entry>` (create dirs; the app owns this dir so no root).
     Write a version marker (e.g. `qq_emoticon_res/.qqpro_bigface_v1`) to skip on subsequent launches.
     Re-extract when the bundled version changes (so it survives app updates/reinstalls).
   - Per `qqpro-mixin-anon-class`: any anonymous-class/listener logic must live in a non-inline
     `lib/` helper, NOT inside a @Mixin method body.

4. **No final-class hooks needed.** Once `qlottie/1/<id>/<id>.json` exists, `WatchAniStickerItemCell`
   → `AniStickerHelper` finds it and animates. Verified: pushing the phone Lottie to
   `qlottie/1/<id>/<id>.json` + config injection (packId=1, aniStickerId=<id>) made 424/468/469
   animate in chat.

---

## Testing
- Build debug (`./gradlew MixinApk-debug`, faster than release — see `build-use-debug`), install.
- `adb root` on watch; verify files land in `…/qq_emoticon_res/qlottie/1/<id>/<id>.json` after first
  launch (the unzip), with app uid/SELinux context (the app writing its own files gets these right).
- Open a chat containing 大表情 424/468/469 → should animate. Classic faces (/足球 etc.) → render.
- Logs via `Utils.log` → `/sdcard/Android/data/com.tencent.qqlite/cache/qqpro_debug.log` (see
  `qqpro-logging-qlog`); `adb pull` to read. Keep all Log statements (logging rule).

## Cleanup before shipping
- Remove the on-device manually root-pushed test files (they'll be replaced by the unzip):
  `…/qq_emoticon_res/qlottie/1/{424,468,469}`, `sysface_res/apng/{s424,s468,s469}.png`,
  `sysface_res/static/{s424,s468,s469}.png`.
</content>
