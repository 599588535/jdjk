package com.example.jdmonitor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object JdClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否为账号专属价
    fun getPrice(sku: String, cookie: String): Pair<Double?, Boolean> {
        if (cookie.isNotEmpty()) {
            val acc = getAccountPrice(sku, cookie)
            if (acc != null) return Pair(acc, true)
        }
        return Pair(getPublicPrice(sku), false)
    }

    // 公开挂牌价（极稳定接口）
    private fun getPublicPrice(sku: String): Double? {
        try {
            val url = "https://p.3.cn/prices/mgets?skuIds=J_$sku"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://item.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                val p = o.optString("p", "")
                val price = p.toDoubleOrNull()
                if (price != null && price > 0) return price
            }
        } catch (e: Exception) {
        }
        return null
    }

    // 账号专属价（best-effort）：带登录cookie请求移动版商品页，提取页面价格
    private fun getAccountPrice(sku: String, cookie: String): Double? {
        try {
            val url = "https://item.m.jd.com/product/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cookie", cookie)
                .header("Referer", "https://mall.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val m = Regex("""¥\s*([0-9]+(?:\.[0-9]{1,2})?)""").find(body)
            if (m != null) {
                val price = m.groupValues[1].toDoubleOrNull()
                if (price != null && price > 0) return price
            }
        } catch (e: Exception) {
        }
        return null
    }
}
