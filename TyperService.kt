package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

class TyperService : AccessibilityService() {

    companion object {
        @Volatile var instance: TyperService? = null
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_TREE_NODES = 1500
        private const val MIN_SET_INTERVAL_MS = 30L
        private val SEND_WORDS = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
        private val EXACT_SEND = setOf("send", "отправить", "надіслати", "відправити", "отправка", "submit")
        private val BLACKLIST = listOf(
            "send as", "send_as", "sendas", "as_", "от имени", "от лица", "від імені",
            "написать от", "написати від", "schedule", "запланир", "заплан", "voice",
            "audio", "голос", "attach", "вложен", "вкладен", "emoji", "sticker", "стикер"
        )
    }

    private sealed class Action {
        data class Text(val value: String) : Action()
        object Enter : Action()
        object Back : Action()
        data class Wait(val ms: Long) : Action()
        object Send : Action()
    }

    private class Candidate(
        val node: AccessibilityNodeInfo,
        val exact: Boolean,
        val right: Int,
        val bottom: Int,
        val centerY: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var typing = false
    private var checkPending = false
    private var parts: List<List<Action>> = emptyList()
    private var partIndex = 0
    private var actionIndex = 0
    private var currentText = StringBuilder()
    private var base = ""
    private var cachedInput: AccessibilityNodeInfo? = null
    private var lostTicks = 0
    private var sendAttempted = false
    private var lastSetTextAt = 0L
    private var recorder: MacroRecorder? = null
    private var player: MacroPlayer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disarm()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // ---------- управление ----------

    fun arm() {
        typing = false
        parts = emptyList()
        cachedInput = null
        TyperState.armed = true
        showOverlay()
    }

    fun disarm() {
        recorder?.cancel()
        recorder = null
        player?.stop()
        player = null
        TyperState.armed = false
        typing = false
        checkPending = false
        parts = emptyList()
        partIndex = 0
        actionIndex = 0
        currentText = StringBuilder()
        cachedInput = null
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
    }

    // ---------- макросы ----------

    fun startRecording(name: String) {
        disarm()
        recorder = MacroRecorder(this) { steps ->
            MacroStore.save(this, name, steps)
            recorder = null
            Toast.makeText(this, "Макрос «$name» сохранён (${steps.size} шагов)", Toast.LENGTH_LONG).show()
        }.also { it.start() }
    }

    fun playMacro(name: String, repeats: Int) {
        val steps = MacroStore.load(this, name)
        if (steps == null) {
            Toast.makeText(this, "Макрос не найден", Toast.LENGTH_SHORT).show()
            return
        }
        disarm()
        showOverlay()
        player = MacroPlayer(this, steps, repeats) {
            player = null
            disarm()
        }.also { it.start() }
    }

    // ---------- ждём тап по полю ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (typing || !TyperState.armed) return
        if (event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (checkPending) return
                checkPending = true
                handler.postDelayed({
                    checkPending = false
                    tryStart()
                }, 150)
            }
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    private fun currentInput(): AccessibilityNodeInfo? {
        val cached = cachedInput
        if (cached != null) {
            val ok = try { cached.refresh() } catch (_: Exception) { false }
            if (ok && cached.isEditable) return cached
        }
        return focusedInput().also { cachedInput = it }
    }

    private fun tryStart() {
        if (!TyperState.armed || typing) return
        val input = focusedInput() ?: return
        if (input.packageName == packageName) return

        val raw: List<String> = if (TyperState.splitMode) {
            val regex = if (TyperState.splitNewline) Regex("[,，\\r\\n]+") else Regex("[,，]+")
            TyperState.text.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(TyperState.text).filter { it.isNotEmpty() }
        }
        if (raw.isEmpty()) {
            disarm()
            return
        }
        parts = raw.map(::parseTokens)
        partIndex = 0
        actionIndex = 0
        currentText = StringBuilder()
        base = ""   // всегда с пустого поля: плейсхолдер и старый текст не попадут в сообщение
        cachedInput = input
        lostTicks = 0
        sendAttempted = false
        typing = true
        tick()
    }

    // ---------- разбор токенов {ENTER} {BACK} {SEND} {WAIT:ms} ----------

    private fun parseTokens(input: String): List<Action> {
        val result = mutableListOf<Action>()
        var cursor = 0
        for (m in Regex("\\{([^{}]+)\\}").findAll(input)) {
            addText(result, input.substring(cursor, m.range.first))
            val token = m.groupValues[1].trim()
            val wait = Regex("(?i)WAIT:(\\d+)").matchEntire(token)
            when {
                token.equals("ENTER", true) -> result += Action.Enter
                token.equals("BACK", true) -> result += Action.Back
                token.equals("SEND", true) -> result += Action.Send
                wait != null -> result += Action.Wait(wait.groupValues[1].toLongOrNull()?.coerceIn(0L, 60000L) ?: 0L)
                else -> addText(result, m.value)
            }
            cursor = m.range.last + 1
        }
        addText(result, input.substring(cursor))
        return result
    }

    private fun addText(target: MutableList<Action>, text: String) {
        var i = 0
        while (i < text.length) {
            val next = text.offsetByCodePoints(i, 1)
            target += Action.Text(text.substring(i, next))
            i = next
        }
    }

    // ---------- сам набор ----------

    private fun tick() {
        if (!typing) return
        if (partIndex >= parts.size) {
            finish()
            return
        }
        val actions = parts[partIndex]
        if (actionIndex >= actions.size) {
            onPartComplete()
            return
        }
        val node = currentInput()
        if (node == null) {
            if (++lostTicks > 40) {
                Toast.makeText(this, "Поле потеряно — остановлено", Toast.LENGTH_SHORT).show()
                disarm()
                return
            }
            handler.postDelayed({ tick() }, 250)
            return
        }
        lostTicks = 0
        when (val a = actions[actionIndex]) {
            is Action.Text -> typeChunk(node, actions)
            Action.Enter -> {
                actionIndex++
                if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    scheduleNext()
                } else {
                    currentText.append('\n')
                    setText(node) { scheduleNext() }
                }
            }
            Action.Back -> {
                actionIndex++
                removeLastChar()
                setText(node) { scheduleNext() }
            }
            is Action.Wait -> {
                actionIndex++
                handler.postDelayed({ tick() }, a.ms)
            }
            Action.Send -> {
                actionIndex++
                sendCurrentPart()
                scheduleNext()
            }
        }
    }

