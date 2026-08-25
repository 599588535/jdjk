package com.example.jdmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    // 全局复用的 WebView（兜底通道用，避免频繁创建/销毁）
    @Volatile
    private var sharedWebView: WebView? = null

    // 上一个商品成功读到的价格（用于检测"京东停留在旧页面没跳转"）
    private var lastReturnedPrice: Double = -1.0

    // 每轮检查开始前调用：重置"上一商品价格"，避免跨轮误判
    fun beginRound() {
        lastReturnedPrice = -1.0
    }

    // 从用户输入（纯数字ID 或 任意京东链接）中提取商品ID
    fun extractSku(input: String): String? {
        val s = input.trim()
        if (s.matches(Regex("\\d{5,}"))) return s
        val m = Regex("""(\d{5,})""").find(s)
        return m?.groupValues?.get(1)
    }

    // 返回：价格, 是否为账号价
    // 主通道：拉起京东 App 商品页 + 无障碍读屏（真实价格，含京东 App 登录态，最可靠）
    // 兜底通道：后台 WebView 渲染移动商品页（无障碍未开启时使用）
    fun getPrice(ctx: Context, sku: String, cookie: String): Pair<Double?, Boolean> {
        lastError = null

        if (PriceReaderService.isEnabled(ctx)) {
            val p = fetchViaJdApp(ctx, sku)
            if (p != null) return Pair(p, true)
            appendErr("京东App读屏未取到价格，转 WebView 兜底")
        } else {
            appendErr("无障碍未开启（仅 WebView 通道）")
        }

        val wv = fetchPriceViaWebView(ctx, sku, cookie)
        if (wv != null) return Pair(wv, cookie.isNotEmpty())

        return Pair(null, false)
    }

    private fun appendErr(msg: String) {
        lastError = if (lastError.isNullOrBlank()) msg else "$lastError\n$msg"
    }

    // ========== 主通道：跳京东 App 商品页 + 无障碍读屏 ==========
    // 跳转用 http 链接（京东 App Links 每次都会强制打开新页面，避免 openapp 协议"京东已运行时不再跳"的问题）
    private fun fetchViaJdApp(ctx: Context, sku: String, timeoutMs: Long = 25000): Double? {
        return try {
            PriceReaderService.reset()

            fun doLaunch(useHttp: Boolean): Boolean {
                return try {
                    val url = if (useHttp) {
                        "https://item.m.jd.com/product/$sku.html"
                    } else {
                        "openapp.jdmobile://virtual?params=" +
                            Uri.encode("{\"category\":\"jump\",\"des\":\"productDetail\",\"skuId\":\"$sku\"}")
                    }
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    true
                } catch (e: Exception) {
                    false
                }
            }

            // 优先 http 链接（走京东 App Links 或浏览器）；失败再试 openapp 协议
            var launched = doLaunch(true)
            if (!launched) launched = doLaunch(false)
            if (!launched) {
                appendErr("无法拉起京东App/浏览器")
                Prefs.appendLog(ctx, "    拉起京东App/浏览器失败")
                return null
            }
            Prefs.appendLog(ctx, "    已跳转商品页（SKU=$sku），等待读屏…")

            val start = System.currentTimeMillis()
            var lastProgressLog = 0L
            var retried = false
            while (System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(1000)
                val p = PriceReaderService.lastPrice
                val pkg = PriceReaderService.lastPricePkg
                // 直接内联判断，让 Kotlin 对 p 做智能转换为非空 Double
                if (p != null && p >= MIN_PRICE && p < 1000000 &&
                    pkg.isNotEmpty() && pkg != ctx.packageName
                ) {
                    if (p == lastReturnedPrice) {
                        // 读到的价格与上一商品相同 → 京东很可能停留在旧页面，重新跳转一次
                        if (!retried && System.currentTimeMillis() - start >= 5000) {
                            retried = true
                            Prefs.appendLog(ctx, "    价格与上一商品相同(¥$p)，疑似未跳转，重新打开…")
                            PriceReaderService.reset()
                            doLaunch(true)
                        }
                    } else {
                        lastReturnedPrice = p
                        Prefs.appendLog(ctx, "    读屏成功：¥$p（来源 $pkg）")
                        backToSelf(ctx)
                        return p
                    }
                }
                if (System.currentTimeMillis() - lastProgressLog >= 5000) {
                    lastProgressLog = System.currentTimeMillis()
                    Prefs.appendLog(ctx, "    等待页面价格…（${(System.currentTimeMillis() - start) / 1000}秒）")
                }
            }
            backToSelf(ctx)
            appendErr("读屏超时，屏幕文本=${PriceReaderService.lastPageText.take(140)}")
            Prefs.appendLog(ctx, "    读屏超时（${timeoutMs / 1000}秒）")
            null
        } catch (e: Exception) {
            appendErr("京东App通道异常：${e.message}")
            Prefs.appendLog(ctx, "    京东App通道异常：${e.message}")
            null
        }
    }

    // 读完价格后跳回本 App
    private fun backToSelf(ctx: Context) {
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ctx.startActivity(i)
            }
        } catch (_: Exception) {}
    }

    // ========== 兜底通道：WebView 真实渲染 ==========
    // 决策规则（防误报优先）：
    //   sp = 高可信选择器价格，需 >=10 且连续 2 次一致；
    //   fp = 正文中第一个 >=10 的 ¥数字（主价区在页面上方），需连续 3 次一致且已过 4 秒。
    private fun fetchPriceViaWebView(ctx: Context, sku: String, cookie: String, timeoutMs: Long = 25000): Double? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Double?>()
        val diag = AtomicReference("")
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                var wv = sharedWebView
                if (wv == null) {
                    wv = WebView(ctx.applicationContext)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.userAgentString = UA_MOBILE
                    wv.webViewClient = WebViewClient()
                    sharedWebView = wv
                }

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
                    latch.countDown()
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
            appendErr(if (d.isNotEmpty()) "WebView 未取到价格（$d）" else "WebView 渲染取价失败/超时")
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

    // 页面内价格提取（已经过 jsdom 6 场景测试全通过）
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
