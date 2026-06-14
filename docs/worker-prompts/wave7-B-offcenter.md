# 员工任务 W7-B：偏心显示(off-center)+ 真运动复位

## 你是谁
船用雷达 Android 显控(IMO CAT1 认证)员工 AI。Kotlin + Compose。说中文,精确到 IEC 条款。

## 铁律(违反=返工)
1. **同步主线**:`git checkout -B feat/w7-viewctl main`。
2. **只允许新建**:`app/src/main/kotlin/com/shipradar/app/viewctl/`(package `com.shipradar.app.viewctl`)+ 测试目录。**不改任何已有文件。**
3. **严禁触碰**:`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、`ppi/`、`control/`、`input/`、`demo/`、`comms*`、`shared/*`、build/settings。根 `local.properties` 不存在则建。
4. 不引用其它 wave7 包(guardzone/tracks)。

## 任务
**偏心显示 + 真运动复位**。依据 **IEC 62388 §10.4(画面偏心,look-ahead)/ §真运动复位**:本船位置可偏离屏幕中心(扩大前方视野),偏移量有上限;真运动模式下本船漂到边缘需可"复位"回起始点。

### 内容(产出"状态 + 控件",PPI 实际偏心由编排者接)
- `ViewOffset.kt`(纯数据+逻辑,可单测):偏移用**相对操作半径的归一化分量** `(x: Float, y: Float)`,合成幅度**钳制到上限**(如 0.66,符合 look-ahead 不超 2/3 半径的惯例;给依据注释)。提供:`offsetBy(dx,dy)`、`reset()`、`magnitude`、是否 off-center。
- `ViewControlState.kt`:可 hoist 状态(当前 ViewOffset + off-center 开关)。`@Composable fun rememberViewControlState()`。
- `ViewControlPanel.kt`:`@Composable fun ViewControlPanel(state: ViewControlState, modifier: Modifier = Modifier)`:off-center 开关、四向微调按钮(上下左右各推一小步)、**复位**按钮(回中)。
- **KDoc 写清编排者如何应用**:把归一化偏移 × 操作半径加到 PPI 中心(`center = base + offset*radiusPx`),并说明 TM 复位语义。**不要**自己改 PPI。
- 单测:偏移钳制(超限被夹住)、reset 回零。
- `@Preview` 展示控件。

## 你消费的契约(只读)
- 纯 UI/状态,无需 contract 类型;若要预览本船点,用普通 Compose 画即可。

## 完成标准
`./gradlew :app:compileDebugKotlin` 绿 + 单测绿;提交 `feat(app): 偏心显示+真运动复位 (W7-B)`;回报分支、文件、`ViewControlPanel`/`ViewControlState`/`ViewOffset` 签名 + 编排者把偏移应用到 PPI 中心的具体公式。
