你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T2.8a：BAM 报警状态机（IEC 62923-1，纯JVM）**。

## 上下文
报警(BAM)是认证重点。你按 **IEC 62923-1** 实现报警状态机(逐态转换)与报警管理逻辑：接收报警事件、确认(ACK/ACN)、静音、纠正(rectified)、升级，符合标准状态转换。**纯逻辑，不碰 Android UI**(UI 在第二波)。标准原文：`~/Desktop/雷达开发资料/3】IEC62923-1-2018.pdf`(状态机) + `4】IEC 62923-2-2018.pdf`(报警ID/语句)。合规追溯 ID：ALRM-01。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-alarm -b feat/alarm`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.alarm`**。不碰 shared、其他 package、根 gradle。
3. 用 `contract.AlarmEvent/AlarmPriority/AlarmState`。**注意：`contract.AlarmState` 当前枚举是临时占位**——按 62923-1 实现状态机时，若需要不同/更多状态，**不要改 shared**，在你的包内用你自己的完整状态枚举实现，并在报告里**提出 contract.AlarmState 的最终定义建议**，由编排者并入 shared(受控变更)。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。**绿后立即 commit，不要等批准。**
5. **送认证**：每个状态/转移 KDoc 标注 `IEC 62923-1 §x`；标准未明确处 `TODO(待标准:62923-1 §x)`。ALRM-01 条款行写进交付报告(勿改矩阵文件)。

## 实现要求（纯状态机）
- 从 62923-1 提取 BAM **报警状态机**：状态(如 active-unacknowledged / active-silenced / active-acknowledged / rectified-unacknowledged / normal 等以标准为准) + 事件(raise / acknowledge / silence / rectify / 升级超时) + 合法转移；非法转移拒绝。
- **优先级/类别**：emergency-alarm / alarm / warning / caution(62923-1/IMO BAM)；静音超时/升级规则。
- **标准报警ID**接入(62923-2)：3044 CPA/TCPA、3052 目标丢失、3048 新目标、3042/3043 容量、3015 传感器失效、3002 通信中断 —— 提供 ID→优先级/文案映射表，标注来源。
- 输出：给定当前状态+事件 → 新状态 + 应发的对外动作(如 ACN 确认需经 comms 下发——只产出意图，不下发)。纯函数式。
- **ACK/silence 的对外命令**(ALF/ACN/ARC)只产出"意图对象"，实际 61162 编码/下发交给 comms 通道(不在你范围，预留接口)。

## 验证
- 单测：覆盖 62923-1 每条合法转移 + 典型非法转移被拒；静音/升级超时(注入模拟时间)；几个标准ID(3044/3002)的优先级/文案断言。

## 交付报告
改动文件、测试结果、**contract.AlarmState 最终定义建议**(供编排者并入 shared)、ALRM-01 已落实 §条款、疑问。
