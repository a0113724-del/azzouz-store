package com.quotex.signal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cfg", 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun tv(t: String, sz: Float = 15f) = TextView(this).apply { text = t; textSize = sz; setPadding(0, 12, 0, 4) }
        root.addView(tv("إشارات شموع الدقيقة (قاعدة Atrium)", 20f))
        root.addView(tv("للتجربة على Demo فقط. لا يوجد ضمان ربح."))
        fun field(label: String, key: String, def: String) {
            root.addView(tv(label))
            val e = EditText(this).apply { setText(p.getString(key, def)); inputType = 2; gravity = Gravity.START }
            fields[key] = e; root.addView(e)
        }
        field("عدد الشموع المتتالية (2-5)", "n", "3")
        field("الفترة (عدد الشموع)", "per", "21")
        field("القاعدة: 1 = عكسية (الافتراضية) ، 2 = مع الاتجاه", "mode", "1")
        root.addView(tv("منطقة الشارت من الشاشة (نسبة مئوية). لا تدخل فيها الأسعار والأزرار:"))
        field("من فوق %", "top", "18")
        field("إلى تحت %", "bottom", "62")
        field("من اليسار %", "left", "0")
        field("إلى اليمين %", "right", "88")
        root.addView(Button(this).apply { text = "تشغيل"; setOnClickListener { start() } })
        root.addView(Button(this).apply { text = "إيقاف"; setOnClickListener { stopService(Intent(this@MainActivity, SignalService::class.java)) } })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "فعّل العرض فوق التطبيقات ثم ارجع واضغط تشغيل", Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val e = getSharedPreferences("cfg", 0).edit()
        fields.forEach { (k, v) -> e.putInt(k, v.text.toString().toIntOrNull() ?: 0) }
        e.apply()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, SignalService::class.java).putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
