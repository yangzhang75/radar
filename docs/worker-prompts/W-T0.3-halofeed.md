你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T0.3：HALO 辐条假数据发生器**。

## 上下文
项目仓库已有冻结的接口契约（commit `3d07258`）。通讯模块需要在没有真实雷达时离线自测，你的发生器要按 HALO 协议发出**合法的组播 UDP 辐条包**，供 T1.2 解析器和后续联调使用。雷达协议原文在 `~/Desktop/雷达开发资料/通讯协议/雷达天线端协议文档-HALO.docx`。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-halofeed -b feat/halofeed`（从 `3d07258`）。
2. **只改 `tools/halofeed/` 下、package `com.shipradar.halofeed`**。不碰 `shared/`（只读）、不碰其他模块、不改根 gradle 文件。
3. 复用 `com.shipradar.constants.HaloEndpoints`（组播地址）、`com.shipradar.util.Angles`。不要硬编码地址。
4. 必须带单元测试，`./gradlew :tools:halofeed:test` 全绿。
5. 数值以协议文档原文为准，缺则 `TODO(待张建)`，不臆造。

## 实现要求
替换 `tools/halofeed/src/main/kotlin/com/shipradar/halofeed/Main.kt` 的 stub，实现一个可配置的发生器：
- 生成 HALO **图像辐条包**（236.6.7.8:6678）。一个 UDP 包含多个辐条；每辐条 = 24 字节头 + 数据（典型 1024 采样 × 4-bit = 512 字节，整辐条 536 字节）。**保持每个 UDP 包 ≤1400 字节**（蒲公英 VPN MTU 限制）→ 每包放 1~2 个辐条即可。
- **辐条头逐字段按协议（小端、位域）打包**，字段顺序与位宽：
  ```
  spokeLength_bytes:12, 预留:4, sequenceNumber:12, sampleEncoding:2, 预留:2,
  nOfSamples:12, bitsPerSample:4, rangeCellSize_mm:16,
  spokeAzimuth:13, 预留:1, bearingZeroError:1, 预留:1, spokeCompass:14, trueNorth:1, compassInvalid:1,
  rangeCellsDiv2:16, 预留:16 ×5
  ```
  （共 24 字节头。采样 4-bit，低索引位存低有效位/字节填充。采样 0=无信号，15=最强。）
- 模拟内容：随扫描角递增（spokeAzimuth 0→4095 一圈），生成可识别图形（如固定方位的"目标块"、距离环测试图样），便于 PPI 渲染肉眼验证。
- 支持两种 `sampleEncoding`：幅度(AMPLITUDE)与多普勒(DOPPLER，部分采样置 15=接近/14=远离)，供 T2.2 着色测试。
- 可配置：转速(辐条/秒)、量程(rangeCellSize_mm × rangeCellsDiv2)、是否多普勒。
- （可选，加分）同时发简单的目标包/状态包占位，但优先把图像辐条做对。

## 验证
- 单元测试：构造一个辐条 → 按相同位域解出来 → 字段值往返一致（这本质上预演了 T1.2 的解析，注意**不要**替 T1.2 写解析器，只验证你自己的打包正确）。
- 提供一个 `main()` 能持续发包；README 写明运行方式（`./gradlew :tools:halofeed:run`）。

## 交付报告
改动文件清单、测试结果、运行截图/日志、对协议理解的疑问。
