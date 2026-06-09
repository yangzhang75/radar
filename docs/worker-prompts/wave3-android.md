# 第三波派工 · Android HMI（复用现有会话）

> 安卓地基已就绪(`main` 上 `./gradlew :app:assembleDebug` 出 APK)。下面每节是一份**粘进对应已有会话**的 prompt。
> **复用映射**(不要开新会话):

| 任务 | 粘进会话 | 独占目录 |
|---|---|---|
| T1.1 服务接线 | **sphenoid-watchmaker** | `comms/` (service) |
| T2.1r PPI 渲染面 | **vivacious-clover** | `app/ppi/` |
| T2.3r 目标/航迹绘制 | **few-basin** | `app/target/` |
| T2.4 量程/模式切换 | **purring-budget** | `app/mode/` |
| T2.5 交互(触/键/鼠) | **past-freon** | `app/input/` |
| T2.6 雷达控制台 | **thorn-poppyseed** | `app/control/` |
| T2.7 数据栏+永久显示 | **rounded-fireplace** | `app/databar/` |
| T2.8 报警界面 | **carefree-drip** | `app/alarm/` |
| T2.9 OpenBridge框架 | **absorbed-stetson** | `app/framework/` |

## 通用规则(所有第三波员工)
1. **复用你被指定的会话**,不要开新会话。新建工作区从 main:`git worktree add ../wt-<任务> -b feat/<任务> main`。
2. **只改你的独占目录**。**绝不碰** `MainActivity.kt`/`RadarScreen.kt`(装配点归编排者)、`shared/`、`*-core`、别人的包、根 gradle / gradle.properties。
3. 暴露**一个入口 Composable**(如 `ControlPanel(...)`/`PpiView(...)`),编排者把它接进 `RadarScreen`。
4. **数据走契约**:用 `com.shipradar.contract`(RadarDataBus/RadarController)+ 预览/假数据开发,**不要依赖 T1.1 是否完成**。复用 `ui-core`(几何/着色/目标)与 `comms-core` 逻辑,别重写。
5. 构建:`./gradlew :app:assembleDebug` 且 `:app:lintDebug` 全绿;**绿后立即 commit**。
6. **送认证**:符号/颜色/布局引 `IEC 62288 §x`(`标准资料/80-733_IEC 62288...pdf`)/`IMO A.278`(图标)/`IEC 62388`;精确不近似,缺则 `TODO(待标准)`;落实条款写进交付报告(勿改矩阵)。

---
## T1.1 → 会话 sphenoid-watchmaker（目录 `comms/`）
把通讯前台服务接到真网络:在 `RadarCommsService` 里开组播 socket(HALO 236.6.7.x + 61162-450 组),驱动 `comms.halo.handshake`(01B1/01B2+A1C1看门狗)、各 parser、`comms.sync` 重连;实现并暴露 `contract.RadarDataBus`/`RadarController`(供 UI 绑定)。有线网仍要 MulticastLock。只改 `comms/`。验证:`:comms:assembleDebug`+单测(可对 socket 层做可注入抽象做单测)。这是让真数据上屏的关键。

## T2.1r → 会话 vivacious-clover（目录 `app/ppi/`）
PPI 回波渲染面:`PpiView(spokes: Flow<EchoSpoke>, …)` 用 **Canvas 或 OpenGL ES** 把辐条画成圆形 PPI。复用 `ui-core.ppi`(极坐标→屏幕/距离环/方位刻度)+ `ui-core.color.ColorMapper`(着色)。要求硬件加速、横屏 1920×1200、≥320mm 等效(用 ui-core DisplaySize)、换档消隐≤1扫描周期。预览用假辐条。引 62388/62288 显示条款。

## T2.3r → 会话 few-basin（目录 `app/target/`）
目标/航迹叠加层:`TargetOverlay(targets: StateFlow<List<TrackedTarget>>, …)` 在 PPI 之上画雷达目标+AIS符号、矢量、过去航迹、CPA/TCPA 危险高亮。复用 `ui-core.target`。符号/颜色按 IEC 62288/62388(危险目标红色等)。容量≥240 流畅。预览用假目标。

## T2.4 → 会话 purring-budget（目录 `app/mode/`）
量程/运动/定向切换 UI:`ModeControls(status, controller, …)`。强制量程档 0.25–24NM(用 `MANDATORY_RANGE_SCALES_NM`);相对/真运动;船首向上/北向上/航向向上。当前档常显。下发经 RadarController。引 62388。

## T2.5 → 会话 past-freon（目录 `app/input/`）
统一交互层:触屏/键盘/鼠标等价处理——缩放/平移/点选目标/EBL(电子方位线)/VRM(可变距离圈)/平行索引线。输出手势/选择事件供 PPI 与目标层消费(经回调/状态,不直接改它们的包)。CAT1 要求三类输入。

## T2.6 → 会话 thorn-poppyseed（目录 `app/control/`）
雷达控制台 UI:`ControlPanel(status, controller)` —— 增益/海浪/雨雪/干扰抑制/转速/发射待机/报警圈等,产出 `RadarCommand` 经 RadarController 下发(你熟,T1.3 就是你编的码)。控件英文名/缩写+图标按 IEC 62288/A.278。

## T2.7 → 会话 rounded-fireplace（目录 `app/databar/`）
数据栏 + **防撞要素永久显示(不得遮挡)**:增益/抑制状态、量程档、运动/定向模式、矢量模式/时间/稳定、主从、各传感器失效指示、本船航向航速位置等。`DataBar(ownShip, status, sensorValidity, …)`。严格按 IEC 62388 §永久显示清单逐项(ALRM-02),引条款。

## T2.8 → 会话 carefree-drip（目录 `app/alarm/`）
报警界面:`AlarmBar(...)`/列表/确认/静音,**消费 `comms.alarm` 的 BAM 状态机**(你写的 T2.8a)。按 IEC 62923-1 显示状态(闪烁/声音/已确认…)、优先级配色、标准ID文案。确认/静音动作经 RadarController/意图回传。引 62923-1。

## T2.9 → 会话 absorbed-stetson（目录 `app/framework/`）
OpenBridge 外围框架:主题(昼/黄昏/夜,复用你的 ColorMapper 调色思路)、按钮/菜单/数据栏与报警栏的样式 chrome、`RadarScaffold(slots…)` 布局容器(供编排者把各 FooView 放进去)。**原生重画对齐 OpenBridge 6.0**(github.com/Ocean-Industries-Concept-Lab/openbridge-webcomponents 视觉参考)。引 IEC 62288 符号/颜色。
