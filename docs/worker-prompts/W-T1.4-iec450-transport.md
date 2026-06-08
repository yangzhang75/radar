你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.4：IEC 61162-450 传输层解析**。

## 上下文
通讯模块从 61162-450 LAN 组播组收到 UDP 帧，帧内封装 61162-1 文本语句。你**只负责传输层**：解析 450 UDP 帧 → 提取内嵌的原始语句串 + 来源/组标签 + 去重/排序信息，输出一个"已打标签的原始语句"流。**不负责语句内容解析**（那是 T1.5 `iec61162` 的事——你只产出 raw NMEA 字符串，绝不解析字段，避免与 T1.5 重叠）。合规追溯 ID：SENS-02。标准原文：`~/Desktop/雷达开发资料/80-1094-IEC 61162-450 ED3_2023_FDIS.pdf`。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-450 -b feat/comms-450`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.iec450`**。不碰 shared、不碰 `iec61162`（T1.5）、其他 package、根 gradle。
3. 组地址用 `com.shipradar.constants.Iec450Groups`（TGTD/SATD/NAVD/BAM1/2/CAM1/2）。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。
5. **送认证**：帧结构/字段 KDoc 标注 `IEC 61162-450 ED3 §x`；缺具体格式 `TODO(待标准: 450 §x)`，不臆造。**勿直接改矩阵文件**；把 SENS-02 落实的条款行写进交付报告，编排者并入。

## 实现要求
纯函数式（无 socket、无 Android）：输入 `ByteArray`（一个 450 UDP 帧）+ 来源组，输出结构化结果。
- 解析 **450 传输层帧格式**（按 ED3 标准）：UDP 载荷 ≤1472B；含传输头（如标签块/datagram 标识、源标识、序号）+ 封装的 61162-1 语句负载。逐字段按标准提取。
- 输出：`data class TaggedSentence(val group: Iec450Group, val sourceId: String?, val sequence: Int?, val rawSentence: String)`（或类似——在你的 package 内定义）。`rawSentence` 是完整 `$..*hh` / `!..*hh` 文本，**原样传出不解析**。
- **校验与丢弃**：UDP>1472、校验失败、格式非法 → 丢弃并计数，不崩溃（VPN 下丢包/截断常见）。
- **去重/重组**：450 可能多源/重传；按序号/源做去重，必要时重组分片语句（标准若定义）。
- 一个帧可能含多条语句 → 输出 `List<TaggedSentence>`。

## 验证
- 单元测试：构造合法 450 帧（参考标准帧示例）→ 断言提取出的 rawSentence 文本与组/源标签；坏帧/超长/校验错的丢弃；多语句帧；去重。
- **不要**断言语句内字段（那是 T1.5）；只断言"取出了正确的原始语句串和标签"。

## 交付报告
改动文件、测试结果、SENS-02 已落实的 §条款行、与 T1.5 的接口约定（你输出的 TaggedSentence 形状）、疑问。
