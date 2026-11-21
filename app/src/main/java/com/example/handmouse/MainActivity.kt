package com.example.handmouse

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 32) }
        root.addView(status)

        fun addButton(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick(); refresh() }
            })
        }

        addButton("1. Cấp quyền camera") { askPermissions() }
        addButton("2. Bật Trợ năng (HandMouse)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("3. Bắt đầu theo dõi tay") {
            startForegroundService(Intent(this, HandTrackingService::class.java))
        }
        addButton("Dừng") { stopService(Intent(this, HandTrackingService::class.java)) }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        refresh()
    }

    private fun refresh() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val acc = CursorAccessibilityService.instance != null
        status.text = "Camera: ${if (cam) "đã cấp ✓" else "chưa cấp"}\n" +
            "Trợ năng: ${if (acc) "đã bật ✓" else "chưa bật"}\n\n" +
            "Làm lần lượt 1 → 2 → 3. Giữ app này mở lúc bấm bước 3."
    }
}
