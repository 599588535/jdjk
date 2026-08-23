package com.example.jdmonitor

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object PushHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 自动识别：SCT 开头 → Server酱；其它 → PushPlus。返回是否成功
    fun push(ctx: Context, key: String, title: String, content: String): Boolean {
        if (key.isEmpty()) return true
        return if (key.startsWith("SCT")) pushServerChan(key, title, content)
        else pushPlus(key, title, content)
    }

    private fun pushServerChan(key: String, title: String, content: String): Boolean {
        try {
            val url = "https://sctapi.ftqq.com/$key.send?title=" +
                URLEncoder.encode(title, "UTF-8") + "&desp=" + URLEncoder.encode(content, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", "JDMonitor").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return false
            val o = JSONObject(body)
            return o.optInt("code", -1) == 0
        } catch (e: Exception) {
            Prefs.appendLog(ctx, "微信(Server酱)推送异常：${e.message}")
            return false
        }
    }

    private fun pushPlus(token: String, title: String, content: String): Boolean {
        try {
            val url = "https://www.pushplus.plus/send?token=" +
                URLEncoder.encode(token, "UTF-8") + "&title=" +
                URLEncoder.encode(title, "UTF-8") + "&content=" +
                URLEncoder.encode(content, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", "JDMonitor").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return false
            val o = JSONObject(body)
            return o.optInt("code", -1) == 200
        } catch (e: Exception) {
            Prefs.appendLog(ctx, "微信(PushPlus)推送异常：${e.message}")
            return false
        }
    }
}
