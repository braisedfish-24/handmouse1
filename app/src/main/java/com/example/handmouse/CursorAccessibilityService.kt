package com.example.handmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
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
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
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

    fun set(x: Float, y: Float, p: Boolean, s: Boolean) {
        cx = x; cy = y; pinch = p; show = s
        invalidate()
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
EOFcat > app/src/main/java/com/example/handmouse/HandTrackingService.kt <<'EOF'
package com.example.handmouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class HandTrackingService : LifecycleService() {

    private val margin = 0.15f
    private val minAlpha = 0.08f
    private val maxAlpha = 0.6f
    private val pinchOn = 0.22f
    private val pinchOff = 0.32f
    private val cooldownMs = 300L

    private var landmarker: HandLandmarker? = null
    private lateinit var executor: ExecutorService

    private val screenW = Resources.getSystem().displayMetrics.widthPixels.toFloat()
    private val screenH = Resources.getSystem().displayMetrics.heightPixels.toFloat()
    private var px = screenW / 2
    private var py = screenH / 2
    private var pinching = false
    private var lastClick = 0L

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        executor = Executors.newSingleThreadExecutor()
        setupLandmarker()
        startCamera()
    }

    private fun startAsForeground() {
        val channelId = "handmouse"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "HandMouse", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("HandMouse đang theo dõi tay")
            .setContentText("Chụm ngón cái + trỏ để click")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { it.printStackTrace() }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(image.planes[0].buffer)
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            landmarker?.detectAsync(BitmapImageBuilder(rotated).build(), SystemClock.uptimeMillis())
        } finally {
            image.close()
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)
    private fun mapAxis(v: Float) = min(1f, max(0f, (v - margin) / (1f - 2 * margin)))

    private fun onResult(result: HandLandmarkerResult) {
        val service = CursorAccessibilityService.instance ?: return
        if (result.landmarks().isEmpty()) {
            service.hide()
            pinching = false
            return
        }
        val lm = result.landmarks()[0]
        val wrist = lm[0]; val thumb = lm[4]; val index = lm[8]; val mid = lm[9]

        val size = dist(wrist.x(), wrist.y(), mid.x(), mid.y())
        val ratio = if (size > 0) dist(thumb.x(), thumb.y(), index.x(), index.y()) / size else 1f
        val isPinch = if (pinching) ratio < pinchOff else ratio < pinchOn

        if (!isPinch) {
            val tx = mapAxis(index.x()) * screenW
            val ty = mapAxis(index.y()) * screenH
            val d = hypot(tx - px, ty - py)
            val a = min(maxAlpha, minAlpha + d / 400f)
            px += (tx - px) * a
            py += (ty - py) * a
        }

        service.update(px, py, isPinch)

        val now = SystemClock.uptimeMillis()
        if (isPinch && !pinching && now - lastClick > cooldownMs) {
            lastClick = now
            service.tap(px, py)
        }
        pinching = isPinch
    }

    override fun onDestroy() {
        landmarker?.close()
        executor.shutdown()
        CursorAccessibilityService.instance?.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): android.os.IBinder? = super.onBind(intent)
}
