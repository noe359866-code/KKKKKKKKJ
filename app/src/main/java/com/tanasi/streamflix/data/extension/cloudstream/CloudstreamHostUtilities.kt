package com.lagradost.cloudstream3

import android.app.Activity
import android.widget.Toast

/** Host replacement for small app-only UI helpers used by Cloudstream extensions. */
object CommonActivity {
    @JvmStatic var activity: Activity? = null
    @JvmStatic fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val current = activity ?: return
        current.runOnUiThread { Toast.makeText(current, message, duration).show() }
    }
}
