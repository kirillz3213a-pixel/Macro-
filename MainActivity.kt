package com.autotyper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1D24")
    private val FIELD = Color.parseColor("#242833")
    private val ACCENT = Color.parseColor("#4F8CFF")
    private val TEXT = Color.parseColor("#E8EAF0")
    private val MUTED = Color.parseColor("#8A90A0")
    private val RED = Color.parseColor("#E5484D")
    private val GREEN = Color.parseColor("#3DD68C")

    private val prefs by lazy { getSharedPreferences("typer", Context.MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var btnService: Button
    private lateinit var btnNormal: Button
    private lateinit var btnMessages: Button
    private lateinit var modeHint: TextView
    private lateinit var etText: EditText
    private lateinit var counter: TextView
    private lateinit var etDelay: EditText
    private lateinit var etStep: EditText
    private lateinit var etPause: EditText
    private lateinit var rowPause: LinearLayout
    private lateinit var cbJitter: CheckBox
    private lateinit var cbSend: CheckBox
    private lateinit var cbNewline: CheckBox
    private var messages = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(CARD, 16)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(MUTED)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun field(hintText: String, numeric: Boolean = false) = EditText(this).apply {
        hint = hintText
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = rounded(FIELD)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun btn(t: String, fill: Int, color: Int) = Button(this).apply {
        text = t
        transformationMethod = null
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        background = rounded(fill, 14)
        stateListAnimator = null
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun splitParts(t: String): List<String> {
        val newline = ::cbNewline.isInitialized && messages && cbNewline.isChecked
        val regex = if (newline) Regex("[,，\\r\\n]+") else Regex("[,，]+")
        return t.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        messages = prefs.getBoolean("messages", false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "AutoTyper"
            textSize = 28f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(status)

        btnService = btn("Включить службу (Спец. возможности)", FIELD, TEXT).apply {
            textSize = 14f
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(btnService, lp(4))

        // ---- режим ----
        val modeCard = card()
        modeCard.addView(label("РЕЖИМ"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnNormal = btn("Обычный", FIELD, TEXT).apply { setOnClickListener { setMode(false) } }
        btnMessages = btn("Сообщения", FIELD, TEXT).apply { setOnClickListener { setMode(true) } }
        modeRow.addView(btnNormal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        modeRow.addView(btnMessages, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        modeCard.addView(modeRow)
        modeHint = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        modeCard.addView(modeHint)
        root.addView(modeCard, lp(12))

        // ---- текст ----
        val textCard = card()
        textCard.addView(label("ТЕКСТ"))
        etText = field("Например: привет как дела, я сегодня пошел в парк").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setText(prefs.getString("text", ""))
        }
        textCard.addView(etText)
        textCard.addView(btn("📄  Загрузить .txt", FIELD, TEXT).apply {
            textSize = 14f
            setOnClickListener { pickTxt() }
        }, lp(8))
        counter = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        textCard.addView(counter)
        textCard.addView(TextView(this).apply {
            text = "Спец. клавиши: {ENTER} {BACK} {SEND} {WAIT:500}"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCounter()
        })
        root.addView(textCard, lp(12))

        // ---- скорость и опции ----
        val setCard = card()
        setCard.addView(label("СКОРОСТЬ"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etDelay = field("Задержка, мс", true).apply { setText(prefs.getLong("delay", 40).toString()) }
        etStep = field("Символов за шаг", true).apply { setText(prefs.getInt("step", 1).toString()) }
        row.addView(etDelay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(etStep, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        setCard.addView(row)

        rowPause = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rowPause.addView(TextView(this).apply {
            text = "Пауза между сообщениями, мс"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(10), 0, dp(6))
        })
        etPause = field("800", true).apply { setText(prefs.getLong("pause", 800).toString()) }
        rowPause.addView(etPause)
        setCard.addView(rowPause)

        cbJitter = CheckBox(this).apply {
            text = "Случайная вариация скорости"
            setTextColor(TEXT)
            isChecked = prefs.getBoolean("jitter", true)
        }
        setCard.addView(cbJitter, lp(8))

        cbSend = CheckBox(this).apply {
            text = "Автоотправка после ввода"
            setTextColor(TEXT)
            isChecked = prefs.getBoolean("send", false)
        }
        setCard.addView(cbSend)

        cbNewline = CheckBox(this).apply {
            text = "Новая строка = новое сообщение"
            setTextColor(TEXT)
            isChecked = prefs.getBoolean("newline", false)
            setOnCheckedChangeListener { _, _ -> updateCounter() }
        }
        setCard.addView(cbNewline)
        root.addView(setCard, lp(12))

        // ---- кнопки ----
        root.addView(btn("▶  Готово — выбрать поле", ACCENT, Color.WHITE).apply {
            setOnClickListener { arm() }
        }, lp(16))
        root.addView(btn("■  Стоп", Color.TRANSPARENT, RED).apply {
            setOnClickListener {
                TyperService.instance?.disarm()
                refreshStatus()
            }
        }, lp(10))
        root.addView(btn("🎬  Макросы", FIELD, GREEN).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, MacrosActivity::class.java)) }
        }, lp(10))

        root.addView(TextView(this).apply {
            text = "Нажми «Готово», открой любое приложение и тапни по полю ввода. " +
                "Красная кнопка «СТОП» висит поверх экрана."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
        setMode(messages)
    }

    private fun setMode(msgs: Boolean) {
        messages = msgs
        btnNormal.background = rounded(if (!msgs) ACCENT else FIELD, 14)
        btnNormal.setTextColor(if (!msgs) Color.WHITE else TEXT)
        btnMessages.background = rounded(if (msgs) ACCENT else FIELD, 14)
        btnMessages.setTextColor(if (msgs) Color.WHITE else TEXT)
        modeHint.text = if (msgs) {
            "Запятая (или новая строка) = конец сообщения. Каждая часть набирается и отправляется отдельно."
        } else {
            "Весь текст набирается в поле как есть."
        }
        rowPause.visibility = if (msgs) android.view.View.VISIBLE else android.view.View.GONE
        cbNewline.visibility = if (msgs) android.view.View.VISIBLE else android.view.View.GONE
        cbSend.isEnabled = !msgs
        updateCounter()
    }

    private fun updateCounter() {
        if (!::counter.isInitialized) return
        val t = etText.text.toString()
        counter.text = if (messages) "Сообщений: ${splitParts(t).size}" else "Символов: ${t.length}"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.edit()
            .putString("text", etText.text.toString())
            .putLong("delay", etDelay.text.toString().toLongOrNull() ?: 40L)
            .putInt("step", etStep.text.toString().toIntOrNull() ?: 1)
            .putLong("pause", etPause.text.toString().toLongOrNull() ?: 800L)
            .putBoolean("jitter", cbJitter.isChecked)
            .putBoolean("send", cbSend.isChecked)
            .putBoolean("newline", cbNewline.isChecked)
            .putBoolean("messages", messages)
            .apply()
    }

    private fun refreshStatus() {
        val on = TyperService.instance != null
        btnService.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        status.setTextColor(if (on) GREEN else RED)
        status.text = when {
            !on -> "Служба выключена"
            TyperState.armed -> "Служба включена · ожидаю тап по полю ввода…"
            else -> "Служба включена · готов к работе"
        }
    }

    private fun arm() {
        val svc = TyperService.instance
        if (svc == null) {
            toast("Сначала включи службу")
            return
        }
        val text = etText.text.toString()
        val hasText = if (messages) splitParts(text).isNotEmpty() else text.isNotBlank()
        if (!hasText) {
            toast("Введи текст")
            return
        }
        save()
        TyperState.text = text
        TyperState.delayMs = etDelay.text.toString().toLongOrNull()?.coerceIn(0L, 5000L) ?: 40L
        TyperState.step = etStep.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 1
        TyperState.pauseMs = etPause.text.toString().toLongOrNull()?.coerceIn(0L, 60000L) ?: 800L
        TyperState.jitter = cbJitter.isChecked
        TyperState.splitMode = messages
        TyperState.splitNewline = messages && cbNewline.isChecked
        TyperState.autoSend = cbSend.isChecked || messages
        svc.arm()
        toast("Открой приложение и тапни по полю ввода")
        moveTaskToBack(true)
    }

    // ---------- загрузка .txt ----------

    private fun pickTxt() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(i, 42)
    }

    @Deprecated("Используем стандартный механизм результатов Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                val r = TxtLoader.load(this, uri)
                runOnUiThread {
                    etText.setText(r.text)
                    Toast.makeText(this, "Загружено: ${r.name} · ${r.text.length} символов", Toast.LENGTH_LONG).show()
                }
            } catch (e: IllegalArgumentException) {
                runOnUiThread { toast("Файл слишком большой (макс. 1 МБ)") }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось прочитать файл") }
            }
        }.start()
    }
}
