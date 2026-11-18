package com.autotyper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MacrosActivity : Activity() {

    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1D24")
    private val FIELD = Color.parseColor("#242833")
    private val ACCENT = Color.parseColor("#4F8CFF")
    private val TEXT = Color.parseColor("#E8EAF0")
    private val MUTED = Color.parseColor("#8A90A0")
    private val RED = Color.parseColor("#E5484D")

    private lateinit var etName: EditText
    private lateinit var etRepeats: EditText
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun button(t: String, fill: Int, textColor: Int) = Button(this).apply {
        text = t
        transformationMethod = null
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        background = rounded(fill)
        stateListAnimator = null
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(MUTED)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Макросы"
            textSize = 26f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })

        val newCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(CARD)
        }
        newCard.addView(label("НОВЫЙ МАКРОС"))

        etName = EditText(this).apply {
            hint = "Название, например «Вход в чат»"
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            background = rounded(FIELD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSingleLine(true)
        }
        newCard.addView(etName, lp())

        etRepeats = EditText(this).apply {
            hint = "Повторов (0 = пока не нажмёшь СТОП)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            background = rounded(FIELD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        newCard.addView(etRepeats, lp(8))

        newCard.addView(button("● Записать", RED, Color.WHITE).apply {
            setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@MacrosActivity, "Введи название", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val svc = TyperService.instance
                if (svc == null) {
                    Toast.makeText(this@MacrosActivity, "Сначала включи службу", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                svc.startRecording(name)
                moveTaskToBack(true)
            }
        }, lp(12))
        root.addView(newCard)

        root.addView(TextView(this).apply {
            text = "Сохранённые"
            textSize = 16f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val names = MacroStore.names(this)
        if (names.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Записанных макросов пока нет"
                textSize = 14f
                setTextColor(MUTED)
            })
            return
        }
        names.forEach { name ->
            val count = MacroStore.load(this, name)?.size ?: 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(CARD)
            }
            card.addView(TextView(this).apply {
                text = name
                textSize = 17f
                setTextColor(TEXT)
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = "Шагов: $count"
                textSize = 13f
                setTextColor(MUTED)
            })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(
                button("▶ Запустить", ACCENT, Color.WHITE).apply {
                    setOnClickListener { startMacro(name) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                button("✕", FIELD, RED).apply {
                    setOnClickListener {
                        MacroStore.delete(this@MacrosActivity, name)
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            card.addView(row)
            list.addView(card, lp(10))
        }
    }

    private fun startMacro(name: String) {
        val svc = TyperService.instance
        if (svc == null) {
            Toast.makeText(this, "Сначала включи службу", Toast.LENGTH_LONG).show()
            return
        }
        val repeats = etRepeats.text.toString().toIntOrNull()?.coerceIn(0, 9999) ?: 1
        svc.playMacro(name, repeats)
        moveTaskToBack(true)
    }
}
