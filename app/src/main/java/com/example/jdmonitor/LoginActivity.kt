package com.example.jdmonitor

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progressBar)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.progress = 100
                val cm = CookieManager.getInstance()
                val c = cm.getCookie("https://www.jd.com") ?: ""
                if (!saved && c.contains("pin=")) {
                    saved = true
                    saveCookieAndFinish()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
            }
        }
        webView.loadUrl("https://passport.jd.com/new/login.aspx?ReturnUrl=https://www.jd.com")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else {
            if (!saved) saveCookieAndFinish()
            super.onBackPressed()
        }
    }

    private fun saveCookieAndFinish() {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://www.jd.com") ?: ""
        val c2 = cm.getCookie("https://passport.jd.com") ?: ""
        val merged = "$c1;$c2"
        Prefs.setCookie(this, merged)
        // 持久化 cookie 到磁盘，确保后台 WebView 抓价时能共享登录态
        try { cm.flush() } catch (_: Exception) {}
        runOnUiThread {
            Toast.makeText(this, "已保存登录态，返回后可获取账号专属价", Toast.LENGTH_LONG).show()
        }
        webView.postDelayed({ finish() }, 1500)
    }
}
