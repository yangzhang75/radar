package com.shipradar.app.chart

/**
 * 演示海图数据(简化海岸线,经纬度)。**真 S-57 海图待采购**;此处内置一小段供"海图叠加"功能演示,
 * 位置在本船默认位附近(长江口一带,~31.2°N 122.1°E)。每条折线是 [lat, lon] 顶点序列;闭合折线=岛/陆块。
 *
 * 接入真海图后,把这里换成 S-57 解析出的要素(海岸线/等深线/航标),叠加渲染逻辑(ChartOverlay)不变。
 */
object ChartData {

    /** 海岸线/陆块折线(纬度, 经度)。 */
    val coastlines: List<List<DoubleArray>> = listOf(
        // 近岸西海岸(~5 NM,默认 6 NM 量程即可见)
        listOf(
            doubleArrayOf(31.12, 122.085), doubleArrayOf(31.18, 122.07), doubleArrayOf(31.23, 122.065),
            doubleArrayOf(31.28, 122.075), doubleArrayOf(31.33, 122.10),
        ),
        // 近岸小岛(~4 NM)
        listOf(
            doubleArrayOf(31.20, 122.22), doubleArrayOf(31.22, 122.25), doubleArrayOf(31.25, 122.245),
            doubleArrayOf(31.245, 122.215), doubleArrayOf(31.20, 122.22),
        ),
        // 西岸(大陆,南北走向)
        listOf(
            doubleArrayOf(31.02, 121.96), doubleArrayOf(31.10, 121.99), doubleArrayOf(31.18, 121.98),
            doubleArrayOf(31.24, 121.95), doubleArrayOf(31.31, 121.92), doubleArrayOf(31.39, 121.90),
            doubleArrayOf(31.46, 121.89),
        ),
        // 北侧岛(闭合)
        listOf(
            doubleArrayOf(31.34, 122.22), doubleArrayOf(31.37, 122.30), doubleArrayOf(31.42, 122.32),
            doubleArrayOf(31.45, 122.27), doubleArrayOf(31.43, 122.19), doubleArrayOf(31.37, 122.17),
            doubleArrayOf(31.34, 122.22),
        ),
        // 东南小岛(闭合)
        listOf(
            doubleArrayOf(31.08, 122.30), doubleArrayOf(31.10, 122.35), doubleArrayOf(31.14, 122.34),
            doubleArrayOf(31.13, 122.29), doubleArrayOf(31.08, 122.30),
        ),
    )
}
