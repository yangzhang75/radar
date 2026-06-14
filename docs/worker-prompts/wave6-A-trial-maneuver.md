# 员工任务 W6-A：试操船(Trial Maneuver)面板

## 你是谁
船用雷达 Android 显控项目(送 IMO CAT 1 型式认证)的员工 AI。Kotlin + Jetpack Compose。
**说中文。精确到 IEC 条款,不近似。**

## 铁律(违反=返工)
1. **同步到最新主线**:在你的 worktree 里执行
   `git checkout -B feat/w6-trial main`(基于当前 `main`,含全部最新代码;不要从旧提交建)。
2. **只允许新建**这些文件,**不得改动任何已有文件**:
   - `app/src/main/kotlin/com/shipradar/app/trial/` 下的新 `.kt`(package `com.shipradar.app.trial`)
   - `app/src/test/kotlin/com/shipradar/app/trial/` 下的单元测试
3. **严禁触碰**(整合由编排者做):`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、
   `control/RadarDisplaySettings.kt`、`demo/`、`input/`、`infopanel/`、`comms*`、`shared/*`(只读)、
   任何 build.gradle / settings.gradle(若根 `local.properties` 不存在则创建,内容
   `sdk.dir=/opt/homebrew/share/android-commandlinetools`,它已被 gitignore)。
4. **不得引用其它 wave6 员工的包**(theme/bite),零依赖、零碰撞。

## 任务
实现**试操船**:操作员假设本船改向/改速(可带延迟),系统在不影响真实跟踪的前提下,**重算各目标相对本船的 CPA/TCPA 与相对矢量**,辅助避碰决策。依据 **IEC 62388 §11(目标跟踪)/ IMO MSC.192 / A.823(ARPA 试操船)**。

### 1. 纯逻辑(可单测,先做)
- 新建 `TrialManeuver.kt`:输入本船当前态(航向/航速)、目标列表、试操船参数(试操航向 trialCourseDeg、试操航速 trialSpeedKn、延迟 delayMin),输出每个目标在"试操生效后"的 **CPA(NM)/TCPA(s)/相对方位**。
- **复用 ui-core 现成的相对运动/CPA 算法**(在 `com.shipradar.uicore.target` 下找,例如 CPA/TCPA 求解器;先 grep 关键字 `cpa`/`tcpa`/`relative`),**不要自己重写矢量数学**。只把"本船速度矢量"换成试操后的矢量再求解。
- 单测:构造 1~2 个会变危险/变安全的目标,断言试操后 CPA/TCPA 变化方向正确。

### 2. Compose 面板
- 新建 `TrialManeuverPanel.kt`:`@Composable fun TrialManeuverPanel(ownShip: OwnShipData, targets: List<TrackedTarget>, modifier: Modifier = Modifier)`。
- 内部状态:试操航向/航速/延迟(slider 或 +/- 步进)、一个"试操开/关"开关。
- 显示结果表:每个目标 试操后 CPA/TCPA(危险用红色),以及与当前值的对比。
- **试操模式必须有明显常驻标识**(如面板顶部红/琥珀 "TRIAL 试操"),IEC 要求试操不得与真实态混淆。
- 提供 `@Preview`(造几个假目标),自包含可预览。

## 你消费的契约类型(只读,来自 `com.shipradar.contract`)
- `OwnShipData(headingDeg: Double?, cogDeg: Double?, sogKn: Double?, latitude, longitude, headingTrue)`
- `TrackedTarget(id, bearingDeg: Double, rangeNm: Double, courseDeg: Double?, speedKn: Double?, cpaNm: Double?, tcpaSec: Double?, dangerous: Boolean, trueBearing: Boolean, source)`

## 完成标准
- `./gradlew :app:compileDebugKotlin` 绿;`./gradlew :app:testDebugUnitTest` 你的测试绿(或纯逻辑放 `:app` 单测)。
- `git add -A && git commit -m "feat(app): 试操船面板 (W6-A)"`。
- **回报**:分支名、新增文件清单、`TrialManeuverPanel` 的最终签名 + 它需要哪些输入(供编排者接入 RadarScreen)。
