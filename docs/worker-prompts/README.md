# 员工派工单 — 第一波（可全部并行）

> 编排者维护。每个 `W-*.md` 是一份**可直接整段粘贴给员工 AI** 的 prompt。
> 全部基于已冻结的接口契约 commit `3d07258`，互不重叠（各占一个 package），且**纯 JVM 可单元测试**，
> 不依赖 Android SDK —— 因此可在 Mac 上立即并行开工并自验，无需等设备/SDK 决策。

> ⚠️ **本项目送型式认证（CAT 1，最高合规标准）。** 所有实现必须可追溯到标准条款，精确落实（不许近似），
> 并为认证留证据。见 `../合规追溯矩阵.md`。这条凌驾于"省着用标准"之上——触及标准的功能必须当场提取原文逐条实现。

## 第一波任务（建议同时派发）
| Prompt 文件 | 任务 | 独占 package | 依赖 | 自验命令 |
|---|---|---|---|---|
| `W-T0.3-halofeed.md` | HALO 假数据发生器 | `tools/halofeed` → `com.shipradar.halofeed` | shared 常量 | `./gradlew :tools:halofeed:test` + 手动收包 |
| `W-T1.2-halo-spoke-parser.md` | HALO 辐条解析→EchoSpoke | `comms-core` → `...comms.halo.image` | shared | `./gradlew :comms-core:test` |
| `W-T1.3-halo-control-status.md` | 控制命令编码 + 状态消息解析 | `...comms.halo.control` / `...comms.halo.status` | shared | `./gradlew :comms-core:test` |
| `W-T1.5-nmea-parser.md` | 61162-1 语句解析 | `...comms.iec61162` | shared | `./gradlew :comms-core:test` |
| `W-T1.4-iec450-transport.md` | 61162-450 传输层(出 raw 语句串) | `...comms.iec450` | shared | `./gradlew :comms-core:test` |
| `W-T2.2-color-mapper.md` | 多普勒/幅度着色 ColorMapper | `ui-core` → `...uicore.color` | shared | `./gradlew :ui-core:test` |
| `W-T2.1-ppi-geometry.md` | PPI 极坐标→屏幕几何 | `...uicore.ppi` | shared | `./gradlew :ui-core:test` |
| `W-T2.3-cpa-tcpa-fusion.md` | CPA/TCPA + 雷达/AIS 融合 | `...uicore.target` | shared | `./gradlew :ui-core:test` |

## 员工复用约定（重要）
**员工会话是稳定复用的**——干完一个任务的会话会闲置，直接把下一波任务派给它，不要每波开新会话（浪费）。固定 7 人池(observant-aura / rounded-fireplace / thorn-poppyseed / past-freon / vivacious-clover / absorbed-stetson / few-basin)，编排者按**领域匹配**复用：让做过同模块的人接同域新任务，复用其上下文。
- **续作类任务必须回原会话**：如 `W-T2.2refine-*` 必须给 `absorbed-stetson`(它在 `feat/ui-color` 上续写)。
- 第二波·免设备的领域匹配建议见本文件末尾派工表的"复用谁"列。

## 所有员工通用规则（铁律）
1. **独立 worktree + 分支**：`git worktree add ../wt-<名> -b feat/<名>`，从 commit `3d07258` 起。
2. **只改你被分配的 package 目录**。绝不碰 `shared/`（接口已冻结，只读引用）、不碰别人的 package、不改根 `build.gradle`/`settings.gradle`（依赖已齐备）。需要改接口？停下，找编排者。
3. **接口只读**：你消费/产出的数据类型全部来自 `com.shipradar.contract`、常量来自 `com.shipradar.constants`、工具来自 `com.shipradar.util`。
4. **必须带单元测试**，且 `./gradlew :<你的模块>:test` 全绿才算完成。能对照协议文档原文示例的，写成断言。**全绿后立即 `git add -A && git commit`（不要等批准——不提交=编排者看不到、无法合并/验收）。**
5. **有依据才写**：数值/字段以 `桌面/雷达开发资料/` 原文为准；缺数据就留 `TODO(待张建)` + 接口预留，**不许臆造**。
6. 交付时报告：改了哪些文件、测试结果、遇到的阻塞、对接口的任何疑问。
7. **合规追溯（型式认证强制）**：每个实现标准要求的类/函数，KDoc 注释中标注对应标准条款（如 `// IEC 62388 §6.x` / `// IEC 61162-1 ED6 §8.x` / `// IMO A.823 §x`）。精确实现标准数值，缺具体值时 `TODO(待标准: <标准号 §条款>)` 占位并报告，**不许近似或臆造**。**不要直接改 `docs/合规追溯矩阵.md`**（多人改同一文件会冲突）——把你落实的条款行写进交付报告，由编排者统一并入矩阵。原文 PDF 在 `桌面/雷达开发资料/标准资料/`。

## 第二波 · 免设备（现在可并行派，纯 JVM 可单测，无需 Android SDK）
| Prompt | 任务 | 独占 package | 备注 |
|---|---|---|---|
| `W-T1.4-iec450-transport.md` | 61162-450 传输层 | `comms.iec450` | 出 raw 语句串，喂 T1.5 |
| `W-T1.1a-handshake-watchdog.md` | HALO 握手+看门狗逻辑 | `comms.halo.handshake` | 连接状态机/看门狗策略(纯逻辑) |
| `W-T1.6a-sync-core.md` | 多路同步/缓冲/重连逻辑 | `comms.sync` | 适配 VPN 高延迟/丢包 |
| `W-HALO-target.md` | HALO 目标通道建模+解析 | `comms.halo.target` | docx 无 wire 格式→建模+stub(待真机) |
| `W-T2.8a-alarm-state-machine.md` | BAM 报警状态机(62923-1) | `comms.alarm` | 认证重点；提 AlarmState 定稿建议 |
| `W-T2.2refine-62288-colors.md` | 用 62288 精修着色 | `uicore.color`(同分支) | 回炉 absorbed-stetson/feat-ui-color |

> 第三波（待 Android SDK + 设备决策 §6 #3）：T1.1 Foreground Service、T1.6 socket接线、T2.4–T2.9 Android/Compose UI、PPI 渲染面（消费 T2.1 几何 + T2.2 着色）。
> 编排者待办（不派员工）：用 62923-1 定稿 `contract.AlarmState`（受控接口变更）；持续合规矩阵consolidation。
