package momoi.mod.qqpro.hook.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import momoi.mod.qqpro.util.Utils

/**
 * 通话音频输出 — in-call audio output control, rebuilt from scratch and verified two-way on the W527
 * (Android 11): Bluetooth SCO carries both the headset speaker AND the headset mic.
 *
 * Background: stock QQ on this watch routes call audio to the SPEAKER only (`C2COperatorImpl.c()` toggles
 * just speaker/earpiece; QQ's real BT engine is never started here). QQ's BT action is just OS-level audio
 * routing, which we drive directly. The mechanism is API-aware:
 * - **API 31+ (phone)**: `AudioManager.setCommunicationDevice(...)` — the authoritative modern API; it
 *   starts/stops SCO itself. The legacy `startBluetoothSco()` is deprecated / a no-op here, which is why
 *   it failed to engage the mic on the phone.
 * - **API ≤30 (the watch)**: legacy `setBluetoothScoOn(true)` + `startBluetoothSco()`, plus a live
 *   SCO-state receiver that re-asserts SCO on this ROM's frequent spontaneous drops.
 *
 * This object is the SINGLE SOURCE OF TRUTH for the active route: [currentRoute] is always the route we
 * actually applied, so the UI icon can never diverge from reality. [begin] applies the default route
 * (Bluetooth when a headset is connected) and registers an [AudioDeviceCallback] for mid-call headset
 * unplug/replug. UIs call [cycle]/[setRoute] and mirror [currentRoute] via [setRouteListener].
 */
object CallAudio {

    enum class Route { SPEAKER, EARPIECE, BLUETOOTH }

    @Volatile var currentRoute: Route = Route.SPEAKER
        private set

    private val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val handler = Handler(Looper.getMainLooper())
    private var routeListener: ((Route) -> Unit)? = null

    @Volatile private var preferBluetooth = true
    @Volatile private var active = false
    private var scoReceiver: BroadcastReceiver? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var commListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoRetries = 0

    private fun am(ctx: Context) = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---- device probing --------------------------------------------------------------------------

    private fun commDeviceTypes(ctx: Context): List<Int> = runCatching {
        am(ctx).availableCommunicationDevices.map { it.type }
    }.getOrDefault(emptyList())

    private fun outputTypes(ctx: Context): List<Int> = runCatching {
        // AudioManager.getDevices / AudioDeviceInfo are API 23+ — absent on this API 19 watch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching emptyList<Int>()
        am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }.getOrDefault(emptyList())

