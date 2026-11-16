package com.translatorpro.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

    private val REQ_CAPTURE = 100
    private lateinit var permBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, bg: String, fg: String = "#FFFFFF", onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor(fg))
            textSize = 16f
            isAllCaps = false
            stateListAnimator = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor(bg))
            }
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "Translator Pro"
            textSize = 28f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = "ترجمة نصوص الشاشة والألعاب"
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        }

        val startBtn = makeButton("تفعيل الترجمة", "#1565C0") { requestCapture() }
        val stopBtn = makeButton("إيقاف الترجمة", "#9E9E9E") {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "تم إيقاف الترجمة", Toast.LENGTH_SHORT).show()
        }
        permBtn = makeButton("فعّل العرض فوق التطبيقات", "#FFFFFF", "#1565C0") { openOverlaySettings() }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#1565C0"))
            }
        }
        val miui = TextView(this).apply {
            text = "إعدادات MIUI (التشغيل التلقائي والبطارية)"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
            setOnClickListener { openMiuiSettings() }
        }

        root.addView(title)
        root.addView(sub)
        root.addView(permBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(miui)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        permBtn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
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
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
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
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }
}
