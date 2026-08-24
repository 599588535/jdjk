package com.example.jdmonitor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object JdClient {

    // 抓取失败时记录真实原因（多接口原因会累积），供 MonitorWorker 写进日志
    var lastError: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
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
        // 账号专属价优先（登录后京东可能给出不同价）
        if (cookie.isNotEmpty()) {
            val acc = getAccountPrice(sku, cookie)
            if (acc != null) return Pair(acc, true)
        }
        // 公开价：多级兜底
        val pub = getPublicPrice(sku)     // 主接口 p.3.cn（重试）
        if (pub != null) return Pair(pub, false)
        val mp = getMobilePagePrice(sku)  // 移动商品页（SSR，价格直接写在 HTML）
        if (mp != null) return Pair(mp, false)
        val pp = getPcPagePrice(sku)      // PC 商品页（最后兜底）
        if (pp != null) return Pair(pp, false)
        return Pair(null, false)
    }

    // 在主接口/商品页都失败时，把原因累积记录下来
    private fun appendErr(msg: String) {
        lastError = if (lastError.isNullOrBlank()) msg else "$lastError\n$msg"
    }

    // 主接口：p.3.cn 公开挂牌价（最稳定，加完整请求头 + 重试1次）
    private fun getPublicPrice(sku: String): Double? {
        var lastMsg: String? = null
        for (attempt in 1..2) {
            try {
                val url = "https://p.3.cn/prices/mgets?skuIds=J_$sku"
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA_DESKTOP)
                    .header("Referer", "https://item.jd.com/$sku.html")
                    .header("Accept", "*/*")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    lastMsg = "p.3.cn 返回空（第$attempt 次）"
                    continue
                }
                parseMgets(body)?.let { return it }
                lastMsg = "p.3.cn 无有效价格字段，返回=$body"
            } catch (e: Exception) {
                lastMsg = "p.3.cn 异常（第$attempt 次）：${e.message}"
            }
        }
        appendErr(lastMsg ?: "p.3.cn 失败")
        return null
    }

    // 解析 p.3.cn 返回的 JSON 数组，优先 p，其次 op/m/tpp
    private fun parseMgets(body: String): Double? {
        return try {
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                for (key in listOf("p", "op", "m", "tpp")) {
                    val v = o.optString(key, "").toDoubleOrNull()
                    if (v != null && v > 0) return v
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // 移动商品页：服务端渲染，价格直接出现在 HTML（比 PC 页可靠）
    private fun getMobilePagePrice(sku: String): Double? {
        return try {
            val url = "https://item.m.jd.com/product/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://search.m.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (body.isNullOrEmpty()) {
                appendErr("移动商品页返回空")
                return null
            }
            extractPriceFromHtml(body)?.let { return it }
            // 解析不到时，把含 price 的 HTML 片段记录下来，便于下次精准修复
            appendErr("移动商品页未解析到价格，片段=${snippet(body, "price")}")
            null
        } catch (e: Exception) {
            appendErr("移动商品页异常：${e.message}")
            null
        }
    }

    // PC 商品页兜底：价格多为 JS 异步加载，成功率低，仅作最后兜底
    private fun getPcPagePrice(sku: String): Double? {
        return try {
            val url = "https://item.jd.com/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", "https://search.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (body.isNullOrEmpty()) {
                appendErr("PC商品页返回空")
                return null
            }
            extractPriceFromHtml(body)?.let { return it }
            appendErr("PC商品页未解析到价格，片段=${snippet(body, "price")}")
            null
        } catch (e: Exception) {
            appendErr("PC商品页异常：${e.message}")
            null
        }
    }

    // 从 HTML 中提取价格：按可信度从高到低尝试
    private fun extractPriceFromHtml(html: String): Double? {
        val patterns = listOf(
            """data-price=["']?([0-9]+\.?[0-9]*)["']?""",                          // data-price="99.00"
            """class=["'][^"']*price[^"']*["'][\s\S]*?>¥?\s*([0-9]+\.?[0-9]*)""",  // <span class="price">¥99.00 / 99.00
            """¥\s*([0-9]+(?:\.[0-9]{1,2})?)""",                                    // ¥99.00
            """["']price["']\s*:\s*["']?([0-9]+\.?[0-9]*)""",                       // "price":"99.00"
            """["']p["']\s*:\s*["']?([0-9]+\.?[0-9]*)"""                            // "p":"99.00"
        )
        for (p in patterns) {
            val m = Regex(p, RegexOption.IGNORE_CASE).find(html)
            if (m != null) {
                val v = m.groupValues[1].toDoubleOrNull()
                // 过滤明显异常值（京东绝大部分商品价在 0.01 ~ 100万 之间）
                if (v != null && v > 0.01 && v < 1000000) return v
            }
        }
        return null
    }

    // 取 HTML 中 keyword 附近的片段，便于排查解析失败（只看公开商品页，不含用户隐私）
    private fun snippet(html: String, keyword: String, radius: Int = 120): String {
        val idx = html.indexOf(keyword, ignoreCase = true)
        return if (idx >= 0) {
            val s = (idx - radius).coerceAtLeast(0)
            val e = (idx + radius).coerceAtMost(html.length)
            html.substring(s, e).replace("\n", " ").replace("\r", " ")
        } else {
            html.take(300).replace("\n", " ").replace("\r", " ")
        }
    }

    // 账号专属价（best-effort）：带登录cookie请求移动版商品页
    private fun getAccountPrice(sku: String, cookie: String): Double? {
        return try {
            val url = "https://item.m.jd.com/product/$sku.html"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Cookie", cookie)
                .header("Referer", "https://cart.m.jd.com/")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            extractPriceFromHtml(body)
        } catch (e: Exception) {
            null
        }
    }
}
