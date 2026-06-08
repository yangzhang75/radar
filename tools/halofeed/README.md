# halofeed — HALO 辐条假数据发生器 (T0.3)

按 HALO 协议发出**合法的组播 UDP 图像辐条包**，供通讯模块在没有真实雷达时离线自测（T1.2 解析器联调）。

来源：`雷达天线端协议文档-HALO.docx` §辐条(Spoke)分配、§协议、§概观。
不硬编码地址：组播端点复用 `com.shipradar.constants.HaloEndpoints.IMAGE`（236.6.7.8:6678）；角度换算复用 `com.shipradar.util.Angles`；编码枚举复用 `com.shipradar.contract.SampleEncoding`。

## 运行

```bash
# 持续发包（默认 24 rpm、3 NM 量程、幅度模式）
./gradlew :tools:halofeed:run

# 多普勒模式、48 rpm、6 NM，发 5 圈后停止
./gradlew :tools:halofeed:run --args="--doppler --rpm=48 --rangeMeters=11112 --scans=5"

# 帮助
./gradlew :tools:halofeed:run --args="--help"
```

### 参数（`--key=value`）
| 参数 | 默认 | 说明 |
|---|---|---|
| `--rpm` | 24 | 天线转速，决定发包节奏（spokes/s = rpm/60 × 4096） |
| `--samples` | 1024 | 每辐条采样数 nOfSamples（偶数） |
| `--rangeMeters` | — | 满量程（米），换算出 rangeCellSizeMm |
| `--rangeCellsDiv2` | 512 | range-cells / 2 |
| `--rangeCellSizeMm` | 5426 | 每量程单元距离(mm)，覆盖 `--rangeMeters` |
| `--doppler` | 关 | 多普勒编码（接近=15 / 远离=14） |
| `--spokesPerPacket` | 2 | 每个 UDP 包的辐条数（包必须 ≤1400 B） |
| `--ttl` | 1 | 组播 TTL（跨网段/蒲公英 SD-WAN 时调高） |
| `--iface` | 系统路由 | 出口网卡名（如 `en0`） |
| `--scans` | 0 | 发多少整圈（4096 辐条）后停止，0=无限 |

## 包格式（与文档一致）

```
UDP 载荷 = 8 字节帧前缀 + N 个辐条（背靠背）
辐条     = 24 字节头 + 数据(nOfSamples × 4-bit，字节填充)
典型辐条 = 24 + 512 = 536 字节；默认每包 2 辐条 → 8 + 2×536 = 1080 B ≤ 1400（蒲公英 MTU）
```

辐条头 6×uint32（小端、位域，§概观「字节对齐和小端法」）：

```
word0: spokeLength_bytes:12 | _:4 | sequenceNumber:12 | sampleEncoding:2 | _:2
word1: nOfSamples:12 | bitsPerSample:4 | rangeCellSize_mm:16
word2: spokeAzimuth:13 | _:1 | bearingZeroError:1 | _:1 | spokeCompass:14 | trueNorth:1 | compassInvalid:1
word3: rangeCellsDiv2:16 | _:16
word4/word5: 全预留
```
采样 4-bit：低索引存低半字节，高索引存高半字节；0=无信号，15=最强目标。

## 测试图样（便于 PPI 肉眼验证）
- **艏向标记**：方位 0° 整条径向高亮 → 确认船首/扫描起点对齐。
- **距离环**：每 128 个采样一圈同心圆 → 量程刻度核对。
- **固定方位目标块**：45°/135°/270° 三个已知方位+距离的目标块。多普勒模式下交替标接近(15)/远离(14)，供 T2.2 着色测试。

## 自测
```bash
./gradlew :tools:halofeed:test    # 30 项：头部位域往返、4-bit 打包、包尺寸/按长度步进、配置解析、图样
```
单元测试把同一个辐条按相同位域**解出来**校验往返一致（预演 T1.2 解析，但**不是** T1.2 的解析器）。
独立校验：用 `/tmp/halo_listen.py`（非本仓代码）的 Python 解码器实测收到 1080 字节包，按 `spokeLength_bytes` 步进恰好消费 1080/1080 字节。

## 给 T1.2 的提示与待确认项
- **建议按 `spokeLength_bytes` + 数据报长度遍历辐条**，不要依赖帧前缀里的计数（见下）。
- `TODO(待张建)` 帧前缀 `0100 0000 0020 0002` 的字段语义文档未给出；`0x20`(=32) 疑似对应 `Spoke[32]` 数组容量而非实际辐条数，故本发生器原样发出该 8 字节、不当作动态计数。
- `TODO(待张建)` `sampleEncoding` 的 `SmpEncode_Amplitude/Doppler` 文档未给数值，本实现假设 AMPLITUDE=0、DOPPLER=1。
- 本发生器无艏向传感器，故 `compassInvalid=1`（spokeCompass/trueNorth 无意义）。
