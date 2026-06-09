# 第四波派工 · 技术债收尾 + 认证战役（复用员工池,大量并行）

> 安卓 HMI 已在 API-34 模拟器跑通(`main`)。本波两类活:① 一个内部统一重构;② 认证审计(各模块 owner 对自己已建的代码**逐条对标标准**,出证据+补数值测试+列缺口)。**全部并行、互不重叠**(各审各的模块)。

## 复用映射(粘进对应已有会话)
| 任务 | 会话 | 范围 |
|---|---|---|
| W4-A 统一 RadarDisplaySettings | **rounded-fireplace** | `app/databar` + `app/control`(受控跨包,见下) |
| W4-B 62388 显示审计 | **vivacious-clover** | `ui-core/ppi` + `app/ppi`(只加测试/文档/注释) |
| W4-C 62288 颜色+符号审计 | **absorbed-stetson** | `ui-core/color` + `app/framework` |
| W4-D A.823 ARPA 审计 | **few-basin** | `ui-core/target` |
| W4-E 62923-1 报警审计 | **carefree-drip** | `comms/alarm` |
| W4-F 61162-1 语句审计 | **past-freon** | `comms-core/iec61162` |

## 通用规则
1. 复用你的会话;`git worktree add ../wt-<任务> -b feat/<任务> main`。
2. **只改你列出的范围**;不碰 `MainActivity`/`RadarScreen`、别人的包。审计类任务**优先只加测试/KDoc 条款标注**,尽量不改已验证的逻辑(改逻辑要在报告里说明)。
3. 构建对应模块全绿(纯 JVM `:<m>:test`;Android `:app:assembleDebug`+`:app:lintDebug`),**绿后立即 commit**。
4. **认证证据**:每条要求 KDoc 标注精确标准条款(§号),数值要求写**对照标准算例的断言测试**;标准未给精确值 `TODO(待标准:<§>)`。交付报告列出"已落实条款行 + 缺口",编排者并入 `docs/合规追溯矩阵.md`(勿直接改矩阵)。

---
## W4-A 统一 RadarDisplaySettings → rounded-fireplace
现状:`app/control/RadarDisplaySettings.kt`(字段 rangeScaleNm/motion/orientation,枚举 MotionMode{RELATIVE,TRUE})与 `app/databar/DataBarModel.kt` 里的 `RadarDisplaySettings`(字段 orientation/motionMode/vectorMode/vectorTimeMin/stabilisation,另有 MotionMode{TRUE_MOTION,RELATIVE_MOTION}/VectorMode/Stabilisation)是**两个重复类型**,RadarScreen 现用适配器临时桥接。
要求:**统一为一个** `RadarDisplaySettings`(放 `app/control` 作 canonical,含全部字段:rangeScaleNm/orientation/motion/vectorMode/vectorTimeMin/stabilisation;枚举统一为一套,命名取清晰者并标 IEC 62388 §6 条款)。databar 删除自己的副本、改用 control 的;相应更新 `DataBarModel` 字段访问 + 其单测。**不要改 RadarScreen**(编排者随后去掉适配器)。范围:`app/control` + `app/databar`。验证:`:app:assembleDebug`+`:app:lintDebug`+`:app:testDebugUnitTest` 全绿。报告里给出统一后的最终类型定义。

## W4-B 62388 显示审计 → vivacious-clover
对 `ui-core/ppi`(几何)+ `app/ppi`(渲染面)逐条核对 IEC 62388 Ed.2:有效显示区≥320mm(§4.4 Table1)、强制量程档(§9.4.1.1)、距离环(§9.11.2.1)、方位刻度(§9.10.2.1)、船首线(§8.2.3.1)、换档消隐≤1扫描(§9.4.1.2)。补/确认对照测试,KDoc 标条款,列缺口(如真实 DPI/物理尺寸待真机)。

## W4-C 62288 颜色+符号审计 → absorbed-stetson
对 `ui-core/color`(回波/调色板)+ `app/framework`(主题/chrome)核对 IEC 62288 Ed.2 §4.5/§5.4 颜色与亮度、昼/黄昏/夜、可分辨性;符号若涉及则对 Annex A。把之前 `TODO(待标准:62288)` 的占位色值替换为标准精确值并断言。列缺口。

## W4-D A.823 ARPA 审计 → few-basin
对 `ui-core/target` 的 CPA/TCPA、矢量、试操船、自动捕获,逐条核对 IMO A.823(19) + GB 11711:矢量时间、CPA/TCPA 定义与精度、试操船参数、容量(IEC 62388 ≥40/40/200/240)。补 A.823 附录算例的数值断言;把"试操船/自动捕获待参数"落实或明确缺口。

## W4-E 62923-1 报警审计 → carefree-drip
对 `comms/alarm` 的 BAM 状态机,核对 IEC 62923-1 Annex G 每条状态转移(E/A/W/C 各图)与定时(静音30s/升级);确认标准报警ID(3044/3052/3048/3042/3043/3015/3002,62923-2)优先级/文案。补全转移测试,列缺口。

## W4-F 61162-1 语句审计 → past-freon
对 `comms-core/iec61162`,逐句对照 IEC 61162-1 ED6 第8章字段定义(已实现的 HDT/GGA/RMC/VTG/VDM/TTM/ALF 等)核验字段顺序/单位/校验;把延后的(如 AIS 装甲解码细节)补齐或明确列出缺口 + 用标准样例语句断言。
