# 员工任务 W8-D：App 内真雷达数据回放(ReplayFeed)

## 你是谁
船用雷达项目(IMO CAT1)员工 AI。Kotlin + Android。说中文。

## 铁律
1. **同步主线**:`git checkout -B feat/w8-replay main`。worktree 在 `~/.superset/worktrees/radar/` 下,**绝不建到桌面**。
2. **只新建**:`app/src/main/kotlin/com/shipradar/app/replay/`(package `com.shipradar.app.replay`)+ test;并把真录像复制到 `app/src/main/assets/real_image.txt`(源文件 `~/Desktop/雷达开发资料/image`,文本 hex 抓包格式)。**不改任何已有文件**(RadarScreen/MainActivity/DemoFeed 等由编排者接)。

## 背景:抓包格式
文本,每包形如:
```
(UDP)192.168.0.100:1084->236.6.7.8:6678 ,17188 Bytes

45 00 43 24 ... (整 IP 报文的两位十六进制,空格分隔)
```
即:头行(含 dst `236.6.7.8:6678`=图像)+ 空行 + 整个 IP 报文 hex。去 **IP 头(20B)+ UDP 头(8B)** 得 HALO 载荷(以 8 字节帧头 `01 00 00 00 00 20 00 02` 开头)。

## 任务
`ReplayFeed`:读取 assets 里的抓包文本 → 解析出每个 UDP 包的 HALO 载荷 → 喂给 `CommsRouter`,让 app 渲染**真实回波**。
- `object ReplayFeed { suspend fun run(router: com.shipradar.comms.service.CommsRouter, assets: android.content.res.AssetManager, loopDelayMs: Long = 1000) }`
- 解析:按 `(UDP)` 分块;取 dst 端口判定通道(6678/6656=图像→`router.onHaloImage`,6679=状态→`onHaloStatus`,6688=目标→`onHaloTarget`);hex→bytes;`ihl=(b[0]&0x0F)*4`;载荷=`b[ihl+8 until end]`。
- 用 `System.currentTimeMillis()` 作 `now`。循环回放(本录像只有少量包,循环喂以便持续显示),每轮间隔 `loopDelayMs`。
- 也设 `router.applyLinkEvent(com.shipradar.comms.halo.handshake.LinkEvent.AllowReceived)` 让链路=CONNECTED。
- 解析要鲁棒(坏块跳过不崩)。
- 单测:用一小段内联的抓包文本字符串(自己造一个含 1 个 536B 辐条 + 8B 帧头的图像包),断言 ReplayFeed 的解析函数能取出正确载荷长度/首字节(把解析逻辑抽成可测纯函数 `fun parseCapture(text: String): List<Pair<Endpoint,ByteArray>>`)。

## 完成
`./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest` 绿;提交 `feat(app): 真雷达数据回放 ReplayFeed (W8-D)`;回报分支、文件、`ReplayFeed.run` 签名,供编排者作为第三数据源(SIM/REPLAY/LIVE)接入。
