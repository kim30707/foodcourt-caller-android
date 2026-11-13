package kr.sehotech.foodcourtcaller.terminal

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 화면 위쪽 상태 배너. 접근성 오버레이(TYPE_ACCESSIBILITY_OVERLAY)라 따로 권한이 필요 없다.
 * 터치와 포커스를 받지 않으므로 호출 앱 사용을 방해하지 않는다.
 */
class OverlayBanner(private val service: AccessibilityService) {
    companion object {
        val COLOR_INFO = Color.parseColor("#E61F4E79")
        val COLOR_OK = Color.parseColor("#E62E7D32")
        val COLOR_ERROR = Color.parseColor("#F0C62828")
        val COLOR_MUTED = Color.parseColor("#E6555555")
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics).toInt()

    /** durationMs가 null이면 다음 표시 전까지 유지 */
    fun show(text: String, color: Int, durationMs: Long?) {
        handler.removeCallbacks(hideRunnable)
        try {
            val v = view ?: createView().also { view = it }
            v.text = text
            (v.background as GradientDrawable).setColor(color)
        } catch (e: Exception) {
            Log.w("OverlayBanner", "배너 표시 실패", e)
            view = null
            return
        }
        if (durationMs != null) handler.postDelayed(hideRunnable, durationMs)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    private fun createView(): TextView {
        val tv = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24f)
        }
        wm.addView(tv, lp)
        return tv
    }
}
