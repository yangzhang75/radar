# 员工任务 W6-B：昼/黄昏/夜 主题 + 亮度(Brilliance)

## 你是谁
船用雷达 Android 显控项目(送 IMO CAT 1 型式认证)的员工 AI。Kotlin + Jetpack Compose。说中文。

## 铁律(违反=返工)
1. **同步主线**:`git checkout -B feat/w6-theme main`(基于当前 `main`)。
2. **只允许**:
   - 新建 `app/src/main/kotlin/com/shipradar/app/theme/`(package `com.shipradar.app.theme`)
   - **仅追加**式修改 `app/src/main/kotlin/com/shipradar/app/framework/ObTokens.kt`(只新增昼/黄昏/夜调色板常量,**不得改已有值/已有签名**;若改动会影响他人,改为在你自己的 `theme/` 包内定义调色板)
   - 测试 `app/src/test/kotlin/com/shipradar/app/theme/`
3. **严禁触碰**:`RadarScreen.kt`、`MainActivity.kt`、`RadarHotkeys.kt`、`framework/RadarScaffold.kt`、
   `framework/ObTheme.kt` 的已有逻辑、`demo/`、`input/`、`comms*`、`shared/*`、build/settings。
   根 `local.properties` 不存在则创建(`sdk.dir=/opt/homebrew/share/android-commandlinetools`)。
4. 不引用其它 wave6 员工的包(trial/bite)。

## 任务
实现**昼(DAY)/黄昏(DUSK)/夜(NIGHT)三套主题 + 亮度调节**。依据 **IEC 62288 §5(色彩/可读性)/ MSC.191**:
夜间须低亮度、红/暗色调保护暗视觉;三档之间可切换;亮度连续可调。

### 内容
- 先看现有 `framework/ObTheme.kt`、`ObTokens.kt`(了解现有 DAY 调色板与 OpenBridgeTheme 用法,**只读**)。
- 在你的 `theme/` 包:
  - `RadarPalette.kt`:定义 DAY / DUSK / NIGHT 三套调色板(背景、前景、回波、文字、警示色…),NIGHT 走暗背景+低亮、暖红夜视色。给出每色的依据注释(62288/夜视)。
  - `ThemeState.kt`:`enum ThemeMode { DAY, DUSK, NIGHT }` + 一个可 hoist 的状态(当前模式 + 亮度 0.0~1.0)。
  - `ThemePanel.kt`:`@Composable fun ThemePanel(mode: ThemeMode, brilliance: Float, onModeChange:(ThemeMode)->Unit, onBrillianceChange:(Float)->Unit, modifier: Modifier = Modifier)` —— 三档按钮 + 亮度滑条。
  - 一个 `@Composable fun rememberThemeState()` 或等价,方便编排者 hoist。
- **不要**去改 RadarScaffold/RadarScreen 应用主题——只产出"调色板 + 控件 + 状态",编排者负责把它接到 OpenBridgeTheme/全局。但要在 KDoc 写清"编排者如何应用"(例如:把 mode→ObTheme、brilliance→整体 alpha/背光)。
- `@Preview` 展示三档面板。

## 完成标准
- `./gradlew :app:compileDebugKotlin` 绿;有测试则 `:app:testDebugUnitTest` 绿。
- 提交 `feat(app): 昼/黄昏/夜主题 + 亮度 (W6-B)`。
- 回报:分支、文件清单、`ThemePanel`/`ThemeState` 签名 + "编排者如何把主题应用到全局"的说明。
