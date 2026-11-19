package com.translatorpro.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class SettingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        header.addView(TextView(this).apply {
            text = "→"
            textSize = 28f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "الإعدادات"
            textSize = 24f
            setTextColor(Color.parseColor("#1565C0"))
        })
        root.addView(header)

        addSetting(
            root, "الفحص عند لمس الشاشة",
            "بعد لمسك الشاشة تخفي الترجمة لحظة لتتأكد من تغيّر النص",
            "touch", 1, listOf("تشغيل", "إيقاف"), listOf(1, 0)
        )
        addSetting(
            root, "فحص تلقائي كل",
            "للمشاهد التي يتغير نصها بدون لمس (يسبب وميضاً قصيراً)",
            "interval", 10, listOf("إيقاف", "5 ث", "10 ث", "20 ث"), listOf(0, 5, 10, 20)
        )
        addSetting(
            root, "حجم خط الترجمة", "",
            "font", 100, listOf("صغير", "عادي", "كبير", "كبير جداً"), listOf(80, 100, 120, 140)
        )
        addSetting(
            root, "شفافية خلفية الترجمة", "",
            "alpha", 100, listOf("كاملة", "85%", "70%"), listOf(100, 85, 70)
        )
        addSlider(root, "استدارة زوايا مربع الترجمة", "radius", 25)

        root.addView(TextView(this).apply {
            text = "التغييرات تُطبّق فوراً، وتظهر عند الترجمة التالية."
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(28), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }

    private fun addSlider(parent: LinearLayout, title: String, key: String, def: Int) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, dp(22), 0, dp(2))
        })

        val preview = TextView(this).apply {
            text = "معاينة"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(44)).apply { topMargin = dp(10) }
        }
        fun paint(pct: Int) {
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(44) / 2f * pct / 100f
                setColor(Color.parseColor("#616161"))
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = Prefs.getInt(this@SettingsActivity, key, def)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    paint(p)
                    if (fromUser) Prefs.putInt(this@SettingsActivity, key, p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        paint(bar.progress)
        row.addView(TextView(this).apply { text = "حادة"; textSize = 12f })
        row.addView(bar)
        row.addView(TextView(this).apply { text = "دائرية"; textSize = 12f })
        parent.addView(row)
        parent.addView(preview)
    }

    private fun addSetting(
        parent: LinearLayout, title: String, hint: String, key: String, def: Int,
        labels: List<String>, values: List<Int>
    ) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, dp(22), 0, dp(2))
        })
        if (hint.isNotEmpty()) {
            parent.addView(TextView(this).apply {
                text = hint
                textSize = 12f
                setTextColor(Color.parseColor("#9E9E9E"))
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(8), 0, 0)
        }
        val chips = ArrayList<TextView>()

        fun refresh() {
            val cur = Prefs.getInt(this, key, def)
            chips.forEachIndexed { i, c ->
                val sel = values[i] == cur
                c.setTextColor(if (sel) Color.WHITE else Color.parseColor("#424242"))
                c.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor(if (sel) "#1565C0" else "#EEEEEE"))
                }
            }
        }

        labels.forEachIndexed { i, l ->
            val c = TextView(this).apply {
                text = l
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    Prefs.putInt(this@SettingsActivity, key, values[i])
                    refresh()
                }
            }
            chips.add(c)
            row.addView(c)
        }
        refresh()
        parent.addView(row)
    }
}
