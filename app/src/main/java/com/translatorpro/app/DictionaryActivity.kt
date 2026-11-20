package com.translatorpro.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class DictionaryActivity : Activity() {

    private lateinit var input: EditText

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
            text = "قاموسي الخاص"
            textSize = 24f
            setTextColor(Color.parseColor("#1565C0"))
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "اكتب كل مدخل في سطر مستقل بهذه الصيغة:\nEnglish = العربية\n\nمثال:\nZagreus = زاغريوس\nPress any key = اضغط أي زر\n\nالأفضل للأسماء والمصطلحات المستقلة. يُطبَّق فوراً على الترجمات الجديدة."
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(14), 0, dp(14))
        })

        input = EditText(this).apply {
            setText(Prefs.getString(this@DictionaryActivity, "userDict", ""))
            hint = "Zagreus = زاغريوس"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
            gravity = Gravity.TOP or Gravity.LEFT
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 16f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F5F5F5"))
            }
        }
        root.addView(input)

        root.addView(Button(this).apply {
            text = "حفظ"
            setTextColor(Color.WHITE)
            textSize = 16f
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1565C0"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(18)
            }
            setOnClickListener {
                save()
                Toast.makeText(this@DictionaryActivity, "تم الحفظ ✅", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }

    private fun save() {
        Prefs.putString(this, "userDict", input.text.toString())
        UserDict.load(this)
    }

    override fun onPause() {
        super.onPause()
        save()
    }
}
