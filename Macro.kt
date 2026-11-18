package com.autotyper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// type: tap, swipe, wait, back, home, text. Координаты в долях экрана (0..1).
data class Step(
    val type: String,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val ms: Long = 0L,
    val text: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("x1", x1.toDouble())
        put("y1", y1.toDouble())
        put("x2", x2.toDouble())
        put("y2", y2.toDouble())
        put("ms", ms)
        put("text", text)
    }

    companion object {
        fun fromJson(o: JSONObject) = Step(
            type = o.getString("type"),
            x1 = o.optDouble("x1").toFloat(),
            y1 = o.optDouble("y1").toFloat(),
            x2 = o.optDouble("x2").toFloat(),
            y2 = o.optDouble("y2").toFloat(),
            ms = o.optLong("ms"),
            text = o.optString("text")
        )
    }
}

object MacroStore {
    private const val PREFS = "macros"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun names(ctx: Context): List<String> = prefs(ctx).all.keys.sorted()

    fun load(ctx: Context, name: String): List<Step>? {
        val raw = prefs(ctx).getString(name, null) ?: return null
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Step.fromJson(arr.getJSONObject(it)) }
    }

    fun save(ctx: Context, name: String, steps: List<Step>) {
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(name, arr.toString()).apply()
    }

    fun delete(ctx: Context, name: String) {
        prefs(ctx).edit().remove(name).apply()
    }
}