    private fun typeChunk(node: AccessibilityNodeInfo, actions: List<Action>) {
        val total = actions.sumOf { (it as? Action.Text)?.value?.length ?: 0 }
        val step = if (TyperState.step == 1 && total > 200) 2 else TyperState.step.coerceAtLeast(1)
        val chunk = StringBuilder()
        var taken = 0
        while (actionIndex < actions.size && taken < step) {
            val t = actions[actionIndex] as? Action.Text ?: break
            chunk.append(t.value)
            actionIndex++
            taken++
        }
        currentText.append(chunk)
        setText(node) { scheduleNext() }
    }

    private fun removeLastChar() {
        if (currentText.isEmpty()) return
        val end = currentText.length
        val start = currentText.offsetByCodePoints(end, -1)
        currentText.delete(start, end)
    }

    private fun setText(node: AccessibilityNodeInfo, then: () -> Unit) {
        val wait = MIN_SET_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSetTextAt)
        handler.postDelayed({
            if (typing) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + currentText.toString())
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                lastSetTextAt = SystemClock.elapsedRealtime()
                then()
            }
        }, wait.coerceAtLeast(0L))
    }

    private fun scheduleNext() {
        var d = TyperState.delayMs
        if (TyperState.jitter && d > 0) d = (d * (0.5 + Random.nextDouble())).toLong()
        handler.postDelayed({ tick() }, d.coerceAtLeast(0L))
    }

    private fun sendCurrentPart() {
        if (sendAttempted) return
        sendAttempted = true
        val node = cachedInput
        if (node != null && node.isEditable) {
            val end = (base + currentText).length
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }
        clickSend()
    }

    private fun onPartComplete() {
        val sending = TyperState.autoSend || TyperState.splitMode
        if (!sending) {
            finish()
            return
        }
        handler.postDelayed({
            if (!typing) return@postDelayed
            sendCurrentPart()
            if (partIndex >= parts.lastIndex) {
                finish()
                return@postDelayed
            }
            handler.postDelayed({
                if (!typing) return@postDelayed
                if (fieldIsEmpty()) {
                    startNextPart()
                } else {
                    // сообщение не ушло: нажимаем отправку ещё раз
                    sendAttempted = false
                    sendCurrentPart()
                    handler.postDelayed({
                        if (!typing) return@postDelayed
                        if (fieldIsEmpty()) {
                            startNextPart()
                        } else {
                            Toast.makeText(this, "Сообщение не отправилось — остановлено", Toast.LENGTH_LONG).show()
                            disarm()
                        }
                    }, 900)
                }
            }, TyperState.pauseMs.coerceIn(0L, 60000L))
        }, 400)
    }

    private fun startNextPart() {
        partIndex++
        actionIndex = 0
        currentText = StringBuilder()
        base = ""
        lostTicks = 0
        sendAttempted = false
        tick()
    }

    private fun fieldIsEmpty(): Boolean {
        val n = currentInput() ?: return true
        val t = n.text?.toString().orEmpty()
        if (t.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= 26) {
            if (n.isShowingHintText) return true
            if (t == n.hintText?.toString()) return true
        }
        return false
    }

    private fun finish() {
        Toast.makeText(this, "Готово ✓", Toast.LENGTH_SHORT).show()
        disarm()
    }

    // ---------- отправка: поиск кнопки во всех окнах ----------

    private fun clickSend() {
        val roots = allRoots()
        val budget = intArrayOf(0)

        val keyword = ArrayList<Candidate>()
        roots.forEach { collectKeyword(it, 0, budget, keyword) }
        keyword.sortWith(
            compareByDescending<Candidate> { it.exact }
                .thenByDescending { it.right }
                .thenByDescending { it.bottom }
        )
        for (c in keyword) {
            if (clickNode(c.node)) return
        }

        val input = focusedInputAcross(roots)
        if (input != null) {
            val ir = Rect()
            input.getBoundsInScreen(ir)
            val right = ArrayList<Candidate>()
            budget[0] = 0
            roots.forEach { collectRight(it, ir, 0, budget, right) }
            right.sortedByDescending { it.right }.forEach { c ->
                if (clickNode(c.node)) return
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val target = input ?: cachedInput
            if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true) return
        }
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val list = try { windows } catch (_: Exception) { emptyList() }
        list.sortedByDescending { it.isActive }.forEach { w ->
            try {
                w.root?.let { out += it }
            } catch (_: Exception) {
            }
        }
        val active = rootInActiveWindow
        if (active != null && out.none { it.windowId == active.windowId }) out.add(0, active)
        return out
    }

    private fun focusedInputAcross(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        roots.asSequence()
            .mapNotNull { try { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null } }
            .firstOrNull { it.isEditable }

    private fun collectKeyword(node: AccessibilityNodeInfo?, depth: Int, budget: IntArray, out: MutableList<Candidate>) {
        if (node == null || depth > MAX_TREE_DEPTH || budget[0] > MAX_TREE_NODES) return
        budget[0]++
        if (!node.isEditable && node.isVisibleToUser) {
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val label = "$desc $id ${if (text.length <= 12) text else ""}".lowercase()
            if (SEND_WORDS.any { label.contains(it) } && BLACKLIST.none { label.contains(it) }) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val exact = EXACT_SEND.contains(desc.trim().lowercase()) || EXACT_SEND.contains(text.trim().lowercase())
                out += Candidate(node, exact, r.right, r.bottom, r.centerY())
            }
        }
        for (i in 0 until node.childCount) collectKeyword(node.getChild(i), depth + 1, budget, out)
    }

    // кликабельные элементы справа от поля на той же строке
    private fun collectRight(node: AccessibilityNodeInfo?, input: Rect, depth: Int, budget: IntArray, out: MutableList<Candidate>) {
        if (node == null || depth > MAX_TREE_DEPTH || budget[0] > MAX_TREE_NODES) return
        budget[0]++
        if (node.isClickable && !node.isEditable && node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val sameRow = abs(r.centerY() - input.centerY()) < input.height()
            if (sameRow && r.centerX() > input.centerX()) {
                out += Candidate(node, false, r.right, r.bottom, r.centerY())
            }
        }
        for (i in 0 until node.childCount) collectRight(node.getChild(i), input, depth + 1, budget, out)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ---------- красная кнопка СТОП ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val btn = Button(this).apply {
            text = "■ СТОП"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setOnClickListener { disarm() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 160
            x = 16
        }
        wm.addView(btn, lp)
        overlay = btn
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
