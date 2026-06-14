# 员工任务 W7-C：过去航迹(past tracks / target trails)

## 你是谁
船用雷达 Android 显控(IMO CAT1 认证)员工 AI。Kotlin + Compose。说中文,精确到 IEC 条款。

## 铁律(违反=返工)
1. **同步主线**:`git checkout -B feat/w7-tracks main`。
2. **只允许新建**:`app/src/main/kotlin/com/shipradar/app/tracks/`(package `com.shipradar.app.tracks`)+ 测试目录。**不改任何已有文件。**
3. **严禁触碰**:`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、`target/`(已有的 TargetOverlay 不要动,编排者会决定取舍)、`input/`、`ppi/`、`demo/`、`comms*`、`shared/*`、build/settings。根 `local.properties` 不存在则建。
4. 不引用其它 wave7 包(guardzone/viewctl)。

## 任务
**过去航迹**:按时间采样目标历史位置,在 PPI 上画出尾迹点串。依据 **IEC 62388 §11.5(过去位置/航迹)/ MSC.192 5.27**:可开关、可选时长(如 OFF/1/3/6 min),等间隔采样。

### 内容(自成体系,不依赖现有 TargetOverlay 的尾迹)
- `TrackHistory.kt`(纯逻辑,可单测):每个目标 id 一个**环形历史**(时间戳 + 极坐标 方位/距离 或直接存 NE 分量)。`fun sample(targets: List<TrackedTarget>, nowMs: Long)`;按配置的**采样间隔**落点;按**时长**裁剪过期点。线程安全或在单线程调用即可。
- `TracksConfig.kt`:`enum TrackLength { OFF, MIN_1, MIN_3, MIN_6 }` + 采样间隔。可 hoist 状态。
- `PastTracksOverlay.kt`:`@Composable` Canvas 叠加,把每个目标的历史点按当前 `rangeScaleNm`/`orientation`/本船投影画成淡色点串。入参:`history: TrackHistory(或其快照), center, radiusPx, rangeScaleNm, orientation, ownShip`。自包含。
- `TracksControlPanel.kt`:开关 + 时长选择(OFF/1/3/6 min)。
- 单测:采样间隔(过密不落点)、时长裁剪(老点被丢)、容量上限。
- `@Preview`(造一个目标 + 几个历史点)。

## 你消费的契约(只读)
- `com.shipradar.contract.TrackedTarget`(id, bearingDeg, rangeNm, …)、`OwnShipData`
- `com.shipradar.uicore.ppi.PpiOrientation`(投影方向);PPI 几何/投影可参考 ui-core 现成工具(只读复用,不改)。

## 完成标准
`./gradlew :app:compileDebugKotlin` 绿 + 单测绿;提交 `feat(app): 过去航迹 (W7-C)`;回报分支、文件、`PastTracksOverlay`/`TrackHistory`/`TracksControlPanel` 签名 + 编排者每帧如何 `sample()` 与渲染、以及"是否需要关掉现有 TargetOverlay 尾迹"的建议。
