package com.example.jdmonitor

import org.json.JSONObject

data class Product(
    val id: String,
    var name: String,
    var url: String,
    var target: Double,
    var notified: Boolean = false,
    var lastPrice: Double = -1.0
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("url", url)
        o.put("target", target)
        o.put("notified", notified)
        o.put("lastPrice", lastPrice)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Product {
            return Product(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                url = o.optString("url", ""),
                target = o.optDouble("target", 0.0),
                notified = o.optBoolean("notified", false),
                lastPrice = o.optDouble("lastPrice", -1.0)
            )
        }
    }
}
