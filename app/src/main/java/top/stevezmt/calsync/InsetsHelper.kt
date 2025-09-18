package top.stevezmt.calsync

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Apply top padding to v equal to status bar inset + buffer (8dp), but only once per view.
 * It stores the original top padding in view's tag (id: R.id.original_top_padding) to avoid
 * accumulating padding on repeated calls or configuration changes.
 */
object InsetsHelper {
    private const val EXTRA_BUFFER_DP = 0

    fun applyTopInsetOnce(v: View) {
        // If we've already applied, skip
        val tagKey = R.id.original_top_padding
        if (v.getTag(tagKey) != null) return

        // save original top padding
        v.setTag(tagKey, v.paddingTop)

        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val sysBar = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            val density = view.resources.displayMetrics.density
            val extra = (EXTRA_BUFFER_DP * density).toInt()
            val originalTop = (view.getTag(tagKey) as? Int) ?: view.paddingTop
            // Apply exactly the status bar inset (no doubling) to avoid excessive top gap
            view.updatePadding(top = originalTop + sysBar.top + extra)
            insets
        }

        if (v.isAttachedToWindow) ViewCompat.requestApplyInsets(v)
        else v.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(p0: View) {
                p0.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(p0)
            }
            override fun onViewDetachedFromWindow(p0: View) {}
        })
    }
}
