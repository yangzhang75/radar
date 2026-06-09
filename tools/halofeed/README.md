# halofeed — HALO 假数据发生器 (T0.3 + W5-D)

为**真机离线联调**发出合法的组播 UDP：HALO **图像辐条包**为主，外加 **01C4 状态包**、**目标占位包**、**本船 NMEA**；并支持**录制/回放**以做可重复测试。供通讯模块（T1.2 解析、T1.4 NMEA、T2.x 渲染/着色）在没有真实雷达时自测。

来源：`雷达天线端协议文档-HALO.docx` §辐条(Spoke)分配、§协议、§概观、§状态信息。
不硬编码地址：复用 `com.shipradar.constants.HaloEndpoints`、`com.shipradar.constants.Iec450Groups`、`com.shipradar.util.Angles`、`com.shipradar.contract.*`。

## 通道一览
| 通道 | 端点 | 内容 | 依据 |
|---|---|---|---|
| 图像 | `HaloEndpoints.IMAGE` 236.6.7.8:6678 | 辐条包（每包 1~2 辐条） | 文档完整规定 ✅ |
| 状态 | `HaloEndpoints.STATUS` 236.6.7.9:6679 | 01C4 模式状态（每 2s） | 文档规定，帧头为推断 ⚠️ |
| 目标 | `HaloEndpoints.TARGET` 236.6.7.18:6688 | **占位**目标包（magic `FAKETGT`） | 布局文档缺，`TODO(待协议)` ⚠️ |
| 本船 | `Iec450Groups.SATD/NAVD` 239.192.0.3/4 | NMEA-0183（HDT/ROT/GGA/VTG） | NMEA 标准 ✅；450 封装为 TODO ⚠️ |

## 运行

```bash
# 持续发全部通道（默认 24 rpm、3 NM、幅度模式）
./gradlew :tools:halofeed:run

# 多普勒、48 rpm、6 NM，发 5 圈后停
./gradlew :tools:halofeed:run --args="--doppler --rpm=48 --rangeMeters=11112 --scans=5"

# 只发图像（关其它通道）
./gradlew :tools:halofeed:run --args="--no-status --no-target --no-ownship"

# 录制一段（跑实况并写入文件）
./gradlew :tools:halofeed:run --args="--record=/tmp/feed.bin --scans=3"

# 回放（任意通道，复现原始时序；--no-paced 则尽快发）
./gradlew :tools:halofeed:run --args="--replay=/tmp/feed.bin"

./gradlew :tools:halofeed:run --args="--help"   # 全部参数
```

### 主要参数
`--rpm --samples --rangeMeters --rangeCellSizeMm --rangeCellsDiv2 --doppler --spokesPerPacket --ttl --iface --scans`（图像，见 `--help`）
`--no-image/--no-status/--no-target/--no-ownship --targets=N --statusPeriod/--targetPeriod/--ownshipPeriod`（通道）
`--record=FILE --replay=FILE --no-paced`（录制/回放）

## 包格式

**图像辐条**（与文档一致，小端位域，§概观「字节对齐和小端法」）：
```
UDP 载荷 = 8 字节帧前缀 + N 辐条（背靠背）；辐条 = 24 字节头 + 数据(nOfSamples×4-bit)
典型 = 24 + 512 = 536 B；默认每包 2 辐条 → 8 + 2×536 = 1080 B ≤ 1400（蒲公英 MTU）
word0: spokeLength_bytes:12 | _:4 | sequenceNumber:12 | sampleEncoding:2 | _:2
word1: nOfSamples:12 | bitsPerSample:4 | rangeCellSize_mm:16
word2: spokeAzimuth:13 | _:1 | bearingZeroError:1 | _:1 | spokeCompass:14 | trueNorth:1 | compassInvalid:1
word3: rangeCellsDiv2:16 | _:16 ; word4/word5 全预留
```
采样 4-bit：低索引存低半字节；0=无信号，15=最强。**测试图样**：艏向标记(0°整条高亮) + 同心距离环(每128采样) + 45°/135°/270° 固定目标块（多普勒下交替接近15/远离14）。

**录制文件格式**（`HALOFEEDREC1\n` + 逐条 `tMicros|addr|port|len|payload`，容器大端、payload 原样）。通道无关 → 图像/状态/目标/本船统一录制回放。

## 真机 / 模拟器如何接收
所有数据都是标准组播 UDP。接收端须**加入对应组播组**并绑定端口：

- **本项目 comms 模块**：直接订阅相应组播组即可（与真机一致）；本工具只是把真机换成发生器。
- **命令行抓包**：`tcpdump -i en0 -X 'udp port 6678 or udp port 6679 or udp port 6688'`。
- **Wireshark**：过滤 `udp.port==6678`；需主机已加入组（或用混杂模式 + 同网段）。
- **快速验证脚本**（非本仓代码，置于 `/tmp`）：
  - `halo_listen.py` 加入 236.6.7.8 收一个图像包并按 `spokeLength_bytes` 步进解辐条头。
  - `halo_recsum.py feed.bin` 统计录制文件各通道数据报数。
- **跨网段 / 蒲公英 SD-WAN**：默认 `--ttl=1` 只在本网段；跨段需 `--ttl` 调高且组播可路由；必要时用 `--iface=enX` 指定出口网卡。
- **接收端注意**：组播需 `SO_REUSEADDR` + `IP_ADD_MEMBERSHIP`；macOS/Linux 上发收同机时，出口网卡须有组播路由（默认 en0 即可环回本机监听者）。

## 自测
```bash
./gradlew :tools:halofeed:test   # 53 项全绿
```
覆盖：辐条头位域往返、4-bit 打包、包尺寸/按长度步进、图样、配置解析、01C4 状态布局、目标占位往返、NMEA 校验和（对标准例 *47）、**录制↔回放字节级一致**（实况录制再回放与原流逐包相同）、通道开关。
单测把辐条按相同位域**解出来**校验往返（预演 T1.2，但**不是** T1.2 的解析器）。

## 待确认项（已在代码标注）
- `TODO(待张建)` 图像帧前缀 `0100 0000 0020 0002` 字段语义未给；`0x20`(=32) 疑为 `Spoke[32]` 容量而非实际计数 → 原样发出、不当动态计数。**建议 T1.2 按 `spokeLength_bytes`+数据报长度遍历辐条。**
- `TODO(待张建)` `sampleEncoding` 的 `SmpEncode_Amplitude/Doppler` 无数值 → 假设 AMPLITUDE=0、DOPPLER=1。
- `TODO(待协议)` **状态**：01C4 在状态通道需自识别，故推断前置 2 字节命令字(01 C4)、字段按文档每行 uint32 LE；02C4/08C4 未做。确认帧头有无/宽度/字节序。
- `TODO(待协议)` **目标**：HALO 目标跟踪载荷布局文档未给 → 发 magic `FAKETGT` 的**清晰占位**中间格式（真机解析器会拒收，不会误判）。待真布局确认后替换包体。
- `TODO(待协议)` **本船**：发原始 NMEA-0183 句子（校验和正确）；未套 61162-450 ED3 的 `UdPbC\0`/TAG 块封装，由 T1.4 按 450 标准确认是否需要。
- 发生器无艏向传感器，图像辐条 `compassInvalid=1`。
