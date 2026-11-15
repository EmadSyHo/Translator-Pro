package com.translatorpro.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var iconView: TextView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var boxView: FrameLayout? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private var boxLocked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        createIcon()
    }

    private fun startAsForeground() {
        val channelId = "translator_pro"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "Translator Pro", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("Translator Pro")
            .setContentText("الأيقونة العائمة تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        startForeground(1, n)
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility")
    private fun createIcon() {
        val size = 140
        val icon = TextView(this).apply {
            text = "ترجم"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1565C0"))
            }
        }
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 300
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        var lastTap = 0L
        var singleTapRunnable: Runnable? = null

        icon.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 400) {
                            singleTapRunnable?.let { v.removeCallbacks(it) }
                            lastTap = 0L
                            onDoubleTap()
                        } else {
                            lastTap = now
                            val r = Runnable { onSingleTap() }
                            singleTapRunnable = r
                            v.postDelayed(r, 400)
                        }
                    }
                }
            }
            true
        }

        iconView = icon
        iconParams = p
        wm.addView(icon, p)
    }

    private fun onSingleTap() {
        if (boxView == null) {
            createBox()
        } else {
            removeBox()
        }
    }

    private fun onDoubleTap() {
        if (boxView == null) {
            Toast.makeText(this, "اضغط مرة واحدة لإظهار المربع أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        boxLocked = !boxLocked
        updateBoxStyle()
        Toast.makeText(
            this,
            if (boxLocked) "تم تثبيت المنطقة ✅" else "تم فك التثبيت",
            Toast.LENGTH_SHORT
        ).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBox() {
        val box = FrameLayout(this)
        val p = WindowManager.LayoutParams(
            600, 220, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200; y = 500
        }

        val handle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#FFFFEB3B"))
            }
        }
        val hp = FrameLayout.LayoutParams(70, 70, Gravity.BOTTOM or Gravity.END)
        box.addView(handle, hp)

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        box.setOnTouchListener { v, e ->
            if (boxLocked) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt()
                    p.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(v, p)
                }
            }
            true
        }

        var rw = 0; var rh = 0; var rtx = 0f; var rty = 0f
        handle.setOnTouchListener { _, e ->
            if (boxLocked) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    rw = p.width; rh = p.height; rtx = e.rawX; rty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.width = (rw + (e.rawX - rtx).toInt()).coerceAtLeast(150)
                    p.height = (rh + (e.rawY - rty).toInt()).coerceAtLeast(80)
                    wm.updateViewLayout(box, p)
                }
            }
            true
        }

        boxView = box
        boxParams = p
        boxLocked = false
        updateBoxStyle()
        wm.addView(box, p)
    }

    private fun updateBoxStyle() {
        val box = boxView ?: return
        val p = boxParams ?: return
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(6, if (boxLocked) Color.parseColor("#FF4CAF50") else Color.parseColor("#FFFFEB3B"))
            setColor(Color.parseColor("#22FFFFFF"))
        }
        (box.getChildAt(0))?.visibility = if (boxLocked) View.GONE else View.VISIBLE

        if (boxLocked) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (box.isAttachedToWindow) wm.updateViewLayout(box, p)
    }

    private fun removeBox() {
        boxView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        boxView = null
        boxParams = null
        boxLocked = false
    }

    override fun onDestroy() {
        removeBox()
        iconView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        iconView = null
        super.onDestroy()
    }
}
