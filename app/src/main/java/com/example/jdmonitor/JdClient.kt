    // 从 HTML 中提取价格：按可信度从高到低尝试，并排除页面里常见的占位值（如 ¥1）
    private fun extractPriceFromHtml(html: String): Double? {
        // 1) 精确 class="price"（含 "price J-p-xxx"），取其标签内价格
        //    注意：用精确匹配避免误命中 price-label / price-tips 等无关元素
        val priceClassRe = Regex(
            """class=["']price(\s[^"']*)?["'][^>]*>¥?\s*([0-9]+(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        priceClassRe.find(html)?.let { m ->
            val v = m.groupValues[2].toDoubleOrNull()
            if (v != null && v > 0.01 && v < 1000000 && v != 1.0) return v
        }
        // 2) 内联 JSON 的 "price":"x" 或 "price":x
        val jsonRe = Regex("""["']price["']\s*:\s*["']?([0-9]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
        jsonRe.find(html)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull()
            if (v != null && v > 0.01 && v < 1000000 && v != 1.0) return v
        }
        // 3) data-price 属性（京东很多无关元素也有此占位，仅作兜底且要求 > 1，跳过 data-price="1"）
        val dataRe = Regex("""data-price=["']?([0-9]+(?:\.[0-9]{1,2})?)["']?""", RegexOption.IGNORE_CASE)
        dataRe.find(html)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull()
            if (v != null && v > 1.0 && v < 1000000) return v
        }
        // 4) ¥ 紧跟价格（排除恰好 ¥1 的占位/优惠券）
        val yenRe = Regex("""¥\s*([0-9]+(?:\.[0-9]{1,2})?)""")
        yenRe.findAll(html).forEach { m ->
            val v = m.groupValues[1].toDoubleOrNull()
            if (v != null && v > 0.01 && v < 1000000 && v != 1.0) return v
        }
        return null
    }
