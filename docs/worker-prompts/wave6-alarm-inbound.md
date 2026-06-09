# 第六波(单任务) · 接通入站报警命令链路（MED 修复）→ carefree-drip

> 代码审查发现的唯一 MED:入站 ACN/ARC/ALC 报警命令在 `CommsRouter` 里被路由到 `{}`,**对本机报警状态机没有任何作用**——即"中央报警面板(BAM/CAM)确认/静音/转移责任"这条链路没通。你是报警域 owner(建了 `BamAlarmManager.accept()`),来把它接通。认证报警闭环需要这条。

## 规则
- 复用 `carefree-drip` 会话。worktree **建到非桌面绝对路径**:`git worktree add /Users/yangzhang/.superset/worktrees/radar/wt-alarm-inbound -b feat/alarm-inbound main`。**不要用 `../`**。
- 范围:`comms-core/.../comms/alarm/`(你的) + **授权编辑** `comms/src/main/kotlin/com/shipradar/comms/service/CommsRouter.kt`(报警接线那段)。不碰其它。
- 绿了(`:comms-core:test` + `:comms:assembleDebug`)立即 commit。引 IEC 62923-1 §条款。

## 现状(事实)
- `CommsRouter.routeSentence`:`AlertUpdate -> _alarms.tryEmit(parsed.alarm)`(直接发原始事件给 UI,**没经过状态机**);`AlertCommandReceived/AlertCommandRefused/AlertListUpdate -> {}`(丢弃)。
- `_alarms` 是 `MutableSharedFlow<com.shipradar.contract.AlarmEvent>`(UI 消费)。
- `BamAlarmManager`:`raise(...)`、`command(id,instance,event,now)`、`accept(cmd: AlarmCommand, now): List<AlarmIntent>`、`stateOf(...)`。工作在 `BamAlertState`/`AlarmIntent`。
- 映射:`iec61162.AlertCommand`(kind: AlertCommandKind{ACKNOWLEDGE/REQUEST_REPEAT/RESPONSIBILITY_TRANSFER/SILENCE}) → `alarm.AlarmCommand`(Kind 同名集合)。

## 要求(让状态机成为唯一真相源)
1. `CommsRouter` 持有一个 `BamAlarmManager`(+ 时钟 now 来源,沿用 supervisor 用的时间)。
2. **入站报警事件**(AlertUpdate / ALR / ALF / AlertListUpdate)→ 走 `manager.raise(...)`,不再直接 tryEmit 原始事件。
3. **入站命令** AlertCommandReceived(ACN)→ 映射成 `AlarmCommand` → `manager.accept(...)`;AlertCommandRefused(ARC)→ 按 62923-1 处理(记录/状态)。
4. 把 `manager` 产生的状态/意图**转换成 `contract.AlarmEvent`**(含正确的 `AlarmState`/优先级/文案/ID)发到 `_alarms`,供 UI 显示。设计这个 `AlarmIntent/BamAlertState → contract.AlarmEvent` 转换器(你的域,放 alarm 包)。
5. 保持既有 UI 行为不回归(报警仍能正常显示);新增的是命令真正驱动状态转移。
6. 顺手清理 `BamAlarmManager.accept` 里那段不可达分支(命令 event==null 的 RefuseAcn 兜底,REQUEST_REPEAT 已在前面处理——审查标的 LOW)。

## 验证
- 单测:注入 ACN(确认)→ 状态 ACTIVE_UNACK→ACTIVE_ACK 并发出对应 AlarmEvent;ARC/责任转移;ALC 列表;非法命令被拒。`:comms-core:test` 全绿;`:comms:assembleDebug` 绿(CommsRouter 编译通过)。
- 报告:转换器设计 + 62923-1 条款 + 任何接口疑问。
