# 员工任务 W6-C：性能监视 / BITE 自检面板

## 你是谁
船用雷达 Android 显控项目(送 IMO CAT 1 型式认证)的员工 AI。Kotlin + Jetpack Compose。说中文。

## 铁律(违反=返工)
1. **同步主线**:`git checkout -B feat/w6-bite main`(基于当前 `main`)。
2. **只允许新建**:`app/src/main/kotlin/com/shipradar/app/bite/`(package `com.shipradar.app.bite`)+
   `app/src/test/kotlin/com/shipradar/app/bite/`。**不改任何已有文件。**
3. **严禁触碰**:`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、`demo/`、`input/`、`comms*` 源码、
   `shared/*`、build/settings。根 `local.properties` 不存在则创建(`sdk.dir=/opt/homebrew/share/android-commandlinetools`)。
4. 不引用其它 wave6 员工的包(trial/theme)。

## 任务
实现**性能监视 / BITE(Built-In Test Equipment)自检面板**。依据 **IEC 62388 §6/§15(集成测试/性能监测)/ 62388 性能监视器要求**:雷达须持续指示设备健康、传感器有效性、链路状态,并提供自检。

### 内容(纯展示 + 简单健康判定,数据由编排者注入)
- 新建 `BiteState.kt`:定义一个**纯数据**的健康快照模型 `BiteReport`,字段例如:
  - 链路状态(用 `com.shipradar.contract.LinkState`)
  - 各通道收包速率/最后到包时延(可定义自己的简单字段;编排者会从 `com.shipradar.comms.service.DataLinkStats` 映射进来)
  - 传感器有效性:航向(HDG)有效?、位置(POSN)有效?、SOG 有效?(布尔,来自 OwnShipData 是否为 null)
  - 整体状态:OK / 降级(DEGRADED)/ 故障(FAULT)的判定函数(纯函数,可单测)
- 新建 `BitePanel.kt`:`@Composable fun BitePanel(report: BiteReport, modifier: Modifier = Modifier)` ——
  分区显示:链路、各数据通道(收包/速率/时延、绿=活跃 黄=静默 红=无)、传感器有效性、整体健康灯。
  并提供一个"运行自检 RUN BITE"按钮回调 `onRunBite: () -> Unit = {}`(点了把各项标记为"已自检+时间")。
- **不要**自己去连 comms 或读 socket。只接收 `BiteReport` 作为入参。提供 `@Preview`(造一个 OK 的、一个 DEGRADED 的)。
- 单测:`BiteReport` 的整体健康判定函数(无链路→FAULT、链路在但某传感器无效→DEGRADED、全好→OK)。

## 你消费的契约类型(只读)
- `com.shipradar.contract.LinkState { DISCONNECTED, NEGOTIATING, CONNECTED, DEGRADED }`
- `com.shipradar.contract.OwnShipData`(判定传感器有效性:字段为 null = 无效)
- 编排者会把 `com.shipradar.comms.service.DataLinkStats`(各通道 ChannelStat: packets/lastMs)映射进 `BiteReport`,你定义好 `BiteReport` 的形状即可。

## 完成标准
- `./gradlew :app:compileDebugKotlin` 绿;`:app:testDebugUnitTest` 你的判定测试绿。
- 提交 `feat(app): 性能监视/BITE 自检面板 (W6-C)`。
- 回报:分支、文件清单、`BitePanel`/`BiteReport` 签名 + 编排者应如何把 `DataLinkStats`+`OwnShipData` 映射成 `BiteReport`。
