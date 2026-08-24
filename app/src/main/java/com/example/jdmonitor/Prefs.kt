package com.example.jdmonitor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {

    private const val NAME = "jd_monitor_prefs"
    private const val KEY_PRODUCTS = "products"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_START = "start_h"
    private const val KEY_END = "end_h"
    private const val KEY_WX = "wx_key"
    private const val KEY_COOKIE = "jd_cookie"
    private const val KEY_LOG = "run_log"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun loadProducts(ctx: Context): MutableList<Product> {
        val list = ArrayList<Product>()
        val raw = sp(ctx).getString(KEY_PRODUCTS, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                try { list.add(Product.fromJson(arr.getJSONObject(i))) } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // 本地数据损坏时不崩，按空列表处理（用户可重新添加商品）
        }
        return list
    }

    fun saveProducts(ctx: Context, list: List<Product>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY_PRODUCTS, arr.toString()).apply()
    }

    fun getInterval(ctx: Context): Double =
        (sp(ctx).getString(KEY_INTERVAL, "6") ?: "6").toDoubleOrNull() ?: 6.0

    fun setInterval(ctx: Context, v: Double) =
        sp(ctx).edit().putString(KEY_INTERVAL, v.toString()).apply()

    fun getStart(ctx: Context): Int =
        (sp(ctx).getString(KEY_START, "9") ?: "9").toIntOrNull() ?: 9

    fun setStart(ctx: Context, v: Int) =
        sp(ctx).edit().putString(KEY_START, v.toString()).apply()

    fun getEnd(ctx: Context): Int =
        (sp(ctx).getString(KEY_END, "23") ?: "23").toIntOrNull() ?: 23

    fun setEnd(ctx: Context, v: Int) =
        sp(ctx).edit().putString(KEY_END, v.toString()).apply()

    fun getWxKey(ctx: Context): String = sp(ctx).getString(KEY_WX, "") ?: ""

    fun setWxKey(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_WX, v).apply()

    fun getCookie(ctx: Context): String = sp(ctx).getString(KEY_COOKIE, "") ?: ""

    fun setCookie(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_COOKIE, v).apply()

    fun appendLog(ctx: Context, line: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        var cur = sp(ctx).getString(KEY_LOG, "") ?: ""
        cur = cur + "[" + stamp + "] " + line + "\n"
        val lines = cur.lines()
        val keep = if (lines.size > 200) lines.takeLast(200).joinToString("\n") else cur
        sp(ctx).edit().putString(KEY_LOG, keep).apply()
    }

    fun getLog(ctx: Context): String = sp(ctx).getString(KEY_LOG, "") ?: ""

    fun clearLog(ctx: Context) = sp(ctx).edit().putString(KEY_LOG, "").apply()
}
