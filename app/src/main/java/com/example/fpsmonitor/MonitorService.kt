package com.example.fpsmonitor

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.*
import java.util.Locale

class MonitorService : Service() {
    companion object { const val START = "monitor.start"; const val STOP = "monitor.stop"; private const val CHANNEL = "monitor_status" }
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: MonitorView? = null
    private var params: WindowManager.LayoutParams? = null
    private val update = object : Runnable {
        override fun run() { overlay?.refresh(); if (overlay != null) handler.postDelayed(this, 1000) }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "FPS Monitor", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 0, Intent(this, MonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("FPS Monitor активен").setContentText("Мониторинг поверх экрана • без root").setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
        startForeground(10, notification)
        if (overlay == null && Settings.canDrawOverlays(this)) showOverlay()
        return START_STICKY
    }
    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        overlay = MonitorView(this)
        params = WindowManager.LayoutParams((320*density).toInt(), (190*density).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 10; y = (36*density).toInt() }
        wm?.addView(overlay, params)
        handler.post(update)
    }
    override fun onDestroy() { handler.removeCallbacks(update); overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }; overlay = null; super.onDestroy() }
    inner class MonitorView(context: Context): View(context) {
        private val paint = Paint(3)
        private val mem = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        private var compact = false
        private var ramPercent = 0f
        private var ramText = "—"
        private var batteryLevel = "—"
        private var batteryTemp = "—"
        private val history = ArrayDeque<Float>()
        private var downX = 0f; private var downY = 0f; private var initialX = 0; private var initialY = 0; private var moved = false
        fun refresh() {
            val info = ActivityManager.MemoryInfo(); mem.getMemoryInfo(info)
            val used = info.totalMem - info.availMem
            ramPercent = (used.toDouble()/info.totalMem).toFloat().coerceIn(0f, 1f)
            ramText = String.format(Locale.US, "%.1f / %.1f GB", used/1e9, info.totalMem/1e9)
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            batteryLevel = if (level >= 0 && scale > 0) "${level*100/scale}%" else "—"
            val t = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            batteryTemp = if (t >= 0) String.format(Locale.US, "%.1f°C", t/10f) else "—"
            history.addLast(ramPercent)
            if (history.size > 55) history.removeFirst()
            invalidate()
        }
        private fun label(text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            paint.color = color; paint.textSize = size; paint.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            canvasRef?.drawText(text, x, y, paint)
        }
        private var canvasRef: Canvas? = null
        override fun onDraw(canvas: Canvas) {
            canvasRef = canvas
            val d = resources.displayMetrics.density
            canvas.save(); canvas.scale(d,d)
            paint.color = Color.argb(218, 9, 14, 22); canvas.drawRoundRect(0f,0f,320f,if(compact) 75f else 190f,12f,12f,paint)
            val green = Color.rgb(96, 235, 111); val blue = Color.rgb(85, 200, 255); val yellow = Color.rgb(255, 197, 65)
            label("FPS", 12f, 23f, 15f, green, true); label("N/A", 56f, 23f, 15f, Color.WHITE, true)
            label("RAM", 140f, 23f, 14f, blue, true); label(ramText, 183f, 23f, 11f, Color.WHITE)
            label("BAT", 12f, 50f, 14f, yellow, true); label(batteryLevel, 55f, 50f, 14f, Color.WHITE)
            label("TEMP", 140f, 50f, 14f, yellow, true); label(batteryTemp, 190f, 50f, 14f, Color.WHITE)
            if (!compact) {
                label("CPU  N/A     GPU  N/A", 12f, 78f, 13f, blue)
                label("RAM HISTORY", 12f, 104f, 12f, green, true)
                paint.color = Color.DKGRAY; paint.strokeWidth = 1f
                canvas.drawLine(12f, 156f, 305f, 156f, paint)
                val values = history.toList()
                if (values.size > 1) {
                    val path = Path()
                    values.forEachIndexed { i, v ->
                        val x = 12f + i*293f/54f; val y = 153f - v*42f
                        if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                    }
                    paint.color = green; paint.strokeWidth = 1.8f; paint.style = Paint.Style.STROKE; canvas.drawPath(path,paint); paint.style = Paint.Style.FILL
                }
                label("FPS другого приложения недоступен", 12f, 177f, 10f, Color.LTGRAY)
            }
            canvas.restore(); canvasRef = null
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val p = params ?: return false
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = p.x; initialY = p.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(event.rawX-downX)+kotlin.math.abs(event.rawY-downY)>12) moved=true
                    if(moved) { p.x = initialX+(event.rawX-downX).toInt(); p.y = initialY+(event.rawY-downY).toInt(); wm?.updateViewLayout(this,p) }; return true }
                MotionEvent.ACTION_UP -> { if(!moved) { compact = !compact; p.height = ((if(compact) 75 else 190)*resources.displayMetrics.density).toInt(); wm?.updateViewLayout(this,p); invalidate() }; return true }
            }
            return true
        }
    }
}