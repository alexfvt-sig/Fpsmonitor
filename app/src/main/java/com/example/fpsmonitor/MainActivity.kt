package com.example.fpsmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 70, 48, 30); setBackgroundColor(Color.rgb(14, 18, 26)) }
        fun text(s: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(0, 0, 0, 24) }
        fun button(s: String, click: () -> Unit) { root.addView(Button(this).apply { text = s; setOnClickListener { click() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(text("FPS MONITOR", 29f, Color.rgb(90, 230, 130)).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(text("Samsung S22 Ultra • Android 16\nБез root • плавающая панель поверх игр", 16f))
        root.addView(text("Реальные данные: RAM, заряд и температура батареи. FPS других приложений, GPU и температура CPU без специальных привилегий недоступны.", 14f, Color.LTGRAY))
        button("1. Разрешить отображение поверх окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. Запустить монитор") {
            if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@button }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            startForegroundService(Intent(this, MonitorService::class.java).setAction(MonitorService.START))
        }
        button("Остановить монитор") { startService(Intent(this, MonitorService::class.java).setAction(MonitorService.STOP)) }
        root.addView(text("Нажмите на панель для переключения режима. Перетаскивайте её пальцем. Монитор можно остановить здесь или через уведомление.", 13f, Color.GRAY))
        setContentView(root)
    }
}