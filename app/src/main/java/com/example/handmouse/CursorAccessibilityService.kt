package com.example.handmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class CursorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CursorAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursorView: CursorView? = null

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cursorView = CursorView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm?.addView(cursorView, params)
    }

    fun update(x: Float, y: Float, pinch: Boolean) {
        handler.post { cursorView?.set(x, y, pinch, true) }
    }

    fun hide() {
        handler.post { cursorView?.set(0f, 0f, false, false) }
    }

    fun tap(x: Float, y: Float) {
        handler.post {
            val dm = Resources.getSystem().displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            when {
                y > h * 0.94f -> when {
                    x < w / 3f -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    x < w * 2f / 3f -> performGlobalAction(GLOBAL_ACTION_HOME)
                    else -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
                y < h * 0.03f -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                else -> {
                    val path = Path().apply { moveTo(x, y) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                        .build()
                    dispatchGesture(gesture, null, null)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        cursorView?.let { wm?.removeView(it) }
        cursorView = null
        super.onDestroy()
    }
}

class CursorView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var pinch = false
    private var show = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private fun dirty() {
        val r = 60f
        invalidate((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
    }

    fun set(x: Float, y: Float, p: Boolean, s: Boolean) {
        dirty()
        cx = x; cy = y; pinch = p; show = s
        dirty()
    }

    override fun onDraw(canvas: Canvas) {
        if (!show) return
        val r = if (pinch) 20f else 32f
        fill.color = if (pinch) Color.argb(130, 244, 67, 54) else Color.argb(90, 33, 150, 243)
        ring.color = if (pinch) Color.rgb(244, 67, 54) else Color.rgb(33, 150, 243)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
    }
}