    fun bluetoothConnected(ctx: Context): Boolean {
        val types = if (modern) commDeviceTypes(ctx) else outputTypes(ctx)
        return types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    fun hasEarpiece(ctx: Context): Boolean {
        val types = if (modern) commDeviceTypes(ctx) else outputTypes(ctx)
        return types.any { it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }

    /** Routes worth offering, given present hardware. Speaker always; BT only when a headset is present. */
    fun availableRoutes(ctx: Context): List<Route> = buildList {
        if (bluetoothConnected(ctx)) add(Route.BLUETOOTH)
        add(Route.SPEAKER)
        if (hasEarpiece(ctx)) add(Route.EARPIECE)
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Called when the active-call screen comes to the foreground. Idempotent; applies the default route. */
    fun begin(ctx: Context, autoBluetooth: Boolean) {
        preferBluetooth = autoBluetooth
        diagnose(ctx)
        if (!active) {
            active = true
            scoRetries = 0
            // SCO retry receiver is LEGACY-ONLY. On API 31+ (normal phones) setCommunicationDevice(SCO)
            // establishes + owns SCO by itself (verified reaching CONNECTED on a Samsung A53). The watch
            // (API 30) needs the legacy startBluetoothSco + retry path.
            if (!modern) ensureScoReceiver(ctx)
            ensureDeviceCallback(ctx)
            if (modern) ensureCommListener(ctx)
        }
        // Apply the default output NOW (don't just paint an icon) so the applied route and the mic match
        // the display from the first frame.
        val default = if (autoBluetooth && bluetoothConnected(ctx)) Route.BLUETOOTH else Route.SPEAKER
        setRoute(ctx, default)
        // QQ's QavBussinessCtrl calls setSpeakerphoneOn(true) at bring-up shortly after we show. Re-assert
        // the default once it has settled — but only for non-BT routes (BT/SCO robustness is owned by the
        // SCO receiver; re-calling startBluetoothSco here would disturb an in-progress connect).
        handler.postDelayed({ if (active && currentRoute != Route.BLUETOOTH) runCatching { setRoute(ctx, currentRoute) } }, 1400)
    }

    /** Restore normal audio at call end. */
    @Suppress("DEPRECATION")
    fun release(ctx: Context) {
        active = false
        handler.removeCallbacksAndMessages(null)
        val audio = am(ctx)
        runCatching {
            stopSco(audio) // we now start SCO on both paths
            if (modern) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }.onFailure { Utils.log("CallAudio: release failed: $it") }
        unregisterScoReceiver(ctx)
        unregisterDeviceCallback(ctx)
        unregisterCommListener(ctx)
        currentRoute = Route.SPEAKER
        Utils.log("CallAudio: released")
    }

    // ---- routing ---------------------------------------------------------------------------------

    /** Cycle to the next available route and apply it. Returns the new route. */
    fun cycle(ctx: Context): Route {
        val routes = availableRoutes(ctx)
        if (routes.size < 2) { Utils.toast(ctx, "无其它音频输出"); return currentRoute }
        val idx = (routes.indexOf(currentRoute) + 1).let { if (it < 0 || it >= routes.size) 0 else it }
        val chosen = routes[idx]
        // A manual choice sets intent: stop auto-forcing BT if the user picked speaker/earpiece (and resume
        // preferring BT if they picked it), so a transient SCO re-add doesn't yank them back.
        preferBluetooth = chosen == Route.BLUETOOTH
        setRoute(ctx, chosen)
        Utils.toast(ctx, label(currentRoute))
        return currentRoute
    }

    /** Apply [target] as the active call-audio output and become the source of truth for the UI. */
    @Suppress("DEPRECATION")
    fun setRoute(ctx: Context, target: Route): Boolean {
        val audio = am(ctx)
        return runCatching {
            if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            // Two fully separate mechanisms — NEVER mixed (mixing setCommunicationDevice + startBluetoothSco
            // caused an SCO connect/disconnect storm on API 31+):
            //  • modern (API 31+): setCommunicationDevice is authoritative and manages SCO itself.
            //  • legacy (API ≤30, the watch): setBluetoothScoOn + startBluetoothSco.
            if (modern) routeModern(ctx, target) else routeLegacy(ctx, audio, target)
            currentRoute = target
            notifyRoute(target)
            true
        }.onFailure { Utils.log("CallAudio: route $target failed: $it") }.getOrDefault(false)
    }

    private fun routeModern(ctx: Context, target: Route) {
        val audio = am(ctx)
        // setCommunicationDevice is authoritative on API 31+: it establishes SCO for BT and switches the
        // whole comm route (playback + mic). No startBluetoothSco (mixing them caused connect storms).
        val wantType = when (target) {
            Route.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            Route.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            Route.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        setCommDevice(audio, wantType)
        Utils.log("CallAudio: route(modern) -> $target (mode=${audio.mode})")
    }

    @Suppress("DEPRECATION")
    private fun routeLegacy(ctx: Context, audio: AudioManager, target: Route) {
        when (target) {
            Route.BLUETOOTH -> {
                scoRetries = 0
                audio.isSpeakerphoneOn = false
                audio.isBluetoothScoOn = true
                audio.startBluetoothSco()
            }
            Route.SPEAKER -> {
                stopSco(audio)
                audio.isSpeakerphoneOn = true
                // Switching off SCO is async; the first speaker flip can lose the race, re-assert once.
                handler.postDelayed({ if (active && currentRoute == Route.SPEAKER) runCatching { am(ctx).isSpeakerphoneOn = true } }, 350)
            }
            Route.EARPIECE -> {
                stopSco(audio)
                audio.isSpeakerphoneOn = false
                handler.postDelayed({ if (active && currentRoute == Route.EARPIECE) runCatching { am(ctx).isSpeakerphoneOn = false } }, 350)
            }
        }
        Utils.log("CallAudio: route(legacy) -> $target (mode=${audio.mode}, scoOn=${audio.isBluetoothScoOn}, spkOn=${audio.isSpeakerphoneOn})")
    }

    /** Point the modern communication route at [wantType] if such a device exists. */
    private fun setCommDevice(audio: AudioManager, wantType: Int) {
        val device = audio.availableCommunicationDevices.firstOrNull { it.type == wantType }
        if (device != null) {
            val ok = audio.setCommunicationDevice(device)
            Utils.log("CallAudio: setCommunicationDevice(type=$wantType) = $ok")
        } else {
            Utils.log("CallAudio: no comm device type=$wantType")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco(audio: AudioManager) {
        if (audio.isBluetoothScoOn) audio.isBluetoothScoOn = false
        runCatching { audio.stopBluetoothSco() }
    }

    // ---- UI mirror -------------------------------------------------------------------------------

    /** UIs register here to keep their icon in sync; the current route is pushed immediately. */
    fun setRouteListener(l: ((Route) -> Unit)?) {
        routeListener = l
        l?.let { cb -> handler.post { runCatching { cb(currentRoute) } } }
    }

    private fun notifyRoute(route: Route) {
        val cb = routeListener ?: return
        handler.post { runCatching { cb(route) } }
    }

    fun label(route: Route) = when (route) {
        Route.BLUETOOTH -> "蓝牙耳机"
        Route.SPEAKER -> "扬声器"
        Route.EARPIECE -> "听筒"
    }

    // ---- diagnostics -----------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun diagnose(ctx: Context) = runCatching {
        val audio = am(ctx)
        // getDevices is API 23+ — absent on the API 19 watch; skip it there.
        val outs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        } else emptyList()
        val comm = if (modern) audio.availableCommunicationDevices.map { it.type } else emptyList()
        Utils.log(
            "CallAudio.diagnose: api=${Build.VERSION.SDK_INT} modern=$modern mode=${audio.mode} " +
                "scoOffCall=${audio.isBluetoothScoAvailableOffCall} scoOn=${audio.isBluetoothScoOn} " +
                "a2dpOn=${audio.isBluetoothA2dpOn} spkOn=${audio.isSpeakerphoneOn} outputs=$outs commDevices=$comm",
        )
    }.onFailure { Utils.log("CallAudio.diagnose failed: $it") }

    // ---- SCO link tracking / re-assertion (legacy / API ≤30 only) --------------------------------

    @Suppress("DEPRECATION")
    private fun ensureScoReceiver(ctx: Context) {
        if (scoReceiver != null) return
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                val name = when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
                    AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
                    else -> "state=$state"
                }
                Utils.log("CallAudio: SCO_AUDIO_STATE -> $name")
                if (!active || currentRoute != Route.BLUETOOTH) return
                val audio = am(app)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        if (!audio.isBluetoothScoOn) audio.isBluetoothScoOn = true
                        // Now that the SCO link is actually up, lock the modern comm route onto it (this is
                        // when the crDroid SCO device finally resolves to non-null).
                        if (modern) setCommDevice(audio, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                        scoRetries = 0
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED, AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        // DELAYED retry (1s) — retrying immediately caused a connect/disconnect storm.
                        if (scoRetries < 3) {
                            scoRetries++
                            Utils.log("CallAudio: SCO dropped while BT desired; retry $scoRetries in 1s")
                            handler.postDelayed({
                                if (active && currentRoute == Route.BLUETOOTH) runCatching {
                                    am(app).isBluetoothScoOn = true
                                    am(app).startBluetoothSco()
                                }
                            }, 1000)
                        }
                    }
                }
            }
        }
        runCatching {
            app.registerReceiver(rx, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiver = rx
        }.onFailure { Utils.log("CallAudio: register SCO receiver failed: $it") }
    }

    private fun unregisterScoReceiver(ctx: Context) {
        val rx = scoReceiver ?: return
        runCatching { ctx.applicationContext.unregisterReceiver(rx) }
        scoReceiver = null
    }

    // ---- headset hotplug during a call -----------------------------------------------------------

    private fun ensureDeviceCallback(ctx: Context) {
        // AudioDeviceCallback is API 23+ — absent on the API 19 watch. The legacy SCO receiver
        // (ensureScoReceiver) already covers headset connect/disconnect there; skip the hotplug
        // callback entirely instead of crashing the call screen with a NoClassDefFoundError.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (deviceCallback != null) return
        val app = ctx.applicationContext
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (!active) return
                if (added.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) {
                    // Guard against self-churn: STARTING SCO adds the bt_sco device, which fires this
                    // callback. Only auto-switch when we're NOT already on BT (a genuinely new headset),
                    // otherwise we'd re-run startBluetoothSco mid-connect and thrash the link.
                    if (preferBluetooth && currentRoute != Route.BLUETOOTH) {
                        Utils.log("CallAudio: BT device added mid-call -> switching to BT")
                        setRoute(app, Route.BLUETOOTH)
                    }
                }
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (!active) return
                val btRemoved = removed.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                // The TYPE_BLUETOOTH_SCO device disappears every time the SCO link cycles (normal), which is
                // NOT an unplug — only treat it as gone if NO Bluetooth device remains at all. Falling back on
                // an SCO cycle both caused the "drops to speaker" bug and blocked SCO auto-recovery.
                if (btRemoved && currentRoute == Route.BLUETOOTH && !bluetoothConnected(app)) {
                    Utils.log("CallAudio: headset truly disconnected mid-call; falling back to speaker")
                    setRoute(app, Route.SPEAKER)
                }
            }
        }
        runCatching {
            am(app).registerAudioDeviceCallback(cb, handler)
            deviceCallback = cb
        }.onFailure { Utils.log("CallAudio: register device callback failed: $it") }
    }

    private fun unregisterDeviceCallback(ctx: Context) {
        val cb = deviceCallback ?: return
        runCatching { am(ctx).unregisterAudioDeviceCallback(cb) }
        deviceCallback = null
    }

    // Keep the UI icon in sync with the ACTUAL communication device (API 31+). When SCO cycles or QQ's
    // native code nudges the route, this makes the icon reflect reality instead of going stale — and
    // re-asserts Bluetooth if we got knocked off it while BT is the desired route.
    private fun ensureCommListener(ctx: Context) {
        if (commListener != null) return
        val app = ctx.applicationContext
        val exec = Executor { handler.post(it) }
        val l = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (!active) return@OnCommunicationDeviceChangedListener
            val r = when (device?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Route.BLUETOOTH
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Route.SPEAKER
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Route.EARPIECE
                else -> null
            }
            if (r != null && r != currentRoute) {
                if (preferBluetooth && currentRoute == Route.BLUETOOTH && r != Route.BLUETOOTH && bluetoothConnected(app)) {
                    // Knocked off BT (e.g. QQ native setSpeakerphoneOn) while headset is still connected — re-assert.
                    Utils.log("CallAudio: comm device -> $r while BT desired; re-asserting BT")
                    setCommDevice(am(app), AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                } else {
                    Utils.log("CallAudio: comm device changed -> $r (icon synced)")
                    currentRoute = r
                    notifyRoute(r)
                }
            }
        }
        runCatching {
            am(app).addOnCommunicationDeviceChangedListener(exec, l)
            commListener = l
        }.onFailure { Utils.log("CallAudio: register comm listener failed: $it") }
    }

    private fun unregisterCommListener(ctx: Context) {
        val l = commListener ?: return
        runCatching { am(ctx).removeOnCommunicationDeviceChangedListener(l) }
        commListener = null
    }
}
