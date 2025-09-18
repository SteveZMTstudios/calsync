package top.stevezmt.calsync

import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Apply top padding equal to the status bar / display cutout inset so content is below
 * the status bar / notch. This uses WindowInsetsCompat and is safe on older devices.
 */
fun View.applyStatusBarPadding() {
    // Ensure we listen to insets and apply top padding
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val sysBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
        // Keep existing paddings for left/right/bottom
        v.updatePadding(top = sysBar.top)
        // Return the insets as consumed for status bars to avoid double application downstream
        insets
    }

    // Request insets when view is attached
    if (this.isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        this.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}
