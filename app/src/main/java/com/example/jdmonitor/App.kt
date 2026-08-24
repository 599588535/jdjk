package com.example.jdmonitor

import android.app.Application
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        // 尽量在 super.onCreate() 之前注册，才能捕获 Application 初始化阶段的崩溃
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "崩溃信息（请把这段发给我）：\n" +
                    "时间：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) + "\n" +
                    "设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE + "\n\n" +
                    sw.toString()
                // 写入文件：即使红页来不及弹，下次打开 App 也会自动显示
                try {
                    File(filesDir, "crash.txt").writeText(text)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // 尽力立即弹红页（进程已死时可能弹不出，靠下次打开兜底）
                try {
                    val intent = Intent(this, CrashActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onCreate()
    }
}
