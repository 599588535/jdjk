package com.example.jdmonitor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object JdClient {

    // 抓取失败时记录真实原因，供 MonitorWorker 写进日志
    var lastError: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否为账号专属价
    fun getPrice(sku: String, cookie: String): Pair<Double?, Boolean> {
        lastError = null
        if (cookie.isNotEmpty()) {
            val acc = getAccountPrice(sku, cookie)
            if (acc != null) return Pair(acc, true)
        }
        val pub = getPublicPrice(sku)
        if (pub != null) return Pair(pub, false)
        // 兜底：从商品详情页解析
        val page = getPagePrice(sku)
        if (page != null) return Pair(page, false)
        return Pair(null, false)
    }

    // 公开挂牌价（极稳定接口）
    private fun getPublicPrice(sku: String): Double? {
        return try {
            val url = "https://p.3.cn/prices/mgets?skuIds=J_$sku"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://item.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null.also { lastError = "p.3.cn 返回空" }
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                val p = o.optString("p", "")
                val price = p.toDoubleOrNull()
                if (price != null && price > 0) return price
                lastError = "p.3.cn 无有效价格字段，返回=$body"
            } else {
                lastError = "p.3.cn 返回空数组"
            }
            null
        } catch (e: Exception) {
            lastError = "p.3.cn 异常：${e.message}"
            null
        }
    }

    // 兜底：从商品详情页 HTML 中解析价格
    private fun getPagePrice(sku: String): Double? {
        return try {
            val url = "https://item.jd.com/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_DESKTOP)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null.also { lastError = "商品页返回空" }
            val regexes = listOf(
                """["']p["']\s*:\s*["']?([0-9]+\.?[0-9]*)["']?""",
                """price["']?\s*[:=]\s*["']?([0-9]+\.?[0-9]*)""",
                """¥\s*([0-9]+(?:\.[0-9]{1,2})?)"""
            )
            for (r in regexes) {
                val m = Regex(r).find(body)
                if (m != null) {
                    val price = m.groupValues[1].toDoubleOrNull()
                    if (price != null && price > 0) return price
                }
            }
            lastError = "商品页未解析到价格"
            null
        } catch (e: Exception) {
            lastError = "商品页异常：${e.message}"
            null
        }
    }

    // 账号专属价（best-effort）：带登录cookie请求移动版商品页，提取页面价格
    private fun getAccountPrice(sku: String, cookie: String): Double? {
        return try {
            val url = "https://item.m.jd.com/product/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_MOBILE)
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
            null
        } catch (e: Exception) {
            null
        }
    }
}
