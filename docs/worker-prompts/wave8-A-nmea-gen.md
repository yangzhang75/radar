# 员工任务 W8-A：NMEA-0183 / 61162-1 传感器语句生成核心

## 你是谁
船用雷达项目(IMO CAT1)员工 AI。**纯 JVM**(无 Android/Compose)。说中文,字段对照 **IEC 61162-1 ED6**(资料:`~/Desktop/雷达开发资料/80-1093-IEC 61162-1 ED6_2023_FDIS.pdf`)。

## 铁律
1. **同步主线**:在你 worktree 里 `git checkout -B feat/w8-nmea main`。worktree 路径必须在 `~/.superset/worktrees/radar/` 下,**绝不建到桌面**。
2. **只新建**:`tools/simcore/src/main/kotlin/com/shipradar/sim/nmea/` + `tools/simcore/src/test/kotlin/com/shipradar/sim/nmea/`(package `com.shipradar.sim.nmea`)。**不改任何已有文件**(含 build.gradle、Sim.kt、settings)。
3. 不碰其它包(尤其 `sim/ais` 是 W8-B 的)。不依赖 Android。

## 任务
做一个**传感器 NMEA 语句生成器**(给模拟器 GUI 和 app 共用):各传感器一个**数据类(可设字段)** + `toSentences(): List<String>`,输出合法 NMEA-0183 句(带 `$`/`!`、talker、字段、`*HH` 校验和、CRLF)。**纯函数,可单测。**

覆盖(每类给依据条款注释):
- **GPS/GNSS**:`GGA`(定位)、`RMC`(最小推荐)、`VTG`(对地航向/航速)、`GLL`(经纬)、`ZDA`(时间)。输入:lat/lon、SOG、COG、UTC、定位质量、卫星数。
- **罗经/航向**:`HDT`(真航向)、`HDG`(磁航向+偏差+磁差)、`THS`(真航向+状态)、`ROT`(转向率)。
- **风**:`MWV`(风速+风角,R 相对/T 真)、`MWD`(真风向+风速)。
- **水速/流**:`VHW`(对水航速+航向)、`VBW`(对地/对水双速)、`VDR`(流向流速 set & drift)。
- **测深**:`DPT`、`DBT`。
- **舵/自动舵**:`RSA`(舵角)、`HSC`(航向操纵指令)、`HTC/HTD`(航向/航迹控制,若 ED6 有)。
- **机舱**:`RPM`(主机/轴转速)、`XDR`(换能器:温度/压力等通用量)。

要求:
- 校验和 = `$`与`*`之间所有字符逐字节 XOR,两位大写十六进制。提供一个公共 `fun checksum(body: String): String` 和 `fun sentence(talker: String, body: String): String`。
- 字段格式严格按 ED6(经纬 ddmm.mmmm、半球 N/S/E/W、角度 0–359.9、单位字母等)。**不确定的字段在 KDoc 标 TODO(待核 ED6 §x),不臆造。**
- 每类 ≥2 个断言测试(典型值 → 期望句 + 校验和正确)。可参考已有 `tools/halofeed` 里 `Nmea.kt`/`NmeaTest`(只读借鉴风格,不依赖它)。

## 完成
`./gradlew :tools:simcore:test` 全绿;提交 `feat(simcore): NMEA-0183 传感器语句生成 (W8-A)`;回报分支、文件、每个生成器的入口签名(供 GUI 调用)。
