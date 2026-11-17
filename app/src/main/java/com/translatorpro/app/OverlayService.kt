package com.translatorpro.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private class Grab(val bmp: Bitmap, val sig: IntArray, val bg: Int, val fg: Int)

    private lateinit var wm: WindowManager
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private var modelReady = false

    private var iconView: TextView? = null
    private var boxView: FrameLayout? = null
    private var boxParams: WindowManager.LayoutParams? = null
    private var boxLocked = false
    private var watcher: View? = null

    // آخر مكان وحجم للمربع (يُستعاد عند إظهاره من جديد)
    private var savedX = 200
    private var savedY = 500
    private var savedW = 600
    private var savedH = 220

    private var transView: TextView? = null
    private var transParams: WindowManager.LayoutParams? = null
    private var transAdded = false
    private var transShown = false

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
    private var refreshing = false
    private var pending = false
    private var candidate: String? = null
    private var lastSource = ""
    private var lastSig: IntArray? = null
    private var lastBg = Color.parseColor("#FF222222")
    private var lastFg = Color.WHITE
    private var lastCheck = 0L
    private var tapRunnable: Runnable? = null

    private val HIDE_WAIT_MS = 250L
    private val TAP_DELAY_MS = 800L

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
            gravity = Gravity.TOP or Gravity.LEFT
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
        when {
            boxView == null -> createBox()
            boxLocked -> {
                // فك التثبيت فقط: يبقى المربع بمكانه وحجمه ليتحرك أو يتغير
                boxLocked = false
                stopCapture()
                updateBoxStyle()
                toast("تم فك التثبيت، حرّك المربع ثم اضغط مرتين لتثبيته")
            }
            else -> removeBox()
        }
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
            savedW, savedH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = savedX; y = savedY
        }

        val handle = ResizeHandle(this)
        box.addView(handle, FrameLayout.LayoutParams(100, 100, Gravity.BOTTOM or Gravity.RIGHT))

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
        val strokeColor = when {
            boxLocked && lastSource.isNotEmpty() -> Color.TRANSPARENT
            boxLocked -> Color.parseColor("#FF4CAF50")
            else -> Color.parseColor("#FFFFEB3B")
        }
        box.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(6, strokeColor)
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
        boxParams?.let {
            savedX = it.x
            savedY = it.y
            savedW = it.width
            savedH = it.height
        }
        boxView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        boxView = null
        boxParams = null
        boxLocked = false
    }

    // ---------------- مراقبة اللمس ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addWatcher() {
        if (watcher != null) return
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) onUserTouch()
            false
        }
        val p = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0; y = 0
        }
        try {
            wm.addView(v, p)
            watcher = v
        } catch (_: Exception) {}
    }

    private fun removeWatcher() {
        watcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        watcher = null
    }

    private fun onUserTouch() {
        if (!capturing || Prefs.getInt(this, "touch", 1) == 0) return
        tapRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!capturing || !transShown) return
                if (busy) mainHandler.postDelayed(this, 300) else startRefresh()
            }
        }
        tapRunnable = r
        mainHandler.postDelayed(r, TAP_DELAY_MS)
    }

    // ---------------- التقاط الشاشة ----------------

    private fun startCapture() {
        if (projection == null) {
            toast("إذن التقاط الشاشة غير متوفر. أوقف التطبيق وابدأ الترجمة من جديد")
            return
        }
        pending = false
        refreshing = false
        candidate = null
        lastSource = ""
        lastSig = null
        lastCheck = System.currentTimeMillis()
        busy = false
        capturing = true
        addWatcher()
        mainHandler.post(tick)
    }

    private fun stopCapture() {
        capturing = false
        busy = false
        refreshing = false
        pending = false
        candidate = null
        lastSource = ""
        lastSig = null
        mainHandler.removeCallbacks(tick)
        mainHandler.removeCallbacks(refreshCapture)
        tapRunnable?.let { mainHandler.removeCallbacks(it) }
        tapRunnable = null
        removeWatcher()
        hideTranslation()
        try { vDisplay?.release() } catch (_: Exception) {}
        vDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        capW = 0
        capH = 0
    }

    private val refreshCapture = Runnable { doCapture() }

    private val tick = object : Runnable {
        override fun run() {
            if (!capturing) return
            if (!busy) {
                if (refreshing) {
                    busy = true
                    doCapture()
                } else if (transShown) {
                    val iv = Prefs.getInt(this@OverlayService, "interval", 10) * 1000L
                    if (iv > 0 && System.currentTimeMillis() - lastCheck >= iv) startRefresh()
                } else {
                    busy = true
                    doCapture()
                }
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    // إزالة نافذة الترجمة لحظة ثم التقاط المنطقة للتأكد من تغيّر النص
    private fun startRefresh() {
        if (busy || !transShown) return
        busy = true
        lastCheck = System.currentTimeMillis()
        transView?.let { if (transAdded) try { wm.removeView(it) } catch (_: Exception) {} }
        transAdded = false
        transShown = false
        refreshing = true
        mainHandler.postDelayed(refreshCapture, HIDE_WAIT_MS)
    }

    private fun finishRefresh() {
        refreshing = false
        lastCheck = System.currentTimeMillis()
        val tv = transView
        val p = transParams
        if (tv != null && p != null && !transAdded && lastSource.isNotEmpty() && boxLocked) {
            try {
                wm.addView(tv, p)
                transAdded = true
                transShown = true
            } catch (_: Exception) {}
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
        if (!capturing) {
            busy = false
            return
        }
        val box = boxView
        val ok = try { ensureCapture() } catch (e: Exception) { false }
        if (box == null || !ok) {
            if (refreshing) finishRefresh()
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
            val g = grabCrop(r, rect)
            mainHandler.post { onCrop(g) }
        }
    }

    private fun grabCrop(r: ImageReader?, rect: Rect): Grab? {
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
            if (crop !== bmp) bmp.recycle()
            val colors = analyzeColors(crop)
            return Grab(crop, makeSig(crop), colors[0], colors[1])
        } catch (e: Exception) {
            return null
        } finally {
            img.close()
        }
    }

    // بصمة الصورة: شبكة 32x16 من متوسطات الرمادي
    private fun makeSig(b: Bitmap): IntArray {
        val gw = 32
        val gh = 16
        val out = IntArray(gw * gh)
        val w = b.width
        val h = b.height
        for (gy in 0 until gh) {
            val y0 = gy * h / gh
            val y1 = ((gy + 1) * h / gh).coerceAtLeast(y0 + 1).coerceAtMost(h)
            for (gx in 0 until gw) {
                val x0 = gx * w / gw
                val x1 = ((gx + 1) * w / gw).coerceAtLeast(x0 + 1).coerceAtMost(w)
                var sum = 0
                for (sy in 0..2) {
                    val py = (y0 + (y1 - y0) * (2 * sy + 1) / 6).coerceIn(0, h - 1)
                    for (sx in 0..2) {
                        val px = (x0 + (x1 - x0) * (2 * sx + 1) / 6).coerceIn(0, w - 1)
                        val p = b.getPixel(px, py)
                        sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                    }
                }
                out[gy * gw + gx] = sum / 9
            }
        }
        return out
    }

    private fun sameSig(a: IntArray, b: IntArray?): Boolean {
        if (b == null || a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            if (Math.abs(a[i] - b[i]) > 24) diff++
        }
        return diff < 3
    }

    private fun lum(c: Int): Int =
        (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000

    // يرجع [لون الخلفية, لون الخط]
    private fun analyzeColors(b: Bitmap): IntArray {
        val w = b.width
        val h = b.height
        val cnt = IntArray(4096)
        val sr = LongArray(4096)
        val sg = LongArray(4096)
        val sb = LongArray(4096)
        val ring = Math.max(1, Math.min(4, Math.min(w, h) / 2))

        fun add(p: Int) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (bl shr 4)
            cnt[k]++
            sr[k] += r.toLong()
            sg[k] += g.toLong()
            sb[k] += bl.toLong()
        }

        for (y in 0 until h) {
            if (y < ring || y >= h - ring) {
                var x = 0
                while (x < w) {
                    add(b.getPixel(x, y))
                    x += 2
                }
            } else {
                for (x in 0 until ring) add(b.getPixel(x, y))
                for (x in (w - ring) until w) add(b.getPixel(x, y))
            }
        }

        var best = 0
        for (i in 1 until 4096) {
            if (cnt[i] > cnt[best]) best = i
        }
        val n = Math.max(1, cnt[best]).toLong()
        val bgR = (sr[best] / n).toInt()
        val bgG = (sg[best] / n).toInt()
        val bgB = (sb[best] / n).toInt()
        val bgColor = Color.rgb(bgR, bgG, bgB)

        var fr = 0L
        var fg = 0L
        var fb = 0L
        var fn = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = b.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                if (Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(bl - bgB) > 220) {
                    fr += r.toLong()
                    fg += g.toLong()
                    fb += bl.toLong()
                    fn++
                }
                x += 3
            }
            y += 3
        }

        var fgColor = if (fn >= 12) {
            Color.rgb((fr / fn).toInt(), (fg / fn).toInt(), (fb / fn).toInt())
        } else {
            if (lum(bgColor) > 140) Color.BLACK else Color.WHITE
        }
        if (Math.abs(lum(fgColor) - lum(bgColor)) < 110) {
            fgColor = if (lum(bgColor) > 140) Color.BLACK else Color.WHITE
        }
        return intArrayOf(bgColor, fgColor)
    }

    // ---------------- معالجة النتيجة ----------------

    // الشاشة مستقرة (لا إطار جديد أو لا تغيّر): نترجم المرشح إن وُجد وإلا نعيد الترجمة
    private fun stable() {
        val c = candidate
        if (pending && c != null) {
            pending = false
            translateText(c)
        } else {
            if (refreshing) finishRefresh()
            busy = false
        }
    }

    private fun onCrop(g: Grab?) {
        if (!capturing) {
            g?.bmp?.recycle()
            busy = false
            return
        }
        if (g == null) {
            stable()
            return
        }
        if (sameSig(g.sig, lastSig)) {
            g.bmp.recycle()
            stable()
            return
        }

        lastSig = g.sig
        lastBg = g.bg
        lastFg = g.fg
        recognizer.process(InputImage.fromBitmap(g.bmp, 0))
            .addOnSuccessListener { res ->
                g.bmp.recycle()
                val text = res.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                handleText(text)
            }
            .addOnFailureListener {
                g.bmp.recycle()
                if (refreshing) finishRefresh()
                busy = false
            }
    }

    // يتأكد أن النص المقروء يشبه كلاماً إنجليزياً وليس ضوضاء
    private fun looksLikeText(t: String): Boolean {
        val chars = t.count { !it.isWhitespace() }
        val letters = t.count { it.isLetter() }
        if (letters < 2 || letters * 100 / chars < 60) return false
        return t.split(" ").any { w ->
            val x = w.filter { it.isLetter() }
            x.length >= 2 && x.any { it.lowercaseChar() in "aeiouy" }
        }
    }

    private fun handleText(text: String) {
        if (text.length < 2 || !looksLikeText(text)) {
            // لا يوجد نص: نزيل الترجمة ونراقب ظهور نص جديد
            hideTranslation()
            lastSource = ""
            updateBoxStyle()
            candidate = null
            pending = false
            refreshing = false
            busy = false
            return
        }
        if (text == lastSource) {
            pending = false
            candidate = null
            finishRefresh()
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
            if (refreshing) finishRefresh()
            busy = false
            return
        }
        TranslationEngine.translate(translator, text) { result ->
            if (capturing) {
                lastSource = text
                candidate = null
                showTranslation(result)
            }
            busy = false
        }
    }

    // ---------------- عرض الترجمة مكان النص ----------------

    private fun showTranslation(text: String) {
        val bp = boxParams ?: return
        if (!boxLocked) return

        val inset = 6
        val w = (bp.width - inset * 2).coerceAtLeast(60)
        val h = (bp.height - inset * 2).coerceAtLeast(40)

        val tv = transView ?: TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            includeFontPadding = false
        }.also { transView = it }

        val alphaPct = Prefs.getInt(this, "alpha", 100)
        val bgWithAlpha = (lastBg and 0x00FFFFFF) or ((alphaPct * 255 / 100) shl 24)
        val roundPct = Prefs.getInt(this, "radius", 25)
        val corner = (h / 2f) * roundPct / 100f
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(bgWithAlpha)
        }
        val padX = 14 + (corner * 0.6f).toInt()
        tv.setPadding(padX, 8, padX, 8)
        tv.setTextColor(lastFg)
        tv.text = text

        // أكبر خط يتسع داخل المربع حسب إعداد الحجم
        val fontPct = Prefs.getInt(this, "font", 100)
        var size = 26f * fontPct / 100f
        while (size >= 9f) {
            tv.textSize = size
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            if (tv.measuredHeight <= h) break
            size -= 1f
        }

        val p = transParams ?: WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }.also { transParams = it }

        p.width = w
        p.height = h
        p.x = bp.x + inset
        p.y = bp.y + inset

        tv.alpha = 1f
        if (transAdded) {
            wm.updateViewLayout(tv, p)
        } else {
            wm.addView(tv, p)
            transAdded = true
        }
        transShown = true
        refreshing = false
        lastCheck = System.currentTimeMillis()
        updateBoxStyle()
    }

    private fun hideTranslation() {
        transView?.let { if (transAdded) try { wm.removeView(it) } catch (_: Exception) {} }
        transView = null
        transParams = null
        transAdded = false
        transShown = false
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

// مقبض التكبير والتصغير: سهمان متعاكسان بخلفية شفافة
private class ResizeHandle(ctx: Context) : View(ctx) {
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        strokeWidth = 11f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFEB3B")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.2f
        val len = w * 0.28f
        val x1 = pad
        val y1 = pad
        val x2 = w - pad
        val y2 = h - pad
        for (p in arrayOf(dark, light)) {
            c.drawLine(x1, y1, x2, y2, p)
            c.drawLine(x2, y2, x2 - len, y2, p)
            c.drawLine(x2, y2, x2, y2 - len, p)
            c.drawLine(x1, y1, x1 + len, y1, p)
            c.drawLine(x1, y1, x1, y1 + len, p)
        }
    }
}
