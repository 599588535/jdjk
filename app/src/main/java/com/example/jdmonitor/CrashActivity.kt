package com.example.jdmonitor

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        val tv = findViewById<TextView>(R.id.tvCrash)
        val btn = findViewById<Button>(R.id.btnCopy)
        val text = try {
            java.io.File(filesDir, "crash.txt").readText()
        } catch (e: Exception) {
            "无法读取崩溃日志"
        }
        tv.text = text
        btn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            Toast.makeText(this, "已复制，请粘贴发给我", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finishAffinity()
            System.exit(0)
        }
    }
}
