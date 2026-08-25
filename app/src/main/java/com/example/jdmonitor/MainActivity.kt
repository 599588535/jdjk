package com.example.jdmonitor

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 若上次崩溃留下了日志，先显示报错页（此时进程健康，红页必弹；避免反复崩进主界面）
        val crashFile = java.io.File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION_CODES.TIRAMISU <= android.os.Build.VERSION.SDK_INT) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        loadPrefsToUi()
        renderProducts()
        renderLog()

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addProduct() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startMonitor() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopMonitor() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkOnce() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Prefs.clearLog(this); renderLog()
        }

        maybePromptAccessibility()
        maybePromptOverlay()
    }

    override fun onResume() {
        super.onResume()
        updateLoginStatus()
        renderProducts()
        renderLog()
    }

    // 首次打开时引导开启无障碍（京东App读屏抓价，最可靠）；开启后每次检查自动跳京东商品页读真实价格
    private fun maybePromptAccessibility() {
        if (PriceReaderService.isEnabled(this)) {
            Prefs.appendLog(this, "无障碍已开启：将使用京东App读屏抓价（最可靠）")
            return
        }
        val sp = getSharedPreferences("jd_monitor_prefs", MODE_PRIVATE)
        if (sp.getBoolean("acc_asked", false)) return
        sp.edit().putBoolean("acc_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("推荐：开启京东App抓价")
            .setMessage("后台静默抓价在你的网络下失败率高。\n\n开启「无障碍服务」后，每次检查会自动打开京东 App 的商品页，直接读取你看到的真实价格（含你的账号价），读完自动跳回。\n\n点「去开启」→ 在列表找到「京东降价监控」→ 打开开关。")
            .setPositiveButton("去开启") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    // 引导开启悬浮窗权限：安卓不允许后台应用打开界面，开启后多个商品才能连续自动跳转
    private fun maybePromptOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) return
        val sp = getSharedPreferences("jd_monitor_prefs", MODE_PRIVATE)
        if (sp.getBoolean("saw_asked", false)) return
        sp.edit().putBoolean("saw_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("推荐：允许显示悬浮窗")
            .setMessage("安卓系统禁止后台应用打开界面，会导致检查第二个商品时无法跳转。\n\n开启「显示在其他应用上层」后，多个商品就能全部自动连续检查。\n\n点「去开启」→ 找到「京东降价监控」→ 允许。")
            .setPositiveButton("去开启") { _, _ ->
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {}
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun loadPrefsToUi() {
        val etI = findViewById<EditText>(R.id.etInterval)
        if (etI.text.isEmpty()) etI.setText(Prefs.getInterval(this).toString())
        val etS = findViewById<EditText>(R.id.etStart)
        if (etS.text.isEmpty()) etS.setText(Prefs.getStart(this).toString())
        val etE = findViewById<EditText>(R.id.etEnd)
        if (etE.text.isEmpty()) etE.setText(Prefs.getEnd(this).toString())
        val etW = findViewById<EditText>(R.id.etWxKey)
        if (etW.text.isEmpty()) etW.setText(Prefs.getWxKey(this))
    }

    private fun savePrefsFromUi() {
        val iv = findViewById<EditText>(R.id.etInterval).text.toString().toDoubleOrNull()
        if (iv != null && iv > 0) Prefs.setInterval(this, iv)
        val sh = findViewById<EditText>(R.id.etStart).text.toString().toIntOrNull()
        if (sh != null && sh in 0..23) Prefs.setStart(this, sh)
        val eh = findViewById<EditText>(R.id.etEnd).text.toString().toIntOrNull()
        if (eh != null && eh in 0..23) Prefs.setEnd(this, eh)
        Prefs.setWxKey(this, findViewById<EditText>(R.id.etWxKey).text.toString().trim())
    }

    private fun addProduct() {
        val name = findViewById<EditText>(R.id.etName).text.toString().trim()
        val url = findViewById<EditText>(R.id.etUrl).text.toString().trim()
        val targetStr = findViewById<EditText>(R.id.etTarget).text.toString().trim()
        if (name.isEmpty() || url.isEmpty()) { toast("请填写商品名和链接/ID"); return }
        val target = targetStr.toDoubleOrNull()
        if (target == null || target <= 0) { toast("目标价格需为数字"); return }
        val sku = JdClient.extractSku(url)
        if (sku == null) { toast("无法识别商品ID，请填完整链接或纯数字ID"); return }
        val list = Prefs.loadProducts(this)
        list.add(Product(id = System.currentTimeMillis().toString(), name = name, url = url, target = target))
        Prefs.saveProducts(this, list)
        findViewById<EditText>(R.id.etName).setText("")
        findViewById<EditText>(R.id.etUrl).setText("")
        findViewById<EditText>(R.id.etTarget).setText("")
        Prefs.appendLog(this, "添加商品：$name")
        renderProducts()
        toast("已添加")
    }

    private fun renderProducts() {
        val container = findViewById<LinearLayout>(R.id.llProducts)
        container.removeAllViews()
        val list = Prefs.loadProducts(this)
        val inflater = LayoutInflater.from(this)
        for (p in list) {
            val row = inflater.inflate(R.layout.item_product_row, null) as LinearLayout
            val tvInfo = row.findViewById<TextView>(R.id.tvInfo)
            val btnEdit = row.findViewById<Button>(R.id.btnEdit)
            val btnDel = row.findViewById<Button>(R.id.btnDel)
            val last = if (p.lastPrice >= 0) "  现价¥${p.lastPrice}" else ""
            tvInfo.text = "${p.name}  目标¥${p.target}${if (p.notified) " ·已提醒" else ""}$last"
            btnDel.setOnClickListener {
                val l = Prefs.loadProducts(this).filter { it.id != p.id }
                Prefs.saveProducts(this, l)
                Prefs.appendLog(this, "删除商品：${p.name}")
                renderProducts()
            }
            btnEdit.setOnClickListener { editProduct(p) }
            container.addView(row)
        }
        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "（还没有商品，先在上方添加）"
            tv.textSize = 13f
            tv.setTextColor(0xFF888888.toInt())
            container.addView(tv)
        }
    }

    private fun editProduct(p: Product) {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 30, 40, 10)
        val etName = EditText(this); etName.setText(p.name); etName.hint = "商品名"
        val etUrl = EditText(this); etUrl.setText(p.url); etUrl.hint = "链接/ID"
        val etTarget = EditText(this); etTarget.setText(p.target.toString())
        etTarget.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        etTarget.hint = "目标价格"
        layout.addView(etName); layout.addView(etUrl); layout.addView(etTarget)
        AlertDialog.Builder(this)
            .setTitle("修改：${p.name}")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val n = etName.text.toString().trim()
                val u = etUrl.text.toString().trim()
                val t = etTarget.text.toString().toDoubleOrNull()
                if (n.isNotEmpty() && u.isNotEmpty() && t != null) {
                    val list = Prefs.loadProducts(this)
                    for (it in list) if (it.id == p.id) {
                        it.name = n; it.url = u; it.target = t; it.notified = false; it.lastPrice = -1.0
                    }
                    Prefs.saveProducts(this, list)
                    Prefs.appendLog(this, "修改商品：$n")
                    renderProducts()
                    toast("已保存")
                } else toast("请填写完整")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startMonitor() {
        savePrefsFromUi()
        val intervalHours = Prefs.getInterval(this)
        var repeatMinutes = (intervalHours * 60).toLong()
        if (repeatMinutes < 15) repeatMinutes = 15
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(repeatMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(MonitorWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MonitorWorker.WORK_TAG, ExistingPeriodicWorkPolicy.UPDATE, req)
        Prefs.appendLog(this, "开始监控：约每 $repeatMinutes 分钟检查一次")
        toast("监控已启动（后台运行）")
        renderLog()
    }

    private fun stopMonitor() {
        WorkManager.getInstance(this).cancelUniqueWork(MonitorWorker.WORK_TAG)
        Prefs.appendLog(this, "已停止监控")
        toast("监控已停止")
        renderLog()
    }

    private fun checkOnce() {
        savePrefsFromUi()
        val req = OneTimeWorkRequestBuilder<MonitorWorker>().addTag(MonitorWorker.WORK_TAG).build()
        WorkManager.getInstance(this).enqueue(req)
        Prefs.appendLog(this, "已触发一次立即检查")
        toast("正在检查（稍后在日志查看）")
        renderLog()
    }

    private fun renderLog() {
        findViewById<TextView>(R.id.tvLog).text = Prefs.getLog(this)
    }

    private fun updateLoginStatus() {
        val tv = findViewById<TextView>(R.id.tvLoginStatus)
        val cookie = Prefs.getCookie(this)
        tv.text = if (cookie.isEmpty()) "未登录（将使用公开价）" else "已登录：将优先获取账号专属价"
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
