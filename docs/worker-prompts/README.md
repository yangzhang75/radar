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
| `W-T2.2-color-mapper.md` | 多普勒/幅度着色 ColorMapper | `ui-core` → `...uicore.color` | shared | `./gradlew :ui-core:test` |
| `W-T2.1-ppi-geometry.md` | PPI 极坐标→屏幕几何 | `...uicore.ppi` | shared | `./gradlew :ui-core:test` |
| `W-T2.3-cpa-tcpa-fusion.md` | CPA/TCPA + 雷达/AIS 融合 | `...uicore.target` | shared | `./gradlew :ui-core:test` |

## 所有员工通用规则（铁律）
1. **独立 worktree + 分支**：`git worktree add ../wt-<名> -b feat/<名>`，从 commit `3d07258` 起。
2. **只改你被分配的 package 目录**。绝不碰 `shared/`（接口已冻结，只读引用）、不碰别人的 package、不改根 `build.gradle`/`settings.gradle`（依赖已齐备）。需要改接口？停下，找编排者。
3. **接口只读**：你消费/产出的数据类型全部来自 `com.shipradar.contract`、常量来自 `com.shipradar.constants`、工具来自 `com.shipradar.util`。
4. **必须带单元测试**，且 `./gradlew :<你的模块>:test` 全绿才算完成。能对照协议文档原文示例的，写成断言。
5. **有依据才写**：数值/字段以 `桌面/雷达开发资料/` 原文为准；缺数据就留 `TODO(待张建)` + 接口预留，**不许臆造**。
6. 交付时报告：改了哪些文件、测试结果、遇到的阻塞、对接口的任何疑问。
7. **合规追溯（型式认证强制）**：每个实现标准要求的类/函数，KDoc 注释中标注对应标准条款（如 `// IEC 62388 §6.x` / `// IEC 61162-1 ED6 §8.x` / `// IMO A.823 §x`）。精确实现标准数值，缺具体值时 `TODO(待标准: <标准号 §条款>)` 占位并报告，**不许近似或臆造**。**不要直接改 `docs/合规追溯矩阵.md`**（多人改同一文件会冲突）——把你落实的条款行写进交付报告，由编排者统一并入矩阵。原文 PDF 在 `桌面/雷达开发资料/标准资料/`。

> 第二波（待 T1.5 接口产出 / 待 Android SDK+设备决策）：T1.4 450 传输层、T1.1 Foreground Service、T1.6 同步重连、T2.4–T2.9 Android/Compose UI、PPI 渲染面。
