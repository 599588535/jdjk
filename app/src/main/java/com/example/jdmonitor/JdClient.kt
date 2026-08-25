package com.example.jdmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object JdClient {

    // 抓取失败时记录诊断信息，供 MonitorWorker 写进日志
    var lastError: String? = null
        private set

    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // 价格合理性门槛
    private const val MIN_SELECTOR_PRICE = 1.0    // 选择器命中的价格（高可信）
    private const val MIN_FULLTEXT_PRICE = 10.0   // 全文匹配的价格（低可信，要求更高，防券面额误报）

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否为账号价
    // 唯一通道：WebView 真实渲染（京东已关停所有免签名价格接口：
    // p.3.cn 公网解析全为内网IP、api.m.jd.com 需 h5st 签名、skudata 频控，均不可用）
    fun getPrice(ctx: Context, sku: String, cookie: String): Pair<Double?, Boolean> {
        lastError = null
        val price = fetchPriceViaWebView(ctx, sku, cookie)
        return Pair(price, cookie.isNotEmpty())
    }

    // ========== WebView 真实渲染取价 ==========
    // 在内存中创建 WebView 加载移动商品页，页面自身 JS 会完成签名请求并渲染价格；
    // 轮询注入 JS 读价：选择器命中（高可信）一次即用；全文匹配（低可信）需 >=10元 且连续3次一致。
    private fun fetchPriceViaWebView(ctx: Context, sku: String, cookie: String, timeoutMs: Long = 25000): Double? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Double?>()
        val diag = AtomicReference("")
        val webViewRef = AtomicReference<WebView?>()
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                val wv = WebView(ctx.applicationContext)
                webViewRef.set(wv)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = UA_MOBILE
                // 空的 WebViewClient：防止页面重定向跳到外部浏览器
                wv.webViewClient = WebViewClient()

                // 注入登录 cookie（如有）。Domain=.jd.com 让页面内 XHR 子域请求也能带上登录态。
                if (cookie.isNotEmpty()) {
                    try {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cookie.split(";").forEach { c ->
                            val t = c.trim()
                            if (t.contains("=") && !t.startsWith("Domain", true) && !t.startsWith("Path", true)) {
                                try { cm.setCookie("https://item.m.jd.com", "$t; Domain=.jd.com; Path=/") } catch (_: Exception) {}
                            }
                        }
                        cm.flush()
                    } catch (_: Exception) {}
                }

                val startTime = System.currentTimeMillis()
                var firstPollTime = 0L
                var finished = false
                var lastAp = -1.0
                var stableCount = 0

                fun finish(price: Double?) {
                    if (finished) return
                    finished = true
                    result.set(price)
                    try { webViewRef.get()?.destroy() } catch (_: Exception) {}
                    webViewRef.set(null)
                    latch.countDown()
                }

                fun poll() {
                    if (finished) return
                    if (firstPollTime == 0L) firstPollTime = System.currentTimeMillis()
                    if (System.currentTimeMillis() - startTime > timeoutMs - 2500) {
                        finish(null)
                        return
                    }
                    val wv = webViewRef.get() ?: run { finish(null); return }
                    wv.evaluateJavascript(JS_EXTRACT_PRICE) { raw ->
                        if (finished) return@evaluateJavascript
                        try {
                            val decoded = decodeJsString(raw)
                            if (decoded != null) {
                                val o = JSONObject(decoded)
                                val t = o.optString("t", "")
                                val s = o.optString("s", "")
                                if (t.isNotEmpty() || s.isNotEmpty()) diag.set("标题=$t 片段=$s")

                                // 1) 选择器命中：高可信，一次即用
                                val sp = o.optString("sp", "").toDoubleOrNull()
                                if (sp != null && sp > MIN_SELECTOR_PRICE && sp < 1000000) {
                                    finish(sp)
                                    return@evaluateJavascript
                                }
                                // 2) 全文最大价：低可信，需 >=10元 且连续3次一致 且已过4秒（等价格XHR渲染稳定）
                                val ap = o.optString("ap", "").toDoubleOrNull()
                                if (ap != null && ap >= MIN_FULLTEXT_PRICE && ap < 1000000) {
                                    if (ap == lastAp) stableCount++ else { stableCount = 1; lastAp = ap }
                                    val elapsed = System.currentTimeMillis() - firstPollTime
                                    if (stableCount >= 3 && elapsed >= 4000) {
                                        finish(ap)
                                        return@evaluateJavascript
                                    }
                                } else {
                                    stableCount = 0
                                    lastAp = -1.0
                                }
                            }
                        } catch (_: Exception) {}
                        main.postDelayed({ poll() }, 800)
                    }
                }

                wv.loadUrl("https://item.m.jd.com/product/$sku.html")
                // 不依赖 onPageFinished（重定向链中回调时机不可靠），2.5 秒后直接开始轮询
                main.postDelayed({ poll() }, 2500)
            } catch (e: Exception) {
                diag.set("WebView 初始化异常：${e.message}")
                latch.countDown()
            }
        }

        latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS)
        // 兜底清理：若 await 超时但 WebView 还在（极端情况），主线程销毁
        main.post {
            try { webViewRef.get()?.destroy() } catch (_: Exception) {}
            webViewRef.set(null)
        }

        val price = result.get()
        if (price == null) {
            val d = diag.get()
            lastError = if (d.isNotEmpty()) "WebView 未取到价格（$d）" else "WebView 渲染取价失败/超时"
        }
        return price
    }

    // evaluateJavascript 的返回值是 JSON 编码的字符串字面量（带外层引号和转义），先解码一层
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            JSONArray("[$raw]").getString(0)
        } catch (e: Exception) {
            raw.trim('"')
        }
    }

    // 页面内价格提取（已经过 jsdom 场景测试：骨架屏/选择器/全文/纯券/区间价/含¥ 全通过）
    // 返回 JSON：sp=选择器价, ap=全文最大¥价, t=页面标题, s=正文片段（诊断用）
    // 注意：必须用 clone+textContent（后台 WebView 不排版，innerText 恒为空）
    private val JS_EXTRACT_PRICE = """
        (function(){
          try {
            var sp = '';
            var sels = ['.price-num', '.price .num', '.detail-price', '#price', '[class*="price"] [class*="num"]',
                        '.price', '.big-price', '.current-price', '.jd-price', '.sale-price'];
            for (var i = 0; i < sels.length; i++) {
              var el = document.querySelector(sels[i]);
              if (el) {
                var mm = (el.textContent || '').match(/[0-9]+(\.[0-9]+)?/);
                if (mm && parseFloat(mm[0]) > 1) { sp = mm[0]; break; }
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
            var ap = '';
            var m = txt.match(/¥\s*[0-9]+(\.[0-9]+)?/g) || [];
            var nums = m.map(function(s){ return parseFloat(s.replace(/[^0-9.]/g, '')); })
                        .filter(function(n){ return n > 1 && n < 1000000; });
            if (nums.length) {
              nums.sort(function(a, b){ return b - a; });
              ap = String(nums[0]);
            }
            return JSON.stringify({
              sp: sp, ap: ap,
              t: document.title || '',
              s: txt.substring(0, 160)
            });
          } catch (e) {
            return JSON.stringify({sp: '', ap: '', t: 'JS异常:' + e.message, s: ''});
          }
        })()
    """.trimIndent()
}
