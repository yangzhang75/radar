package com.shipradar.app.chart

import android.content.res.AssetManager
import org.json.JSONArray

/**
 * 海图海岸线数据。来源 = **Natural Earth 10m 公有海岸线**(已裁剪到长江口/上海/舟山一带,
 * `assets/coastline.json`,折线 = [lat, lon] 顶点序列)。这是**真实地理数据、可辨认海岸线**,
 * 但**不是认证用的 S-57 ENC**(S-57 需向海道测量局授权采购;接入后替换本加载器,渲染逻辑不变)。
 */
object ChartData {

    @Volatile private var cached: List<List<DoubleArray>>? = null

    /** 从 assets 加载海岸线折线(首次解析后缓存)。失败返回空(网格仍显示)。 */
    fun coastlines(assets: AssetManager): List<List<DoubleArray>> {
        cached?.let { return it }
        val parsed = runCatching {
            val text = assets.open("coastline.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val line = arr.getJSONArray(i)
                (0 until line.length()).map { j ->
                    val p = line.getJSONArray(j)
                    doubleArrayOf(p.getDouble(0), p.getDouble(1)) // [lat, lon]
                }
            }
        }.getOrDefault(emptyList())
        cached = parsed
        return parsed
    }
}
