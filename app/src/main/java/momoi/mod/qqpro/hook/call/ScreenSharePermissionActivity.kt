package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import momoi.mod.qqpro.util.Utils

/**
 * Thin, transient activity that shows the system MediaProjection consent dialog and hands the result
 * to [ScreenShare]. Kept as minimal chrome (transparent window, no animation) so the consent dialog
 * appears to pop directly over the call. Registered (not exported) via the merged manifest.
 */
class ScreenSharePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        runCatching {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }.onFailure {
            Utils.log("ScreenSharePermissionActivity: createScreenCaptureIntent failed: $it")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ScreenShare.onConsent(applicationContext, resultCode, data)
        } else {
            Utils.toast(this, "屏幕共享已取消")
        }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ = 0x5C3E
    }
}
