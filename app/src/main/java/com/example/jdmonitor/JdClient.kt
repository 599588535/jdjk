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

    // 价格下限：低于 10 元一律视为分期数/券面额/占位，绝不采用（防误报第一原则）
    private const val MIN_PRICE = 10.0

    // 全局复用的 WebView（避免小米等 ROM 上频繁创建/销毁导致异常）
    @Volatile
    private var sharedWebView: WebView? = null

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否带登录态抓取
    // 唯一通道：WebView 真实渲染（京东已关停所有免签名价格接口，均不可用）
    fun getPrice(ctx: Context, sku: String, cookie: String): Pair<Double?, Boolean> {
        lastError = null
        val price = fetchPriceViaWebView(ctx, sku, cookie)
        return Pair(price, cookie.isNotEmpty())
    }

    // ========== WebView 真实渲染取价 ==========
    // 决策规则（防误报优先）：
    //   sp = 高可信选择器价格，需 >=10 且连续 2 次一致；
    //   fp = 正文中第一个 >=10 的 ¥数字（主价区在页面上方，推荐位高价在下方不会误取），需连续 3 次一致且已过 4 秒；
    //   低于 10 元一律拒绝；25 秒超时返回 null 并带页面标题/片段诊断。
    private fun fetchPriceViaWebView(ctx: Context, sku: String, cookie: String, timeoutMs: Long = 25000): Double? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Double?>()
        val diag = AtomicReference("")
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                // 复用全局 WebView；没有才创建
                var wv = sharedWebView
                if (wv == null) {
                    wv = WebView(ctx.applicationContext)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.userAgentString = UA_MOBILE
                    // 空的 WebViewClient：防止页面重定向跳到外部浏览器
                    wv.webViewClient = WebViewClient()
                    sharedWebView = wv
                }

                // 每次抓取前注入最新登录 cookie（如有）。Domain=.jd.com 让页面内 XHR 子域请求也带登录态。
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
                var lastCand = -1.0
                var stableCount = 0

                fun finish(price: Double?) {
                    if (finished) return
                    finished = true
                    result.set(price)
                    latch.countDown()   // 不 destroy，WebView 留给下一个商品复用
                }

                fun poll() {
                    if (finished) return
                    if (firstPollTime == 0L) firstPollTime = System.currentTimeMillis()
                    if (System.currentTimeMillis() - startTime > timeoutMs - 2500) {
                        finish(null)
                        return
                    }
                    val cur = sharedWebView ?: run { finish(null); return }
                    cur.evaluateJavascript(JS_EXTRACT_PRICE) { raw ->
                        if (finished) return@evaluateJavascript
                        try {
                            val decoded = decodeJsString(raw)
                            if (decoded != null) {
                                val o = JSONObject(decoded)
                                val t = o.optString("t", "")
                                val s = o.optString("s", "")
                                if (t.isNotEmpty() || s.isNotEmpty()) diag.set("标题=$t 片段=$s")

                                val sp = o.optString("sp", "").toDoubleOrNull()
                                val fp = o.optString("fp", "").toDoubleOrNull()
                                // 候选价：选择器优先，其次第一个>=10的¥数字
                                val cand: Double?
                                val needStable: Int
                                val needElapsedMs: Long
                                if (sp != null && sp >= MIN_PRICE && sp < 1000000) {
                                    cand = sp; needStable = 2; needElapsedMs = 0
                                } else if (fp != null && fp >= MIN_PRICE && fp < 1000000) {
                                    cand = fp; needStable = 3; needElapsedMs = 4000
                                } else {
                                    cand = null; needStable = 0; needElapsedMs = 0
                                }

                                if (cand != null) {
                                    if (cand == lastCand) stableCount++ else { stableCount = 1; lastCand = cand }
                                    val elapsed = System.currentTimeMillis() - firstPollTime
                                    if (stableCount >= needStable && elapsed >= needElapsedMs) {
                                        finish(cand)
                                        return@evaluateJavascript
                                    }
                                } else {
                                    stableCount = 0
                                    lastCand = -1.0
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

    // 页面内价格提取（已经过 jsdom 6 场景测试全通过：骨架屏/3期免息防误报/推荐高价不盖主价/
    // 纯券拒绝/划线价/分期小额+主价）
    // 返回 JSON：sp=高可信选择器价(>=10), fp=第一个>=10的¥价, mp=最大¥价(诊断), t=标题, s=片段
    // 注意：必须用 clone+textContent（后台 WebView 不排版，innerText 恒为空）；
    //       选择器不含模糊 [class*="price"]（会误命中"3期免息"等元素）。
    private val JS_EXTRACT_PRICE = """
        (function(){
          try {
            var sp = '';
            var sels = ['.price-num', '.price .num', '.detail-price', '#price',
                        '.big-price', '.current-price', '.jd-price', '.sale-price'];
            for (var i = 0; i < sels.length; i++) {
              var el = document.querySelector(sels[i]);
              if (el) {
                var mm = (el.textContent || '').match(/[0-9]+(\.[0-9]+)?/);
                if (mm && parseFloat(mm[0]) >= 10) { sp = mm[0]; break; }
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
            var fp = '';
            var mp = '';
            var m = txt.match(/¥\s*[0-9]+(\.[0-9]+)?/g) || [];
            var nums = m.map(function(s){ return parseFloat(s.replace(/[^0-9.]/g, '')); })
                        .filter(function(n){ return n > 1 && n < 1000000; });
            for (var k = 0; k < nums.length; k++) {
              if (nums[k] >= 10) { fp = String(nums[k]); break; }
            }
            if (nums.length) {
              var sorted = nums.slice().sort(function(a, b){ return b - a; });
              mp = String(sorted[0]);
            }
            return JSON.stringify({
              sp: sp, fp: fp, mp: mp,
              t: document.title || '',
              s: txt.substring(0, 160)
            });
          } catch (e) {
            return JSON.stringify({sp: '', fp: '', mp: '', t: 'JS异常:' + e.message, s: ''});
          }
        })()
    """.trimIndent()
}
