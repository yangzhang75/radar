# 第五波派工 · 纯软件补全（不需真机、不缺标准的 D 类）

> 目标:把"不依赖真机、也不缺标准原文"的剩余软件做完(认证缺口清单 D 类)。**缺标准的(IHO S-52 色度、ITU-R M.1371 AIS 静态)与需硬件的(HALO 目标 wire/320mm)不做,留 TODO。**
> 复用员工池;`git worktree add ../wt-<任务> -b feat/<任务> main`;只改本范围;绿了(对应模块 test + 必要时 :app:assembleDebug/lintDebug)立即 commit;标 §条款;缺口报告里列出。

| 任务 | 会话 | 范围 |
|---|---|---|
| W5-A 61162-1 次要语句补全 | **past-freon** | `comms-core/iec61162` |
| W5-B 报警命令消费(ACN/ARC) | **carefree-drip** | `comms-core/alarm` |
| W5-C 昼/黄昏/夜 调色暗化 | **absorbed-stetson** | `ui-core/color` + `app/alarm/AlarmColors.kt` |
| W5-D halofeed 目标/状态假包+回放 | **observant-aura** | `tools/halofeed` |

---
## W5-A → past-freon（`comms-core/iec61162`）
实现 61162-1 ED6 第8章中**已在库标准、当前留 TODO 的次要语句**(逐字段+单位+校验,映射到 `contract.*`,带标准样例断言):
TLB(§8.3.102 目标标签)、TLL(§8.3.103 目标经纬度)、OSD(§8.3.75 本船数据)、RSD(§8.3.87 雷达系统/EBL/VRM)、VBW(§8.3.113 双地速/水速)、HDG(§8.3.51 磁航向→真航向,用偏差/磁差)、ZDA(§8.3.121? 实为日期/UTC,与 RMC 融合填 utcMillis,§8.3.14/§8.3.42)、DDC(§8.3.26 调光)、HBT(§8.3.49 心跳监督)、ALR/ALC(§8.3.13/§8.3.15 报警,映射 62923-2 ID→优先级)。
**ACN/ARC(§8.3.7/§8.3.17)只做解析**成意图对象(供 W5-B 消费),不在此实现状态机。
**跳过**:VDM 类型5/24、VSD/SSD 的 AIS 静态字段解码 → 保留 `TODO(待标准: ITU-R M.1371-5)`(缺标准)。
验证:每句标准样例断言 + 校验和;`:comms-core:test` 全绿。

## W5-B → carefree-drip（`comms-core/alarm`）
让 `BamAlarmManager` **消费报警命令**:ACN(确认)/ARC(责任转移)→ 驱动状态机相应转移(ACTIVE_UNACK→ACTIVE_ACK、→ACTIVE_RESP_TRANSFERRED),并产出对外回执意图(ALC/ALF 回复)。补 `RESPONSIBILITY_TRANSFERRED` 全链路。输入用 W5-A 产出的命令意图类型或 `contract.AlarmEvent`(对接口开发,不依赖 W5-A 完成)。引 IEC 62923-1 §6.3。补转移测试。`:comms-core:test` 绿。

## W5-C → absorbed-stetson（`ui-core/color` + `app/alarm/AlarmColors.kt`）
实现 **DUSK / NIGHT 调色板的暗化**(DAY 已按 62288 精确):按 IEC 62288 §4.5/§7.2 的昼夜亮度递减机制给出 DUSK/NIGHT 的回波色阶与报警色(夜间低亮红黑系、保护暗适应)。**精确色度(chromaticity)仍委托 IHO S-52** → 那部分留 `TODO(待标准: IHO S-52)`,但暗化曲线/相对亮度按 62288 机制落实并断言。顺手清理 `RangeRings.kt` 里"62288 缺失"的**过期 TODO**(62288 已入库)。`:ui-core:test`+`:app:assembleDebug` 绿。

## W5-D → observant-aura（`tools/halofeed`）
扩展假数据发生器,为**真机离线联调**铺路:① 增加**目标/状态假包**(目标通道布局未知 → 用一个清晰的占位/中间结构发,并注明 `TODO(待协议)`,主要保证图像+本船+状态可回放);② 增加**回放/录制模式**(可从文件回放一段辐条序列,便于可重复测试);③ README 写明真机/模拟器如何接收。`:tools:halofeed:test` 绿。
