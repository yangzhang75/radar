# 员工任务 W8-E：自动捕获区(auto-acquisition zone)UI

## 你是谁
船用雷达项目(IMO CAT1)员工 AI。Kotlin + Compose。说中文,对照 **IEC 62388 §11(目标自动捕获)/ MSC.192**。

## 铁律
1. **同步主线**:`git checkout -B feat/w8-autoacq main`。worktree 在 `~/.superset/worktrees/radar/` 下,**绝不建到桌面**。
2. **只新建**:`app/src/main/kotlin/com/shipradar/app/autoacq/`(package `com.shipradar.app.autoacq`)+ test。**不改任何已有文件**(尤其不碰 `guardzone/`——那是已合并的报警圈,你做的是"自动捕获区",独立)。
3. 不引用其它 wave8 包。

## 任务
**自动捕获区**:操作员画定扇环区域,雷达对进入该区的回波**自动起始跟踪**(区别于报警圈的"报警")。依据 IEC 62388 自动捕获要求。
- `AcqZoneModel.kt`(纯逻辑+单测):区参数=内/外距离 NM、起/止方位 deg、启用;命中测试纯函数(目标落在扇环内?跨 360° 边界正确)。支持 ≥1 个区。
- `AcqZoneOverlay.kt`:`@Composable` Canvas,把捕获区画成 PPI 扇环(与报警圈**视觉区分**,如虚线/不同色)。入参:`center: Offset, radiusPx: Float, rangeScaleNm: Double, orientation: PpiOrientation, zones: List<AcqZone>, headingDeg, courseDeg`。
- `AcqZoneSetupPanel.kt`:`@Composable fun AcqZoneSetupPanel(zones: List<AcqZone>, onZonesChange: (List<AcqZone>) -> Unit, modifier: Modifier = Modifier)`:启用开关 + 内/外距离 + 起/止方位 步进控件。**状态 hoisted**(由编排者持有,便于同时画 overlay)。
- 命中判定可复用思路但**不要 import guardzone 包**(自成体系)。
- `@Preview`。

## 你消费的契约/工具(只读)
- `com.shipradar.contract.TrackedTarget`(方位/距离)
- `com.shipradar.uicore.ppi.PpiOrientation` + ui-core 投影工具(只读复用)

## 完成
`./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest` 绿;提交 `feat(app): 自动捕获区 UI (W8-E)`;回报分支、文件、`AcqZoneSetupPanel`/`AcqZoneOverlay`/`AcqZone` 签名 + 编排者接入所需输入(hoisted zones + PPI 中心/半径)。
