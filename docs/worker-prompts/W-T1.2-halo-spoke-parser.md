你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.2：HALO 辐条解析 → EchoSpoke**。

## 上下文
通讯模块从组播 236.6.7.8:6678 收到原始 HALO 图像 UDP 包，你负责把字节解析成契约里的 `com.shipradar.contract.EchoSpoke`（极坐标采样数组），供 PPI 渲染消费。协议原文：`~/Desktop/雷达开发资料/通讯协议/雷达天线端协议文档-HALO.docx` §辐条(Spoke)分配。合规追溯 ID：HALO-01。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-image -b feat/comms-image`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.halo.image`**。不碰 `shared/`、其他 package、根 gradle。
3. 输出类型用 `com.shipradar.contract.EchoSpoke` / `SampleEncoding`；角度换算用 `com.shipradar.util.Angles`。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。
5. 字段以协议文档为准，缺则 `TODO`，不臆造。

## 实现要求
实现纯函数式解析器（无 socket、无 Android）：输入 `ByteArray`（一个 UDP 包，可能含多个辐条），输出 `List<EchoSpoke>`。
- 辐条头 24 字节，**小端、位域**，字段顺序/位宽：
  ```
  spokeLength_bytes:12, 预留:4, sequenceNumber:12, sampleEncoding:2, 预留:2,
  nOfSamples:12, bitsPerSample:4, rangeCellSize_mm:16,
  spokeAzimuth:13, 预留:1, bearingZeroError:1, 预留:1, spokeCompass:14, trueNorth:1, compassInvalid:1,
  rangeCellsDiv2:16, 预留:16 ×5
  ```
- 数据区：`spokeLength_bytes - 24` 字节，`bitsPerSample`（典型4）位/采样，**4-bit 解包：低索引=低有效位的字节填充**，解成每元素一个 0..15 的 byte 放入 `EchoSpoke.samples`。
- 映射到 EchoSpoke：
  - `azimuthDeg = Angles.rawAzimuthToDeg(spokeAzimuth)`（>4095 映射到 4095）。
  - `headingDeg`：`compassInvalid==1` 时为 `null`，否则 `Angles.rawAzimuthToDeg(spokeCompass)`。
  - `trueNorth`、`bearingZeroError`、`sequenceNumber`、`rangeCellSizeMm`、`rangeCellsDiv2` 直填。
  - `encoding`：sampleEncoding → AMPLITUDE 或 DOPPLER（接近=采样15/远离=14 的语义仅是着色约定，编码位本身映射到枚举）。
- 鲁棒性：包长不足/字段越界 → 跳过该辐条并可计数，不抛崩溃（VPN 下会丢包/截断）。over-scan=1.8：采样 0~569 为量程内，其余为过扫描——**解析阶段全部保留**，是否裁剪交给渲染层（注释说明）。

## 验证
- 单元测试：手工构造已知字节 → 解析 → 断言每字段（含一个文档典型值：spokeLength=536, nOfSamples=1024, bitsPerSample=4）。
- 与 T0.3 发生器对接最佳：若 T0.3 已合入，喂它的包断言往返；否则自造字节。
- 边界测试：spokeAzimuth>4095、compassInvalid、截断包。

## 交付报告
改动文件、测试结果、HALO-01 追溯行更新、疑问。
