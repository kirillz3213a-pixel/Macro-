package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.hypot

class MacroRecorder(
    private val svc: AccessibilityService,
    private val onDone: (List<Step>) -> Unit
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val metrics = svc.resources.displayMetrics
    private val steps = mutableListOf<Step>()
    private var lastEnd = 0L
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var canvas: View? = null
    private var bar: View? = null
    private var textBox: View? = null

    private fun params(w: Int, h: Int, focusable: Boolean, grav: Int): WindowManager.LayoutParams {
        val flags = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = grav }
    }

    fun start() {
        val touch = View(svc).apply {
            setBackgroundColor(Color.parseColor("#14000000"))
            setOnTouchListener { _, e -> onTouch(e) }
        }
        wm.addView(touch, params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, false, Gravity.TOP or Gravity.START
        ))
        canvas = touch

        val panel = buildBar()
        wm.addView(panel, params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, false, Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { y = 80 })
        bar = panel

        Toast.makeText(svc, "Запись: делай действия на экране. Кнопки сверху", Toast.LENGTH_LONG).show()
    }

    // нажатия не передаются приложению под слоем: это слой записи
    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                downT = SystemClock.elapsedRealtime()
                gap(downT)
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.elapsedRealtime()
                val w = metrics.widthPixels.coerceAtLeast(1).toFloat()
                val h = metrics.heightPixels.coerceAtLeast(1).toFloat()
                val moved = hypot(e.rawX - downX, e.rawY - downY)
                val step = if (moved < 30f) {
                    Step("tap", x1 = downX / w, y1 = downY / h, ms = (now - downT).coerceIn(50L, 1000L))
                } else {
                    Step("swipe", x1 = downX / w, y1 = downY / h, x2 = e.rawX / w, y2 = e.rawY / h,
                        ms = (now - downT).coerceIn(150L, 3000L))
                }
                steps += step
                lastEnd = now
            }
        }
        return true
    }

    // если между действиями прошло больше 200 мс, записываем паузу
    private fun gap(t: Long) {
        if (lastEnd > 0L && t - lastEnd > 200L) steps += Step("wait", ms = t - lastEnd)
    }

    private fun pushAction(s: Step) {
        val now = SystemClock.elapsedRealtime()
        gap(now)
        steps += s
        lastEnd = now
    }

    private fun buildBar(): View {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E0141821"))
            setPadding(8, 8, 8, 8)
        }
        fun btn(label: String, color: Int, action: () -> Unit) {
            row.addView(Button(svc).apply {
                text = label
                textSize = 12f
                transformationMethod = null
                setTextColor(Color.WHITE)
                setBackgroundColor(color)
                setOnClickListener { action() }
            })
        }
        btn("⌫ Назад", Color.parseColor("#3A4256")) {
            pushAction(Step("back"))
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        btn("⌂ Домой", Color.parseColor("#3A4256")) {
            pushAction(Step("home"))
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        btn("⏱ +1с", Color.parseColor("#3A4256")) { pushAction(Step("wait", ms = 1000)) }
        btn("✎ Текст", Color.parseColor("#3A4256")) { showTextBox() }
        btn("✔ Готово", Color.parseColor("#2E7D4F")) { finish(true) }
        btn("✕", Color.parseColor("#8B2E2E")) { finish(false) }
        return row
    }

    private fun showTextBox() {
        if (textBox != null) return
        val input = EditText(svc).apply {
            hint = "Текст для поля"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#2A2F3D"))
        }
        val ok = Button(svc).apply { text = "Добавить" }
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E0141821"))
            setPadding(16, 16, 16, 16)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ok)
        }
        ok.setOnClickListener {
            val t = input.text.toString()
            if (t.isNotEmpty()) pushAction(Step("text", text = t))
            hideTextBox()
        }
        wm.addView(box, params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT, true, Gravity.BOTTOM
        ))
        textBox = box
    }

    private fun hideTextBox() {
        val v = textBox ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        textBox = null
    }

    fun cancel() = finish(false)

    private fun finish(save: Boolean) {
        hideTextBox()
        canvas?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        canvas = null
        bar = null
        if (save) onDone(steps.toList())
    }
}
