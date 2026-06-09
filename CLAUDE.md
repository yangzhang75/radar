# Ship Radar — IMO CAT 1 船用雷达 安卓显控软件

安卓平台的 IMO CAT 1 级船用雷达显示与人机交互软件:接收 HALO 雷达 + 多路航海传感器(组播 UDP / 61162-450 / 61162-1),实时渲染 PPI 与目标,支持触屏/键/鼠控制,BAM 报警。**目标:送型式认证。**

## 构建 / 运行 / 测试
- **JVM 逻辑层(无需 Android SDK)**:`./gradlew :shared:test :comms-core:test :ui-core:test`
- **全量(含 Android,需 SDK)**:`./gradlew build` —— 编译 + 全测试 + lint + APK
  - 需 `local.properties` 含 `sdk.dir=...`(本机 SDK 在 `/opt/homebrew/share/android-commandlinetools`)
- **装到设备/模拟器**:`./gradlew :app:assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **假数据发生器**(离线测试):`./gradlew :tools:halofeed:run`
- 当前:`./gradlew build` 全绿,~500 测试,lint 干净,debug+release APK 正常;已在 API-34 模拟器(1920×1200 横屏)跑通 HMI。

## 架构(模块)
```
shared/        冻结接口契约(数据类型/常量/HALO opcode/Q12工具/AlarmState) —— 编排者唯一维护
comms-core/    纯JVM通讯逻辑: halo.image/control/status/handshake/target · iec61162 · iec450 · sync · alarm
ui-core/       纯JVM显示逻辑: ppi(几何) · color(着色) · target(CPA/TCPA·融合·叠加)
comms/         Android 库: Foreground Service(组播socket + 跑 comms-core + 暴露 RadarDataBus/Controller)
app/           Compose 应用: 横屏 HMI 装配(RadarScreen 槽位)+ ppi/target/control/databar/alarm/input/framework
tools/halofeed JVM 假数据发生器(组播UDP + 录制/回放)
```
**接口边界**:UI 只消费 `contract.RadarDataBus`/下发 `RadarController`,绝不碰 socket/协议字节。`RadarScreen`/`MainActivity` 是编排者所有的装配点,功能 worker 不碰。

## 设备 / 网络
- 目标:Android 13/14(API 33/34)横屏 1920×1200,**有线以太网**;minSdk 28 / targetSdk 34 / compileSdk 34。
- 远程:**蒲公英 X5 SD-WAN VPN**(旁路+组播透明),软件按同网段组播 + HALO 握手(01B1/01B2);MTU≤1400。

## 文档索引(`docs/` + 根目录)
- `项目计划与核查报告.md` —— 规格核查 + 架构 + 冻结接口 + 决策。
- `docs/任务台账.md` —— 各波任务/会话/分支/状态(已交付 ~33 任务,5 波)。
- `docs/合规追溯矩阵.md` —— 认证证据(要求→实现→测试)。
- `docs/认证缺口清单.md` —— 缺口分类(需标准 / 需真机 / 需张建 / 软件可补)。
- `docs/worker-prompts/` —— 各波派工 prompt(含复用约定:固定会话池、worktree 用**非桌面绝对路径**、绿了即 commit)。

## 进度 & 待外部输入(软件已到天花板)
- 原型 ≈ 86%;可认证产品 ≈ 42%。"不需真机、不缺标准"的软件已全部完成+测试+审查+修瑕。
- **需采购标准原文**:IHO S-52(精确色度)、ITU-R M.1371-5(AIS 静态解码)。
- **需真机/网络雷达 + 蒲公英 VPN**:HALO 目标通道 wire 格式抓包、320mm 物理实测、真数据端到端、性能调优。
- **需张建/船级社**:CPA/TCPA 阈值、融合门限、容量验收值、自动捕获准则、是否型式认证范围。
- **型式试验取证**:现场实船(A.823/GB 11711 等)。
