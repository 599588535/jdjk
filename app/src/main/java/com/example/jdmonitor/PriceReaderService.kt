package com.example.jdmonitor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// 无障碍读屏服务：监控时 App 拉起京东商品页，本服务读取屏幕上渲染出的真实价格。
// 京东 App/浏览器里显示的价格 = 用户看到的真实价格（含账号登录态），不受接口风控影响。
class PriceReaderService : AccessibilityService() {

    companion object {
        // 最近一次从屏幕上读到的合理价格（>=10 元的第一个 ¥数字）
        @Volatile
        var lastPrice: Double? = null
            private set

        // 读到价格时所在界面的包名（用于过滤本 App 自己的界面，防止读到"现价¥xxx"残留）
        @Volatile
        var lastPricePkg: String = ""
            private set

        // 最近一屏文本（诊断用）
        @Volatile
        var lastPageText: String = ""
            private set

        fun reset() {
            lastPrice = null
            lastPricePkg = ""
        }

        // 检测本 App 的无障碍服务是否已被用户开启
        fun isEnabled(ctx: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.contains(ctx.packageName, ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        // 关键：忽略本 App 自己的界面——否则会把本 App 商品列表里的"现价¥xxx"当成新价格读进去
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        var nodeCount = 0

        // 递归收集整屏文本（限制节点数防止大页面卡顿）
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || nodeCount > 500) return
            nodeCount++
            try {
                node.text?.let { sb.append(it).append(' ') }
                node.contentDescription?.let { sb.append(it).append(' ') }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            } catch (_: Exception) {}
        }
        walk(root)

        val text = sb.toString().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return
        lastPageText = text.take(300)

        // 取屏幕上第一个 >=10 的 ¥数字：商品页价格区在上方，推荐位价格不会被误取
        for (m in Regex("""¥\s*([0-9]+(?:\.[0-9]{1,2})?)""").findAll(text)) {
            val v = m.groupValues[1].toDoubleOrNull()
            if (v != null && v >= 10 && v < 1000000) {
                lastPrice = v
                lastPricePkg = pkg
                break
            }
        }
    }

    override fun onInterrupt() {}
}
