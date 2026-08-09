package momoi.mod.qqpro.hook.call

import android.os.Bundle
import com.tencent.activitys.BeInvitedActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * 全新通话界面 — Material 3 skin for the incoming-call (answer/reject) screen. Applied after super so the
 * native `activity_be_invited` layout is inflated; [MaterialCallUi.applyIncoming] restyles the surface,
 * avatar, name and tips. The answer/reject buttons are restyled later in [CallIncomingConnHook] (QQ wires
 * them in the service-connection callback). Gated on materializeCall (default off).
 */
@Mixin
class CallIncomingHook : BeInvitedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.materializeCall.value) MaterialCallUi.prepIncoming(this)
    }
}
