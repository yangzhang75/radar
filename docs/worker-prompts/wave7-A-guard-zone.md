# 员工任务 W7-A：报警圈 / 捕获区 图形设置

## 你是谁
船用雷达 Android 显控(IMO CAT1 认证)员工 AI。Kotlin + Compose。说中文,精确到 IEC 条款。

## 铁律(违反=返工)
1. **同步主线**:`git checkout -B feat/w7-guardzone main`(基于当前 `main`,含最新代码)。
2. **只允许新建**:`app/src/main/kotlin/com/shipradar/app/guardzone/`(package `com.shipradar.app.guardzone`)+ `app/src/test/kotlin/com/shipradar/app/guardzone/`。**不改任何已有文件。**
3. **严禁触碰**:`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、`control/`、`demo/`、`input/`、`infopanel/`、`target/`、`comms*`、`shared/*`(只读)、build/settings。根 `local.properties` 不存在则建(`sdk.dir=/opt/homebrew/share/android-commandlinetools`)。
4. 不引用其它 wave7 包(viewctl/tracks)。

## 任务
**报警圈(guard zone)+ 捕获区(acquisition zone)图形设置**。依据 **IEC 62388 §11(目标跟踪/报警区)/ MSC.192**:操作员设定一/两个扇形/环形区,目标进入(或离开)触发报警;可设灵敏度;可独立开关。

### 内容
- `GuardZoneModel.kt`(纯数据+逻辑,可单测):区参数 = 内/外距离(NM)、起/止方位(deg,真或相对)、启用、报警方向(进入/离开/双向)、灵敏度(0..N)。提供一个判定纯函数:给定目标(方位 deg、距离 NM)是否落在某区内(扇环命中测试),供单测。支持 2 个区(zone 0/1)。
- `GuardZoneOverlay.kt`:`@Composable` Canvas 叠加,把区画成 PPI 上的扇环(半透明边/填充)。入参:`center: Offset, radiusPx: Float, rangeScaleNm: Double, orientation: PpiOrientation, zones: List<GuardZone>`。自包含、不依赖现有 overlay。
- `GuardZoneSetupPanel.kt`:`@Composable fun GuardZoneSetupPanel(controller: RadarController, modifier: Modifier = Modifier)`:每个区的 启用开关 + 内/外距离 + 起/止方位 + 报警方向 + 灵敏度 控件;改动时调用 `controller.send(...)` 下发(见下命令)。本地维护区参数状态。
- 单测:扇环命中测试(区内/区外/跨 360° 边界各一例)。
- `@Preview`(画 1~2 个区 + 几个目标点示意)。

## 你下发的命令(`com.shipradar.contract.RadarController.send(RadarCommand)`)
- `RadarCommand.GuardZoneEnable(zone: Int, on: Boolean)`
- `RadarCommand.GuardZoneSetup(...)`(看 `shared` 里 Commands.kt 的实际字段,按其签名构造)
- `RadarCommand.GuardZoneAlarmMode(zone: Int, type: GuardZoneAlarmType)`
- `RadarCommand.GuardZoneSensitivity(level: Int)`
（先 grep `Commands.kt` 看精确字段,别臆造。)

## 完成标准
`./gradlew :app:compileDebugKotlin` 绿 + `:app:testDebugUnitTest` 你的测试绿;提交 `feat(app): 报警圈/捕获区图形设置 (W7-A)`;回报分支、文件、`GuardZoneSetupPanel`/`GuardZoneOverlay` 签名 + 编排者接入所需输入。
