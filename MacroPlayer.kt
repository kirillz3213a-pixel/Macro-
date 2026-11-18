package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

class MacroPlayer(
    private val svc: AccessibilityService,
    private val steps: List<Step>,
    private val repeats: Int,          // 0 = до СТОП
    private val onFinish: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val metrics = svc.resources.displayMetrics
    private var index = 0
    private var round = 0
    private var done = false

    fun start() {
        handler.post { next() }
    }

    // остановка по СТОП: onFinish не вызывается, сбрасывает disarm() сам
    fun stop() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun finish() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        onFinish()
    }

    private fun next() {
        if (done) return
        if (index >= steps.size) {
            round++
            if (steps.isEmpty() || (repeats > 0 && round >= repeats)) {
                finish()
                return
            }
            index = 0
        }
        val s = steps[index++]
        when (s.type) {
            "tap", "swipe" -> gesture(s)
            "wait" -> handler.postDelayed({ next() }, s.ms.coerceIn(0L, 60000L))
            "back" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                handler.postDelayed({ next() }, 400)
            }
            "home" -> {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                handler.postDelayed({ next() }, 600)
            }
            "text" -> {
                typeText(s.text)
                handler.postDelayed({ next() }, 150)
            }
            else -> next()
        }
    }

    private fun gesture(s: Step) {
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(s.x1 * w, s.y1 * h)
            if (s.type == "swipe") lineTo(s.x2 * w, s.y2 * h) else lineTo(s.x1 * w + 1f, s.y1 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, s.ms.coerceAtLeast(50L))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ next() }, 120)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ next() }, 120)
            }
        }, null)
        if (!ok) handler.postDelayed({ next() }, 300)
    }

    private fun typeText(t: String) {
        val node = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + t)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
