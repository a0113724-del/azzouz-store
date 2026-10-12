package com.quotex.signal

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.util.Calendar
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class C(val dir: Int, val hi: Int, val lo: Int)

class SignalService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var tv: TextView
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private val h = Handler(Looper.getMainLooper())
    private var lastMin = -1L
    private var n = 3; private var per = 21; private var rev = true
    private var top = .18f; private var bottom = .62f; private var left = 0f; private var right = .88f
    private var W = 0; private var H = 0

    override fun onBind(i: Intent?) = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sig", "Signals", NotificationManager.IMPORTANCE_LOW))
        val nt = Notification.Builder(this, "sig").setContentTitle("إشارات الشموع تعمل").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, nt, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val p = getSharedPreferences("cfg", 0)
        n = p.getInt("n", 3).coerceIn(2, 5); per = p.getInt("per", 21).coerceIn(5, 60); rev = p.getInt("mode", 1) != 2
        top = p.getInt("top", 18) / 100f; bottom = p.getInt("bottom", 62) / 100f
        left = p.getInt("left", 0) / 100f; right = p.getInt("right", 88) / 100f

        val dm = resources.displayMetrics; W = dm.widthPixels; H = dm.heightPixels
        val mpm = getSystemService(MediaProjectionManager::class.java)
        proj = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra<Intent>("data")!!)
        proj!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        proj!!.createVirtualDisplay("cap", W, H, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)

        wm = getSystemService(WindowManager::class.java)
        tv = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); setPadding(30, 14, 30, 14); setBackgroundColor(0xCC334155.toInt()); gravity = Gravity.CENTER; text = "انتظار الشمعة..." }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 40 }
        wm.addView(tv, lp)
        h.post(loop)
        return START_NOT_STICKY
    }

    private val loop = object : Runnable { override fun run() { tick(); h.postDelayed(this, 500) } }

    private fun tick() {
        val img = reader?.acquireLatestImage() ?: return
        val sec = Calendar.getInstance().get(Calendar.SECOND)
        val m = System.currentTimeMillis() / 60000
        if (sec in 1..4 && m != lastMin) {
            lastMin = m
            val pl = img.planes[0]
            val bmp = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            val x0 = (left * W).toInt(); val y0 = (top * H).toInt()
            val rw = ((right - left) * W).toInt(); val rh = ((bottom - top) * H).toInt()
            val px = IntArray(rw * rh)
            Bitmap.createBitmap(bmp, x0, y0, rw, rh).getPixels(px, 0, rw, 0, 0, rw, rh)
            bmp.recycle()
            evaluate(detect(px, rw, rh))
        }
        img.close()
    }

    private fun detect(px: IntArray, w: Int, hh: Int): List<C> {
        val col = IntArray(w); val top = IntArray(w); val bot = IntArray(w)
        for (i in 0 until w) {
            var gt = 1 shl 30; var gb = -1; var rt = 1 shl 30; var rb = -1; var gn = 0; var rn = 0
            for (j in 0 until hh) {
                val p = px[j * w + i]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
                if (g > 140 && g - r > 60 && g > b) { gn++; if (j < gt) gt = j; gb = j }
                else if (r > 180 && r - g > 80 && r - b > 80) { rn++; if (j < rt) rt = j; rb = j }
            }
            if (gn >= 2 && gn >= rn) { col[i] = 1; top[i] = gt; bot[i] = gb } else if (rn >= 2) { col[i] = -1; top[i] = rt; bot[i] = rb }
        }
        val cs = ArrayList<C>(); var i = 0
        while (i < w) {
            if (col[i] == 0) { i++; continue }
            val t = col[i]; val s = i; var hi = 1 shl 30; var lo = -1
            while (i < w && col[i] == t) { hi = minOf(hi, top[i]); lo = maxOf(lo, bot[i]); i++ }
            if (i - s >= 2) cs.add(C(t, -hi, -lo))
        }
        return cs
    }

    private fun evaluate(all: List<C>) {
        val c = all.dropLast(1)
        val seq = all.takeLast(8).mapIndexed { k, x -> if (k == all.takeLast(8).size - 1) "⏳" else if (x.dir > 0) "🟢" else "🔴" }.joinToString("")
        if (c.size < maxOf(per, n + 1)) { show(0, "لا توجد صفقة\nقرأ ${c.size} شمعة فقط\n$seq"); return }
        val win = c.takeLast(per); val run = c.takeLast(n); val l = c.last()
        val up = run.all { it.dir == 1 }; val dn = run.all { it.dir == -1 }
        val atHigh = l.hi >= win.maxOf { it.hi } - 2; val atLow = l.lo <= win.minOf { it.lo } + 2
        val s = when {
            up && atHigh -> if (rev) -1 else 1
            dn && atLow -> if (rev) 1 else -1
            else -> 0
        }
        show(s, when (s) { 1 -> "CALL ↑ شراء\nادخل على الشمعة الحالية، دقيقة"; -1 -> "PUT ↓ بيع\nادخل على الشمعة الحالية، دقيقة"; else -> "لا توجد صفقة" } + "\n$seq")
        if (s != 0) try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 400) } catch (_: Exception) {}
    }

    private fun show(s: Int, t: String) {
        tv.text = t
        tv.setBackgroundColor(when (s) { 1 -> 0xEE17B26A.toInt(); -1 -> 0xEEF04438.toInt(); else -> 0xCC334155.toInt() })
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { wm.removeView(tv) } catch (_: Exception) {}
        proj?.stop(); super.onDestroy()
    }
}
