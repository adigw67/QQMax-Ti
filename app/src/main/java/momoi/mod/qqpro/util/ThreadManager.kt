package momoi.mod.qqpro.util

import android.os.Handler
import android.os.Looper

object ThreadManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun runOnUiThread(
        runnable: Runnable,
        delayMillis: Long = 0L
    ) {
        if (delayMillis > 0) {
            mainHandler.postDelayed(runnable, delayMillis)
        } else {
            mainHandler.post(runnable)
        }
    }

}

fun runOnUi(task: () -> Unit) = ThreadManager.runOnUiThread(task)