你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.1a：HALO 握手(01B1/01B2) + 看门狗逻辑（纯JVM）**。

## 上下文
连接 HALO 雷达的入口是握手：客户端发 `01B1`(请求链接)，雷达回 `01B2`(允许链接)，回包含**10位序列号 + 雷达IP + 协商后的完整多播地址清单**。之后客户端须每 ~8s 发 `A1C1` 看门狗(漏发30s转待机、1min关机)。**你只做纯逻辑(编解码+连接状态机+看门狗策略)，不碰 socket/Android**(真正的组播发送在第二波的 Foreground Service 里接线)。协议原文：`~/Desktop/雷达开发资料/通讯协议/雷达天线端协议文档-HALO.docx` §握手协议 / §消息发送规则。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-handshake -b feat/comms-handshake`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.halo.handshake`**。不碰 shared、不碰其他 package(尤其 `sync` 归 T1.6、`control` 归 T1.3)、根 gradle。
3. 用 `com.shipradar.constants.HaloOpcodes`(LINK_REQUEST/LINK_ALLOW/WATCHDOG, HALO_WATCHDOG_PERIOD_MS)、`contract.LinkState`。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。**测试绿后立即 `git add -A && git commit`，不要等批准。**
5. 缺格式 `TODO(待协议)`，不臆造。补 HALO-03 追溯行写进交付报告(勿改矩阵文件)。

## 实现要求（纯函数/纯逻辑，无 IO）
- **握手编解码**：构造 `01B1` 请求包；解析 `01B2` 应答 → 一个结构(雷达序列号10位、雷达IP、协商地址清单 image/status/control/target/... 各 `Endpoint`)。字节示例见文档 §允许链接(给了完整字节样例，逐字段对照)。支持文档 §自定义：序列号/命令头/协议地址可自定义。
- **连接状态机**：`LinkState` 流转 DISCONNECTED → NEGOTIATING(发01B1) → CONNECTED(收01B2) → DEGRADED(看门狗超时/丢包)。纯函数式状态转移 + 事件输入(收到包/超时tick)。
- **看门狗策略**：纯逻辑——给定"上次发送时间/当前tick"，决定何时该发 `A1C1`(周期 `HALO_WATCHDOG_PERIOD_MS`≈8s)，以及超时判定(>30s 警告/降级)。**不真正起线程**——暴露 `nextDueAt()` / `onTick(now)` 之类纯接口，由第二波 Service 驱动。
- **手动IP兜底**(蒲公英VPN组播失败时)：提供"跳过握手、用手动配置的雷达IP+默认 `HaloEndpoints` 地址"的构造路径。

## 验证
- 单测：构造 01B1 字节断言；用文档 §允许链接样例字节解析 01B2 → 断言序列号/IP/地址清单；状态机各转移；看门狗到期/超时判定(注入模拟时间，不用真实时钟)。

## 交付报告
改动文件、测试结果、HALO-03(握手/看门狗)落实条款行、与 T1.6/Service 的边界确认、对前缀/字段的疑问。
