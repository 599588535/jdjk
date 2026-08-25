package com.example.jdmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar

class MonitorWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "jd_monitor_channel"
        const val CHANNEL_NAME = "京东降价监控"
        const val WORK_TAG = "jd_monitor_work"
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        val products = Prefs.loadProducts(ctx)
        if (products.isEmpty()) {
            Prefs.appendLog(ctx, "监控触发：无商品，跳过")
            return Result.success()
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = Prefs.getStart(ctx)
        val end = Prefs.getEnd(ctx)
        val inHours = if (start <= end) (hour >= start && hour < end) else (hour >= start || hour < end)
        if (!inHours) {
            Prefs.appendLog(ctx, "非活跃时段（$start–$end 点），本次跳过")
            return Result.success()
        }

        val cookie = Prefs.getCookie(ctx)
        JdClient.beginRound()
        Prefs.appendLog(ctx, "监控触发：检查 ${products.size} 个商品")

        var index = 0
        for (p in products) {
            index++
            // 清理历史误报：之前 HTML 解析抓到 1 元/3 元等占位价触发了 "已提醒"；
            // 若 lastPrice 低于目标价 20%，大概率是误抓，重置状态避免永远不再提醒
            if (p.notified && p.lastPrice > 0 && p.target > 0 && p.lastPrice < p.target * 0.2) {
                p.notified = false
                p.lastPrice = -1.0
            }
            Prefs.appendLog(ctx, "▶ 第 $index/${products.size} 个：${p.name}")
            val sku = JdClient.extractSku(p.url)
            if (sku == null) {
                Prefs.appendLog(ctx, "  ${p.name}：无法识别商品ID，跳过")
                continue
            }
            val (price, isAcc) = JdClient.getPrice(ctx, sku, cookie)
            if (price == null) {
                Prefs.appendLog(ctx, "  ${p.name}：获取价格失败（${JdClient.lastError ?: "未知原因"}）")
                continue
            }
            p.lastPrice = price
            val tag = if (isAcc) "(账号价)" else "(公开价)"
            Prefs.appendLog(ctx, "  ${p.name}：现价¥$price$tag，目标¥${p.target}")
            if (price <= p.target && !p.notified) {
                sendNotify(ctx, "京东降价提醒",
                    "${p.name} 现价 ¥$price$tag，已低于目标 ¥${p.target}")
                val ok = PushHelper.push(ctx, Prefs.getWxKey(ctx),
                    "京东降价：${p.name}", "现价 ¥$price$tag，目标 ¥${p.target}")
                if (!ok) Prefs.appendLog(ctx, "  微信推送失败")
                else Prefs.appendLog(ctx, "  已推送微信")
                p.notified = true
            } else if (price > p.target) {
                p.notified = false
            }
        }
        Prefs.saveProducts(ctx, products)
        Prefs.appendLog(ctx, "本轮检查完成")
        return Result.success()
    }

    private fun sendNotify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
        val id = System.currentTimeMillis().toInt()
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
