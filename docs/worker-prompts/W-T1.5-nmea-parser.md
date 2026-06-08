你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.5：IEC 61162-1 语句解析**。

## 上下文
通讯模块从串口/网络收到 NMEA/IEC 61162-1 文本语句，你负责解析成契约里的 `OwnShipData`、`TrackedTarget`(AIS/雷达目标)、`AlarmEvent` 等。**这是认证证据链的一环（SENS-01）**，必须逐字段对照标准、带校验和、坏包丢弃。标准原文：`~/Desktop/雷达开发资料/标准资料/`（注：61162-1 ED6 PDF 在仓库根目录 `80-1093-IEC 61162-1 ED6_2023_FDIS.pdf`），第8章逐句格式。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-serial -b feat/comms-serial`（从 `3d07258`）。
2. **只改 `comms-core/` 下、package `com.shipradar.comms.iec61162`**。不碰 shared、其他 package、根 gradle。
3. 输出类型用 `com.shipradar.contract.*`、角度/换算用 `com.shipradar.util`。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。
5. **送认证**：每个语句解析函数 KDoc 标注 `IEC 61162-1 ED6 §8.x` 条款号；字段格式以标准原文为准，缺具体格式 `TODO(待标准: 61162-1 §x)`，不臆造。**勿直接改矩阵文件**；把 SENS-01 落实的条款行写进交付报告，编排者并入。

## 实现要求
设计一个语句解析入口：`fun parse(raw: String): ParsedSentence?`（先校验 `$`/`!` 起始、`*` 校验和；失败返回 null 并可计数）。每种语句一个解析器，逐字段：
- **船首向/姿态**：HDT(真船首向)、THS、HDG、ROT。
- **船位/航向航速**：GGA、GLL、GNS、RMC、VTG(COG/SOG)、VBW、OSD、ZDA(UTC)。
- **AIS**：VDM(周边船)、VDO(本船)、SSD、VSD（VDM/VDO 含 6-bit AIS 装甲解码 → 至少解出 MMSI/经纬度/COG/SOG/航首；复杂字段可分阶段，先 TODO 标注）。
- **目标**：TTM、TTD、TLL、TLB、RSD。
- **报警/显示**：ALR/ALC/ALF/ACN/ARC、HBT(心跳)、DDC(调光)、TXT。
- 校验和：异或 `$`与`*`之间字符，比对十六进制；talker ID 容错（如 GP/GN/HE/HC…）。
- 映射：位置类→`OwnShipData`，目标类→`TrackedTarget`（source 区分），报警类→`AlarmEvent`（标准报警ID）。**先实现 HDT/GGA/RMC/VTG/VDM/TTM/ALF 这7条主力**（覆盖本船+目标+报警），其余建框架+TODO。

## 验证
- 单元测试：每条用**标准/真实样例语句**断言解析结果（含校验和正确与错误两种）；坏包/截断/未知 talker 的丢弃行为。
- NemaStudio（`~/Desktop/雷达开发资料/NemaStudio136Setup.msi`，Windows 程序）可生成样例供参考；测试里用文本样例即可，不需运行它。

## 交付报告
改动文件、测试结果、SENS-01 追溯行（已落实的 §条款）、AIS 装甲解码等延后项清单、疑问。
