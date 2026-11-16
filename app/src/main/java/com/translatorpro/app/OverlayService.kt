package com.translatorpro.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private var modelReady = false

    private var iconView: TextView? = null
    private var boxView: FrameLayout? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private var boxLocked = false

    private var transView: TextView? = null
    private var transParams: WindowManager.LayoutParams? = null

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("tp-capture").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    private var capturing = false
    private var busy = false
    private var pending = false
    private var candidate: String? = null
    private var lastSource = ""
    private var lastSig: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
        )
        toast("جاري تجهيز نموذج الترجمة (أول مرة فقط)...")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                toast("نموذج الترجمة جاهز ✅")
            }
            .addOnFailureListener {
                toast("فشل تنزيل نموذج الترجمة، تأكد من الإنترنت ثم أعد التشغيل")
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (iconView == null) createIcon()
        if (projection == null && intent != null) {
            val code = intent.getIntExtra("code", 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")
            if (data != null) {
                try {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val proj = mpm.getMediaProjection(code, data)
                    proj.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            mainHandler.post {
                                stopCapture()
                                projection = null
                            }
                        }
                    }, mainHandler)
                    projection = proj
                } catch (e: Exception) {
                    toast("تعذر بدء التقاط الشاشة: ${e.message}")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
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
            .setContentText("الترجمة تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    // ---------------- الأيقونة ----------------

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
        wm.addView(icon, p)
    }

    private fun onSingleTap() {
        if (boxView == null) createBox() else removeBox()
    }

    private fun onDoubleTap() {
        if (boxView == null) {
            toast("اضغط مرة واحدة لإظهار المربع أولاً")
            return
        }
        boxLocked = !boxLocked
        updateBoxStyle()
        if (boxLocked) {
            toast("تم تثبيت المنطقة ✅ الترجمة تعمل")
            startCapture()
        } else {
            toast("تم فك التثبيت")
            stopCapture()
        }
    }

    // ---------------- مربع التحديد ----------------

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
        box.addView(handle, FrameLayout.LayoutParams(70, 70, Gravity.BOTTOM or Gravity.END))

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
            setColor(if (boxLocked) Color.TRANSPARENT else Color.parseColor("#22FFFFFF"))
        }
        box.getChildAt(0)?.visibility = if (boxLocked) View.GONE else View.VISIBLE

        if (boxLocked) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (box.isAttachedToWindow) wm.updateViewLayout(box, p)
    }

    private fun removeBox() {
        stopCapture()
        boxView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        boxView = null
        boxParams = null
        boxLocked = false
    }

    // ---------------- التقاط الشاشة + OCR + الترجمة ----------------

    private fun startCapture() {
        if (projection == null) {
            toast("إذن التقاط الشاشة غير متوفر. أوقف التطبيق وابدأ الترجمة من جديد")
            return
        }
        pending = false
        candidate = null
        lastSource = ""
        lastSig = null
        busy = false
        capturing = true
        mainHandler.post(tick)
    }

    private fun stopCapture() {
        capturing = false
        busy = false
        pending = false
        candidate = null
        lastSource = ""
        lastSig = null
        mainHandler.removeCallbacks(tick)
        hideTranslation()
        try { vDisplay?.release() } catch (_: Exception) {}
        vDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        capW = 0
        capH = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!capturing) return
            if (!busy) {
                busy = true
                doCapture()
            }
            mainHandler.postDelayed(this, 400)
        }
    }

    private fun ensureCapture(): Boolean {
        val proj = projection ?: return false
        val m = realMetrics()
        if (reader == null || m.widthPixels != capW || m.heightPixels != capH) {
            try { reader?.close() } catch (_: Exception) {}
            capW = m.widthPixels
            capH = m.heightPixels
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader = r
            val vd = vDisplay
            if (vd == null) {
                vDisplay = proj.createVirtualDisplay(
                    "TranslatorPro", capW, capH, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, null
                )
            } else {
                vd.resize(capW, capH, m.densityDpi)
                vd.surface = r.surface
            }
        }
        return true
    }

    private fun doCapture() {
        val box = boxView
        val ok = try { ensureCapture() } catch (e: Exception) { false }
        if (box == null || !ok) {
            busy = false
            return
        }
        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val inset = 10
        val rect = Rect(
            loc[0] + inset, loc[1] + inset,
            loc[0] + box.width - inset, loc[1] + box.height - inset
        )
        val r = reader
        bgHandler.post {
            val crop = grabCrop(r, rect)
            mainHandler.post { onCrop(crop) }
        }
    }

    private fun grabCrop(r: ImageReader?, rect: Rect): Bitmap? {
        val img = try { r?.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return null
        try {
            val plane = img.planes[0]
            val fullW = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(fullW, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val l = rect.left.coerceIn(0, img.width - 1)
            val t = rect.top.coerceIn(0, img.height - 1)
            val rr = rect.right.coerceIn(l + 1, img.width)
            val bb = rect.bottom.coerceIn(t + 1, img.height)
            val crop = Bitmap.createBitmap(bmp, l, t, rr - l, bb - t)
            bmp.recycle()
            return crop
        } catch (e: Exception) {
            return null
        } finally {
            img.close()
        }
    }

    private fun signature(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 16, 8, true)
        var h = 1125899906842597L
        for (y in 0 until 8) {
            for (x in 0 until 16) {
                val p = s.getPixel(x, y)
                val g = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                h = 31 * h + (g shr 4)
            }
        }
        if (s !== b) s.recycle()
        return h
    }

    private fun onCrop(crop: Bitmap?) {
        if (!capturing) {
            crop?.recycle()
            busy = false
            return
        }
        if (crop == null) {
            // لا يوجد إطار جديد = الشاشة لم تتغير = النص مستقر
            val c = candidate
            if (pending && c != null) {
                pending = false
                translateText(c)
            } else {
                busy = false
            }
            return
        }
        val sig = signature(crop)
                if (sig == lastSig) {
            crop.recycle()
            val c = candidate
            if (pending && c != null) {
                pending = false
                translateText(c)
            } else {
                busy = false
            }
            return
        }
        lastSig = sig
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { res ->
                crop.recycle()
                val text = res.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                handleText(text)
            }
            .addOnFailureListener {
                crop.recycle()
                busy = false
            }
    }

    private fun handleText(text: String) {
        if (text.length < 2) {
            hideTranslation()
            lastSource = ""
            candidate = null
            pending = false
            busy = false
            return
        }
        if (text == lastSource) {
            pending = false
            busy = false
            return
        }
        if (text == candidate) {
            pending = false
            translateText(text)
        } else {
            candidate = text
            pending = true
            busy = false
        }
    }

    private fun translateText(text: String) {
        if (!modelReady) {
            toast("نموذج الترجمة لم يجهز بعد، انتظر قليلاً")
            busy = false
            return
        }
        translator.translate(text)
            .addOnSuccessListener { t ->
                if (capturing) {
                    lastSource = text
                    showTranslation(t)
                }
                busy = false
            }
            .addOnFailureListener { busy = false }
    }

    // ---------------- عرض الترجمة بجانب المربع ----------------

    private fun showTranslation(text: String) {
        val box = boxView ?: return
        val bp = boxParams ?: return
        if (!boxLocked) return

        val m = realMetrics()
        val w = box.width.coerceAtLeast(600).coerceAtMost(m.widthPixels - 20)

        val tv = transView ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(Color.parseColor("#E6212121"))
            }
        }.also { transView = it }

        tv.text = text
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = tv.measuredHeight

        val loc = IntArray(2)
        box.getLocationOnScreen(loc)
        val offX = loc[0] - bp.x
        val offY = loc[1] - bp.y

        var desiredY = loc[1] - h - 12
        if (desiredY < 0) desiredY = loc[1] + box.height + 12
        if (desiredY + h > m.heightPixels) desiredY = (m.heightPixels - h).coerceAtLeast(0)
        val desiredX = (loc[0] + (box.width - w) / 2).coerceIn(0, (m.widthPixels - w).coerceAtLeast(0))

        val p = transParams ?: WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        p.width = w
        p.x = desiredX - offX
        p.y = desiredY - offY
        transParams = p

        if (tv.isAttachedToWindow) wm.updateViewLayout(tv, p) else wm.addView(tv, p)
    }

    private fun hideTranslation() {
        transView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        transView = null
        transParams = null
    }

    override fun onDestroy() {
        removeBox()
        iconView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        iconView = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        bgThread.quitSafely()
        try { recognizer.close() } catch (_: Exception) {}
        try { translator.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
