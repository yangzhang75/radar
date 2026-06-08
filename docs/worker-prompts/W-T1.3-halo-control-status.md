你是船用雷达安卓显控项目（送 IMO CAT 1 型式认证）的员工 AI，负责任务 **T1.3：HALO 控制命令编码 + 状态消息解析**。

## 上下文
UI 通过契约下发 `RadarCommand`/`TrackCommand`，你把它们编码成 HALO 控制信道字节（236.6.7.10:6680）；同时把雷达状态信道字节（236.6.7.9:6679）解析成 `RadarStatus`。协议原文：`~/Desktop/雷达开发资料/通讯协议/雷达天线端协议文档-HALO.docx` §雷达控制 / §雷达状态。合规追溯 ID：HALO-02、HALO-03。

## 工作规则（铁律）
1. 独立 worktree：`git worktree add ../wt-comms-ctl -b feat/comms-ctl`（从 `3d07258`）。
2. **只改 `comms-core/` 下两个 package：`com.shipradar.comms.halo.control`（编码）、`com.shipradar.comms.halo.status`（解析）**。不碰 shared、其他 package、根 gradle。
3. opcode 用 `com.shipradar.constants.HaloOpcodes`；浮点用 `com.shipradar.util.HaloFixedPoint`（Q12）；输入/输出类型用 `com.shipradar.contract.*`。
4. 必须带单元测试，`./gradlew :comms-core:test` 全绿。
5. 命令格式以文档原文为准，缺则 `TODO`，不臆造。

## 实现要求
### A. 控制命令编码器（control 包）
`fun encode(cmd: RadarCommand): ByteArray`，**小端**。逐条对照文档：
- Power `00C1`(bool)、Transmit `01C1`、Rotate `02C1`、TimedTransmit `0CC1`、SetRange `03C1`(uint32 m, 最小15)。
  - **对照断言**：6nm → `03C1 10B2 0100`（即 11112 m，小端）。
- Gain/Sea/Sidelobe/Rain 共用 `06C1`：(type, mode, level)；type 0增益/2海浪/4雨雪/5旁瓣；mode 0手动/1自动，海浪 0手动/1港湾/2近海。
  - **对照断言**：海浪近海值100 → `06C1 0200 0000 0200 0000 64`。
- FTC `07C1`、IR `08C1`(0~3)、本地IR `0EC1`、噪音抑制 `21C1`(0~2)、目标分离 `22C1`、目标扩展 `09C1`、目标加速 `0AC1`(0~2)、快速扫描 `0FC1`、海况 `0BC1`。
- 看门狗 `A1C1`（无参，~8s 周期由 T1.6 调度，你只负责编码）。
- 报警圈 `90C1` 子命令：开关`0100`(zone,bool)、设置`0200`(zone,startR_m,endR_m,bearing_0.1°,width_0.1°)、灵敏度`0300`(0~255)、报警方式`0400`(zone,type 0离开/1进入/2both)。
- 高级：转速 `05CB 0300 0000`(uint rpm 10~36)；最小SNR/视频光圈/各STC削减·速率 `00CB Fx00 0000` + Q12 float。
  - **对照断言**：SNR 98.351 → `00CB F100 0000 9E25 0600 …`；用 `HaloFixedPoint.encodeQ12LeBytes`。
- 安装校正 `40C1/04C1/05C1/30C1`、查询 `01C2~05C2/08C2`、恢复出厂 `04C3`。
- `TrackCommand`（捕获/取消）：若文档未给字节格式 → `TODO(待张建/待协议补充)` + 接口预留，不臆造。

### B. 状态消息解析器（status 包）
`fun parseStatus(bytes: ByteArray): RadarStatusUpdate`（按消息头分发）。逐条对照文档：
- 模式 `01C4`：状态(0关/1待机/2发射/3预热/4无扫描机/5检测扫描机)、定时发射状态、预热时间(s)、定时计数(s)。
- 设置 `02C4`：量程(**cm→m 转换**)、增益类型/值、海浪方式/值、雨雪、FTC、IR、目标扩展/加速、报警圈1/2 全字段（方位参考/起止量程/中心方位0.1°/宽度0.1°/报警类型/是否触发）。
- 扩展设置 `08C4`：STC曲线、本地IR、快速扫描、旁瓣方式/值、转速(rpm×10)、干扰抑制、目标分离。
- 报警状态 `00C6`、雷达错误 `10C6`（13个错误枚举，见文档/合规矩阵）。
- 输出映射到 `com.shipradar.contract.RadarStatus`（含 `GuardZoneStatus` 列表、枚举 `RadarPowerState/SeaMode/SeaState`）。

## 验证
- 单元测试：**逐条命令字节对照文档示例断言**（上面标了的几条是硬验证点）；状态解析用构造字节往返。
- 量程 cm/m、0.1° 角度、Q12 浮点的换算都要有测试。

## 交付报告
改动文件、测试结果（特别是对照文档的硬断言）、HALO-02/03 追溯行更新、文档里不清晰处的疑问。
