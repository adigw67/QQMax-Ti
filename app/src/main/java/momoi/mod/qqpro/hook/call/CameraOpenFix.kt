package momoi.mod.qqpro.hook.call

import android.content.Context
import android.hardware.Camera
import com.tencent.av.camera.AndroidCamera
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.util.Utils

/**
 * Fix a black video-call preview on devices that expose more than one front-facing camera.
 *
 * Stock [AndroidCamera.h] (openFrontFacingCamera) loops over every camera id and calls
 * `Camera.open(i)` for EVERY front-facing camera without breaking, keeping only the last:
 *
 * ```
 * for (i in 0 until l) {
 *     getCameraInfo(i, info)
 *     if (info.facing == FRONT) { opened = Camera.open(i); m = i }   // no break
 * }
 * ```
 *
 * On a device with two+ front cameras the first open succeeds, but opening the second is rejected
 * ("existing client(s) with higher priority" — the first is still held). Its catch block sets the
 * handle to null and `m = 0`, discarding the already-open camera, so `h()` returns false and QQ
 * renders black. The watch has a single front camera so it never hits this; multi-front-camera
 * phones do (confirmed via adb: front ids 1/3/4, id 1 opens then id 3 rejected → result[false]).
 *
 * Fix: break on the FIRST successfully-opened front camera and never overwrite a good handle with a
 * later failure. If no front camera opens through the fast path, delegate to the original so any
 * vendor-specific fallback still runs.
 */
@Mixin
class CameraOpenFix(context: Context) : AndroidCamera(context) {
    override fun h(): Boolean {
        m = 0
        if (l == 0) l = c()
        var opened: Camera? = null
        if (l > 0) {
            val info = Camera.CameraInfo()
            for (i in 0 until l) {
                try {
                    Camera.getCameraInfo(i, info)
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        opened = try {
                            Camera.open(i)
                        } catch (e: Exception) {
                            Utils.log("CameraOpenFix: open front id=$i failed: $e")
                            null
                        }
                        if (opened != null) {
                            m = i
                            break
                        }
                    }
                } catch (e: Exception) {
                    Utils.log("CameraOpenFix: getCameraInfo($i) failed: $e")
                }
            }
        }
        if (opened == null) {
            Utils.log("CameraOpenFix: no front camera via fast path (l=$l), delegating to original")
            return super.h()
        }
        g = opened
        if (l == 0) l = 2
        k = 1
        AndroidCamera.a = true
        Utils.log("CameraOpenFix: opened front camera id=$m of $l")
        return true
    }
}
