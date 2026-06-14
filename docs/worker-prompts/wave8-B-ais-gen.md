# 员工任务 W8-B：AIS 多目标语句生成核心(!AIVDM 编码)

## 你是谁
船用雷达项目(IMO CAT1)员工 AI。**纯 JVM**。说中文。AIS 报文位布局用**公开通行的 ITU-R M.1371 常见报文定义**(type 1/3/5/24,业界公开),编码外壳(!AIVDM + 6-bit armoring)属 NMEA/61162-1。

## 铁律
1. **同步主线**:`git checkout -B feat/w8-ais main`。worktree 在 `~/.superset/worktrees/radar/` 下,**绝不建到桌面**。
2. **只新建**:`tools/simcore/src/main/kotlin/com/shipradar/sim/ais/` + 对应 test 目录(package `com.shipradar.sim.ais`)。**不改任何已有文件**(含 build.gradle、settings、`sim/nmea` 是 W8-A 的)。
3. 不依赖 Android。

## 任务
**多 AIS 目标**模拟生成。每个目标可设静态 + 动态信息,输出 `!AIVDM` 句(支持多分片)。

### 数据模型(可设字段)
- 静态:**MMSI(9 位)**、**船名 name**、呼号 callsign、IMO、**船舶类型 shipType**、尺寸(船首/尾/左/右,即 to-bow/stern/port/starboard)。
- 动态:**位置 lat/lon**、**航速 SOG**、**航向 COG**、真航向 heading、转向率、导航状态 navStatus。
- **国籍**:由 MMSI 前 3 位(MID)推出国家。提供 `fun countryOf(mmsi: Int): String?`,内置一份常见 MID→国家表(中国 412/413/414、美国 338/366-369、英国 232-235、日本 431/432、韩国 440/441、新加坡 563-565、巴拿马 351-373… 不全可标 TODO,常见的要有)。

### 编码
- `type 1/3`(位置报告):MMSI、navStatus、ROT、SOG、经纬、COG、heading、时间戳 → 168 bit。
- `type 5`(静态与航次):MMSI、IMO、callsign、name、shipType、尺寸、目的地 → 424 bit,**需 2 分片** AIVDM。
- (可选)`type 24` A/B(类 B 静态)。
- **6-bit ASCII armoring**:6-bit 值 → ASCII(0–39→'0'..'W' 即 +48,40–63→'`'.. 即 +56),即标准 AIS payload 字符表;`fill bits` 正确;`!AIVDM,分片数,分片号,消息ID,信道(A/B),payload,fillbits*HH`。
- 校验和同 NMEA(`!`与`*`间 XOR)。

### 测试(关键:别自证自)
- 至少对 **type 1 + type 5** 各给一个**已知向量**(可用公开 AIS 样例,或自编一个 MMSI/name 然后断言 payload 6-bit 解回与输入一致——往返测试)。
- `countryOf` 几个 MID 断言(412→中国 等)。
- 6-bit armor 编解往返测试。

## 完成
`./gradlew :tools:simcore:test` 全绿;提交 `feat(simcore): AIS 多目标 !AIVDM 生成 (W8-B)`;回报分支、文件、`AisTarget` 模型 + 生成入口签名(供 GUI 增删目标、调位置/航速)。
