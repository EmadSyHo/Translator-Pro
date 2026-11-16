package com.translatorpro.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val REQ_CAPTURE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "Translator Pro"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val startBtn = Button(this).apply {
            text = "▶ بدء الترجمة"
            setOnClickListener { requestCapture() }
        }

        val stopBtn = Button(this).apply {
            text = "⏹ إيقاف"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }

        val overlayBtn = Button(this).apply {
            text = "تفعيل العرض فوق التطبيقات"
            setOnClickListener { openOverlaySettings() }
        }

        val miuiBtn = Button(this).apply {
            text = "إعدادات MIUI (التشغيل التلقائي والبطارية)"
            setOnClickListener { openMiuiSettings() }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(overlayBtn)
        root.addView(miuiBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "✅ صلاحية العرض فوق التطبيقات مفعّلة"
        } else {
            "❌ صلاحية العرض فوق التطبيقات غير مفعّلة"
        }
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل صلاحية العرض فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra("code", resultCode)
                    putExtra("data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "ظهرت الأيقونة العائمة، افتح اللعبة", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "لازم توافق على التقاط الشاشة", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openMiuiSettings() {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
