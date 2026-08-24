package com.example.jdmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object JdClient {

    // 抓取失败时记录真实原因（多接口原因会累积），供 MonitorWorker 写进日志
    var lastError: String? = null
        private set

    // ========== DoH（DNS-over-HTTPS）兜底：系统 DNS 被劫持/屏蔽时走阿里 DoH 解析真实 IP ==========
    // 专门用于解析 DoH 服务器自身域名的客户端（不能用 DohDns，否则递归）
    private val dohLookupClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private object DohDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1) 先试系统 DNS，正常就直接用
            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: Exception) {
                // 系统 DNS 失败（如 p.3.cn 被劫持成 NXDOMAIN/内网地址），继续走 DoH
            }
            // 2) 阿里 DoH 兜底解析
            try {
                val req = Request.Builder()
                    .url("https://dns.alidns.com/resolve?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                val resp = dohLookupClient.newCall(req).execute()
                val body = resp.body?.string()
                if (!body.isNullOrEmpty()) {
                    val answers = JSONObject(body).optJSONArray("Answer")
                    val list = ArrayList<InetAddress>()
                    if (answers != null) {
                        for (i in 0 until answers.length()) {
                            val ip = answers.optJSONObject(i)?.optString("data") ?: ""
                            if (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
                                // 过滤内网/保留地址，避免劫持到 10.x / 127.x
                                if (!ip.startsWith("10.") && !ip.startsWith("127.") &&
                                    !ip.startsWith("192.168.") && !ip.startsWith("0.")
                                ) {
                                    list.add(InetAddress.getByName(ip))
                                }
                            }
                        }
                    }
                    if (list.isNotEmpty()) return list
                }
            } catch (_: Exception) {
                // DoH 也失败则落到下面抛异常
            }
            throw UnknownHostException("无法解析 $hostname（系统DNS与DoH均失败）")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(DohDns)
        .build()

    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // 价格合理性门槛：低于此值视为占位/误抓
    private const val MIN_SANE_PRICE = 1.0

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否为账号专属价
    // 策略：先走轻量接口（快）；全部失败再走 WebView 真实渲染（慢但最可靠）
    fun getPrice(ctx: Context, sku: String, cookie: String): Pair<Double?, Boolean> {
        lastError = null

        // 1) 公开价：p.3.cn 标准价格接口（DoH 加持）
        val pub = fetchPublicPrice(sku, cookie = "")
        if (pub != null) return Pair(pub, false)

        // 2) 公开价：api.m.jd.com 官方 APP 接口
        val apiP = fetchApiPrice(sku, cookie = "")
        if (apiP != null) return Pair(apiP, false)

        // 3) 公开价：skudata.json
        val skuData = fetchSkuDataPrice(sku, cookie = "")
        if (skuData != null) return Pair(skuData, false)

        // 4) 终极兜底：WebView 真实加载商品页，JS 读取渲染后的价格
        //    带 cookie 时即为账号价（页面按登录态展示）
        val wv = fetchPriceViaWebView(ctx, sku, cookie)
        if (wv != null) return Pair(wv, cookie.isNotEmpty())

        return Pair(null, false)
    }

    private fun appendErr(msg: String) {
        lastError = if (lastError.isNullOrBlank()) msg else "$lastError\n$msg"
    }

    // ========== 1) p.3.cn 标准价格接口 ==========
    private fun fetchPublicPrice(sku: String, cookie: String): Double? {
        var lastMsg: String? = null
        val urls = listOf(
            "https://p.3.cn/prices/mgets?skuIds=J_$sku",
            "https://pm.3.cn/prices/mgets?skuIds=J_$sku"
        )
        for (url in urls) {
            try {
                val rb = Request.Builder().url(url)
                    .header("User-Agent", UA_DESKTOP)
                    .header("Referer", "https://item.jd.com/$sku.html")
                    .header("Accept", "*/*")
                if (cookie.isNotEmpty()) rb.header("Cookie", cookie)
                val resp = client.newCall(rb.build()).execute()
                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    lastMsg = "p.3.cn 返回空 HTTP${resp.code}"
                    continue
                }
                parseMgets(body)?.let { return it }
                lastMsg = "p.3.cn 无有效价格 HTTP${resp.code} 返回=${body.take(150)}"
            } catch (e: Exception) {
                lastMsg = "p.3.cn 异常：${e.message}"
            }
        }
        appendErr(lastMsg ?: "p.3.cn 全部失败")
        return null
    }

    private fun parseMgets(body: String): Double? {
        val clean = body.trim()
            .removePrefix("jQuery(")
            .removeSuffix(")")
            .removePrefix("jQuery")
        return try {
            val arr = JSONArray(clean)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                for (key in listOf("p", "op", "m", "tpp")) {
                    val v = o.optString(key, "").toDoubleOrNull()
                    if (v != null && v > MIN_SANE_PRICE) return v
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ========== 2) api.m.jd.com 官方 APP 接口 ==========
    private fun fetchApiPrice(sku: String, cookie: String): Double? {
        return try {
            val bodyJson =
                """{"skuId":"$sku","cat":"","area":"19_1601_3633_0","shopId":"","venderId":"","paramJson":"{\"platform2\":\"1\",\"specialAttr\":\"\",\"skuMark\":\"\"}","num":"1"}"""
            val url = "https://api.m.jd.com/client.action" +
                "?functionId=pc_detailpage_wareBusiness" +
                "&client=pc&clientVersion=1.0.0" +
                "&t=${System.currentTimeMillis()}" +
                "&body=${URLEncoder.encode(bodyJson, "UTF-8")}"
            val rb = Request.Builder().url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", "https://item.jd.com/$sku.html")
            if (cookie.isNotEmpty()) rb.header("Cookie", cookie)
            val resp = client.newCall(rb.build()).execute()
            val body = resp.body?.string()
            if (body.isNullOrEmpty() || !body.trimStart().startsWith("{")) {
                appendErr("api.m.jd.com 非JSON HTTP${resp.code} ${body?.take(120) ?: "空"}")
                return null
            }
            val price = findPriceInJson(JSONObject(body), 0)
            if (price != null) return price
            appendErr("api.m.jd.com 未解析到价格 ${body.take(150)}")
            null
        } catch (e: Exception) {
            appendErr("api.m.jd.com 异常：${e.message}")
            null
        }
    }

    // 递归在 JSON 里找价格字段（字段名白名单，避免误抓数量/评分等数字）
    private fun findPriceInJson(node: Any?, depth: Int): Double? {
        if (depth > 6 || node == null) return null
        val priceKeys = setOf("p", "price", "jdPrice", "wprice", "salePrice", "currentPrice", "promotionPrice")
        if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (priceKeys.contains(k)) {
                    val v = node.optString(k, "").toDoubleOrNull()
                    if (v != null && v > MIN_SANE_PRICE && v < 1000000) return v
                }
            }
            val keys2 = node.keys()
            while (keys2.hasNext()) {
                val child = node.opt(keys2.next())
                val found = findPriceInJson(child, depth + 1)
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findPriceInJson(node.opt(i), depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    // ========== 3) skudata.json 结构化接口 ==========
    private fun fetchSkuDataPrice(sku: String, cookie: String): Double? {
        return try {
            val rb = Request.Builder()
                .url("https://item.jd.com/skudata.json?skuId=$sku")
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", "https://item.jd.com/$sku.html")
            if (cookie.isNotEmpty()) rb.header("Cookie", cookie)
            val resp = client.newCall(rb.build()).execute()
            val body = resp.body?.string()
            if (body.isNullOrEmpty() || !body.trimStart().startsWith("{")) {
                appendErr("skudata 非JSON HTTP${resp.code} ${body?.take(120) ?: "空"}")
                return null
            }
            val price = findPriceInJson(JSONObject(body), 0)
            if (price != null) return price
            appendErr("skudata 未解析到价格 ${body.take(150)}")
            null
        } catch (e: Exception) {
            appendErr("skudata 异常：${e.message}")
            null
        }
    }

    // ========== 4) WebView 真实渲染兜底（最可靠） ==========
    // 在内存中创建 WebView 加载移动商品页，JS 完整执行后价格必然渲染出来。
    // 注意：后台 WebView 不做排版，innerText 恒为空，必须用 textContent（clone 后剔除 script/style）。
    private fun fetchPriceViaWebView(ctx: Context, sku: String, cookie: String, timeoutMs: Long = 20000): Double? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Double?>()
        val diag = AtomicReference("")   // 诊断信息：页面标题+片段
        val main = Handler(Looper.getMainLooper())
        try {
            main.post {
                var webView: WebView? = null
                try {
                    webView = WebView(ctx.applicationContext)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = UA_MOBILE

                    // 注入登录 cookie（如有），页面即按账号价展示
                    if (cookie.isNotEmpty()) {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cookie.split(";").forEach { c ->
                            val t = c.trim()
                            if (t.contains("=")) {
                                try { cm.setCookie("https://item.m.jd.com", t) } catch (_: Exception) {}
                            }
                        }
                        try { cm.flush() } catch (_: Exception) {}
                    }

                    val startTime = System.currentTimeMillis()
                    var finished = false

                    fun poll() {
                        if (finished) return
                        if (System.currentTimeMillis() - startTime > timeoutMs - 2500) {
                            finished = true
                            try { webView?.destroy() } catch (_: Exception) {}
                            latch.countDown()
                            return
                        }
                        webView?.evaluateJavascript(JS_EXTRACT_PRICE) { raw ->
                            val decoded = decodeJsString(raw)
                            if (decoded != null) {
                                try {
                                    val o = JSONObject(decoded)
                                    val p = o.optString("p", "").toDoubleOrNull()
                                    val t = o.optString("t", "")
                                    val s = o.optString("s", "")
                                    if (t.isNotEmpty() || s.isNotEmpty()) diag.set("标题=$t 片段=$s")
                                    if (p != null && p > MIN_SANE_PRICE && p < 1000000) {
                                        finished = true
                                        result.set(p)
                                        try { webView?.destroy() } catch (_: Exception) {}
                                        latch.countDown()
                                        return@evaluateJavascript
                                    }
                                } catch (_: Exception) {}
                            }
                            main.postDelayed({ poll() }, 800)
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // 页面骨架完成后延迟 1.5 秒等价格异步渲染，再开始轮询
                            main.postDelayed({ poll() }, 1500)
                        }
                    }
                    webView.loadUrl("https://item.m.jd.com/product/$sku.html")
                } catch (e: Exception) {
                    appendErr("WebView 初始化异常：${e.message}")
                    try { webView?.destroy() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
        } catch (e: Exception) {
            appendErr("WebView 调度异常：${e.message}")
            latch.countDown()
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val price = result.get()
        if (price == null) {
            val d = diag.get()
            appendErr(if (d.isNotEmpty()) "WebView 未取到价格（$d）" else "WebView 渲染取价失败/超时")
        }
        return price
    }

    // evaluateJavascript 的返回值是 JSON 编码的字符串字面量（带引号和转义），先解码一层
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            // 包成 JSON 数组解析，利用 org.json 完整处理转义
            JSONArray("[$raw]").getString(0)
        } catch (e: Exception) {
            raw.trim('"')
        }
    }

    // 在渲染完成的页面里提取价格，并回传诊断信息（JSON: {p:价格, t:标题, s:正文片段}）
    // 关键：用 clone + textContent，规避后台 WebView 不排版导致 innerText 为空的问题
    private val JS_EXTRACT_PRICE = """
        (function(){
          try {
            var price = '';
            var sels = ['.price-num', '.price .num', '.detail-price', '#price', '[class*="price"] [class*="num"]'];
            for (var i = 0; i < sels.length; i++) {
              var el = document.querySelector(sels[i]);
              if (el) {
                var t = (el.textContent || '').replace(/[^0-9.]/g, '');
                if (t && parseFloat(t) > 1) { price = t; break; }
              }
            }
            var txt = '';
            if (document.body) {
              var c = document.body.cloneNode(true);
              var junk = c.querySelectorAll('script,style,noscript');
              for (var j = 0; j < junk.length; j++) {
                if (junk[j].parentNode) junk[j].parentNode.removeChild(junk[j]);
              }
              txt = (c.textContent || '').replace(/\s+/g, ' ');
            }
            if (!price) {
              var m = txt.match(/¥\s*[0-9]+(\.[0-9]+)?/g) || [];
              var nums = m.map(function(s){ return parseFloat(s.replace(/[^0-9.]/g, '')); })
                          .filter(function(n){ return n > 1 && n < 1000000; });
              if (nums.length) {
                nums.sort(function(a, b){ return b - a; });
                price = String(nums[0]);
              }
            }
            return JSON.stringify({
              p: price,
              t: document.title || '',
              s: txt.substring(0, 160)
            });
          } catch (e) {
            return JSON.stringify({p: '', t: 'JS异常:' + e.message, s: ''});
          }
        })()
    """.trimIndent()
}
