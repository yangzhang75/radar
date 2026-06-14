# haloprobe — HALO 真雷达探针(本机直连)

在**本机(Mac/PC)直接连真雷达**,不经安卓/模拟器(模拟器 NAT 收不到 LAN/VPN 组播)。
用 JVM 自己的网络栈走蒲公英 X5 所在网卡 join 雷达组播,复用 comms-core 的**真解析器/握手**解码并打印,
可存抓包。用于:验证"接真雷达解得对"、产出离线抓包、第一手十六进制核对协议。

## 跑(对真雷达)
```
# 1. 本机先通过蒲公英 X5 接入雷达网段(确认 ping 得到雷达)
# 2. 找到蒲公英虚拟网卡名:  ifconfig   (如 utun3 / en6)
./gradlew :tools:haloprobe:run --args="--iface=<网卡> --duration=120 --record=cap.bin"

# 组播握手不通时,走手动 IP(单播):
./gradlew :tools:haloprobe:run --args="--iface=<网卡> --manual-ip=<雷达IP> --duration=120 --record=cap.bin"
```

## 参数
| 参数 | 说明 |
|---|---|
| `--iface=<名>` | 走哪块网卡(蒲公英虚拟网卡);不填自动挑 up/非回环/支持组播的 |
| `--manual-ip=<IP>` | 跳过组播握手,按手动 IP(蒲公英组播被过滤时用) |
| `--no-handshake` | 不发握手,直接监听默认端点 |
| `--duration=<秒>` | 运行时长(默认 60) |
| `--record=<文件>` | 存抓包(halofeed 录制格式),之后可离线回放/逐字段核对 |
| `--hex=<n>` | 每通道首包打印前 n 字节十六进制(默认 48) |

## 输出看什么
- 各通道(IMAGE/STATUS/TARGET)**收包数 / 解出辐条数·目标数** + 首包十六进制 + 解码摘要。
- "收到包但解出 0 辐条/目标" = 格式不符 → 看首包十六进制核对协议(像之前的 8 字节帧头)。

## 本机自测(无雷达,验证工具本身)
```
# 终端A:发假数据
./gradlew :tools:halofeed:run --args="--iface=en0"
# 终端B:收并解码(应看到 IMAGE 收包、解出辐条)
./gradlew :tools:haloprobe:run --args="--iface=en0 --no-handshake --duration=6"
```
已验证:halofeed 发 → haloprobe 收,IMAGE 解出辐条(帧头正确跳过);TARGET 因 halofeed 用 `FAKETGT`
占位格式、与真 TargetParser 不一致而解出 0(目标线缆格式待张建确认)。
