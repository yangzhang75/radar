package com.shipradar.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.alarm.AlarmBar
import com.shipradar.app.alarm.AlarmPresentation
import com.shipradar.app.alarm.NoopAlarmController
import com.shipradar.app.control.ControlPanel
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.input.RadarInputLayer
import com.shipradar.app.databar.DataBar
import com.shipradar.app.demo.DemoFeed
import com.shipradar.app.demo.SimRadar
import com.shipradar.app.framework.ObTheme
import com.shipradar.app.framework.OpenBridgeTheme
import com.shipradar.app.bite.BiteMapping
import com.shipradar.app.bite.BitePanel
import com.shipradar.app.chart.ChartOverlay
import com.shipradar.app.autoacq.AcqZone
import com.shipradar.app.autoacq.AcqZoneOverlay
import com.shipradar.app.autoacq.AcqZoneSetupPanel
import com.shipradar.app.guardzone.GuardZone
import com.shipradar.app.guardzone.GuardZoneModel
import com.shipradar.app.guardzone.GuardZoneOverlay
import com.shipradar.app.guardzone.GuardZoneSetupPanel
import com.shipradar.app.replay.ReplayFeed
import com.shipradar.app.infopanel.PpiDataBoxes
import com.shipradar.app.theme.ThemePanel
import com.shipradar.app.theme.rememberThemeState
import com.shipradar.app.tracks.TracksControlPanel
import com.shipradar.app.tracks.TrackLength
import com.shipradar.app.trial.TrialManeuverPanel
import com.shipradar.app.viewctl.ViewControlPanel
import com.shipradar.app.viewctl.rememberViewControlState
import com.shipradar.uicore.target.OverlayConfig
import com.shipradar.app.infopanel.RightInfoPanel
import com.shipradar.app.input.rememberRadarInteractionState
import com.shipradar.app.ppi.PpiConfig
import com.shipradar.app.ppi.PpiSurface
import com.shipradar.app.target.TargetOverlay
import com.shipradar.comms.service.CommsConfig
import com.shipradar.comms.service.CommsRouter
import com.shipradar.comms.service.RadarCommsEngine
import com.shipradar.comms.service.RadarCommsService
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.LinkState
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.uicore.ppi.RangeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Top-level HMI assembly — **OWNED BY THE ORCHESTRATOR**. Wires each third-wave worker's self-contained
 * Composable into the [com.shipradar.app.framework.RadarScaffold] slots. Data flows through the real
 * decode pipeline (DemoFeed → CommsRouter); the local [com.shipradar.app.demo.SimRadar] stands in for
 * the radar's control/status loop. Swap both for the comms RadarCommsService once a radar/feed lands.
 *
 * Feature workers must NOT edit this file or [MainActivity].
 */
@Composable
fun RadarScreen() {
    // Hoisted display state so the control/mode panels actually drive the PPI + data bar.
    var display by remember { mutableStateOf(RadarDisplaySettings()) }

    // --- SIM / LIVE data-source mode ------------------------------------------------------------
    // 模拟作为一个明确的功能(IEC 62388 要求模拟/测试模式必须明显标识,不得与真实回波混淆)。
    //   SIMULATION: DemoFeed 生成真 HALO 线缆字节 + 61162 报文 → 真 CommsRouter 解析 → 总线流驱动 UI。
    //   LIVE:       真 RadarCommsEngine(Android 组播传输) → 接入同段网络上的真雷达 / HALO 发生器。
    // 两条源都走真解析管线,只是字节来源不同;切换时停掉另一侧。默认进入模拟(无硬件时仍可演示)。
    var live by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf(false) } // 真数据回放(J):on=ReplayFeed 真录像,off=DemoFeed 合成
    val ctx = LocalContext.current
    // 高实时性:真实数据接口的摄取跑在专用提升优先级线程池上(与 UI 线程隔离),不阻塞合成、不丢帧。
    val router = remember { CommsRouter(CommsConfig()) }            // SIM/REPLAY 解析器
    val sim = remember { SimRadar() }                              // SIM 控制/状态回路

    // LIVE = 生产数据路径:绑定前台 RadarCommsService(它在专用实时调度上承接真组播 + 看门狗,
    // 进程被杀也续命)。SIM/REPLAY 完全走 router,不碰服务。boundEngine 在服务连接后可用。
    var boundEngine by remember { mutableStateOf<RadarCommsEngine?>(null) }
    val serviceConn = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                boundEngine = (b as? RadarCommsService.LocalBinder)?.radarEngine
            }
            override fun onServiceDisconnected(n: android.content.ComponentName?) { boundEngine = null }
        }
    }
    DisposableEffect(live) {
        if (live) {
            RadarCommsService.start(ctx, com.shipradar.constants.DataInterfaceProfile.ACTUAL)
            ctx.bindService(android.content.Intent(ctx, RadarCommsService::class.java), serviceConn, android.content.Context.BIND_AUTO_CREATE)
        }
        onDispose { if (live) { runCatching { ctx.unbindService(serviceConn) }; boundEngine = null } }
    }
    // LIVE 服务未连/无数据时的回退流。
    val liveOwnShip = remember { MutableStateFlow(OwnShipData()) }
    val liveTargets = remember { MutableStateFlow(emptyList<TrackedTarget>()) }
    val liveStatus = remember { MutableStateFlow(RadarStatus(powerState = RadarPowerState.OFF)) }
    val liveLink = remember { MutableStateFlow(LinkState.DISCONNECTED) }

    // 模拟侧数据源:replay=真录像(ReplayFeed)/ 否则=合成(DemoFeed);LIVE 走绑定服务。
    LaunchedEffect(live, replay) {
        if (!live) {
            if (replay) ReplayFeed.run(router, ctx.assets) else DemoFeed.run(router)
        }
    }

    // 按模式选择数据源(SIM/REPLAY=router/sim ; LIVE=绑定服务 engine,连接前回退)。
    val spokes = if (live) (boundEngine?.echoSpokes ?: emptyFlow()) else router.echoSpokes
    val ownShipFlow = if (live) (boundEngine?.ownShip ?: liveOwnShip) else router.ownShip
    val ownShipState by ownShipFlow.collectAsState()
    // SIM/REPLAY 也走 router.targets:雷达 TT 由回波跟踪管线从 DemoFeed/录像回波提取,AIS 由 61162 解析,
    // 二者在 router 内融合。LIVE 走绑定服务的 engine.targets。(此前 SIM 用静态 FakeTargets 占位,已废弃。)
    val targetsFlow = if (live) (boundEngine?.targets ?: liveTargets) else router.targets
    val targetList by targetsFlow.collectAsState()
    // Conning/engine read-outs(舵角/转速/水深)—— SIM/REPLAY 走 router,LIVE 走绑定服务 engine。
    val liveConning = remember { MutableStateFlow(com.shipradar.contract.ConningData()) }
    val conning by (if (live) (boundEngine?.conning ?: liveConning) else router.conning).collectAsState()
    val status by (if (live) (boundEngine?.radarStatus ?: liveStatus) else sim.status).collectAsState()
    val controller: RadarController = if (live) (boundEngine ?: sim) else sim

    // 双量程画面 (HALO dual-range / Radar B):并排两幅 PPI,各自量程 + 各自叠加目标。B 流见 echoSpokesB。
    var dualRange by remember { mutableStateOf(false) }
    var rangeScaleNmB by remember { mutableStateOf(1.5) } // Radar B 量程,可独立调
    val spokesB = if (live) (boundEngine?.echoSpokesB ?: emptyFlow()) else router.echoSpokesB

    // Hoisted interaction state so target selection + EBL/VRM drive the info panel and on-PPI boxes.
    val interaction = rememberRadarInteractionState()
    val selectedTarget = targetList.firstOrNull { it.id == interaction.model.selectedTargetId }

    var showHelp by remember { mutableStateOf(false) }
    var showMonitor by remember { mutableStateOf(false) }
    var showTrial by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showBite by remember { mutableStateOf(false) }
    var showGuard by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    // 报警圈状态 hoist:面板编辑 + PPI 轮廓共用同一份。
    var guardZones by remember { mutableStateOf(List(GuardZoneModel.ZONE_COUNT) { GuardZone(zone = it) }) }

    // 碰撞预警闭环:由富化后的目标(router 已算 CPA/TCPA/dangerous)+ 警戒圈生成报警事件。
    //   · 危险目标(CPA/TCPA 超限)→ 3044 碰撞报警(ALARM, IEC 62388 §11 / A.823 §3.5.2)
    //   · 进入启用的警戒圈 → 3048(WARNING)
    // SIM/LIVE 同源(targetList 两种模式均经 router 富化);稳定排序避免重组抖动。
    val alarmEvents = remember(targetList, guardZones) {
        buildList {
            targetList.filter { it.dangerous }.sortedBy { it.id }.forEach { t ->
                add(AlarmEvent(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK, "CPA/TCPA ${t.id}", "RADAR"))
            }
            val enabledZones = guardZones.filter { it.enabled }
            if (enabledZones.isNotEmpty()) {
                targetList.sortedBy { it.id }.forEach { t ->
                    if (GuardZoneModel.zonesHit(enabledZones, t.bearingDeg, t.rangeNm).isNotEmpty()) {
                        add(AlarmEvent(3048, AlarmPriority.WARNING, AlarmState.ACTIVE_UNACK, "Target ${t.id} in guard zone", "RADAR"))
                    }
                }
            }
        }
    }
    val alarms = AlarmPresentation.uiStateOf(alarmEvents)
    // 偏心显示(O 键):归一化偏移,驱动 PPI 投影 + 各叠加层同一本船中心。
    var showView by remember { mutableStateOf(false) }
    val viewCtl = rememberViewControlState()
    val viewOff = viewCtl.effectiveOffset // ViewOffset(x,y) 归一化(半径分数)
    // 自动捕获区(C 键):hoisted,面板编辑 + PPI 叠加共用。
    var showAcq by remember { mutableStateOf(false) }
    var acqZones by remember { mutableStateOf(List(2) { AcqZone(id = it) }) }
    // 海图/底图叠加(X 键),默认开。
    var showChart by remember { mutableStateOf(true) }
    // 过去航迹时长(H 键调):驱动现有 TargetOverlay 航迹(showTrails/maxTrailPoints),不另起冗余系统。
    var trackLength by remember { mutableStateOf(TrackLength.MIN_3) }
    // 昼/阴天/黄昏/夜 + 亮度(W6-B):hoist 一次,驱动全局 OpenBridgeTheme;面板由 K 键浮层调节。
    val themeState = rememberThemeState(com.shipradar.app.theme.ThemeMode.DAY)
    // 亮度 → 硬件 I/O 口 PWM 背光(无硬件则记日志;真机配 SysfsBacklightPwm.pwmPath)。
    LaunchedEffect(themeState.brilliance) {
        com.shipradar.app.theme.SysfsBacklightPwm.setBrilliance(themeState.brilliance)
    }
    // 链路监视数据源(SIM=router 计数 / LIVE=engine 计数);两者都有 dataLinkSnapshot。
    val linkSnapshot: (Long) -> com.shipradar.comms.service.DataLinkStats =
        if (live) { now -> boundEngine?.dataLinkSnapshot(now) ?: router.dataLinkSnapshot(now) } else router::dataLinkSnapshot

    OpenBridgeTheme(theme = themeState.mode.toObTheme()) {
      // 屏幕根部 onPreviewKeyEvent:先消费雷达控制级快捷键(量程/发射/增益/定向/运动/SIM-LIVE/帮助),
      // 其余键透传给获得焦点的 RadarInputLayer(光标/目标/EBL/VRM/PI)。两层键位不冲突。
      androidx.compose.foundation.layout.Box(
          Modifier
              .fillMaxSize()
              .onPreviewKeyEvent { ke ->
                  if (ke.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                  handleControlKey(
                      key = ke.key,
                      display = display,
                      setDisplay = { display = it },
                      controller = controller,
                      status = status,
                      onToggleLive = { live = !live },
                      onToggleDual = { dualRange = !dualRange },
                      onToggleMonitor = { showMonitor = !showMonitor },
                      onToggleTrial = { showTrial = !showTrial },
                      onToggleTheme = { showTheme = !showTheme },
                      onToggleBite = { showBite = !showBite },
                      onToggleGuard = { showGuard = !showGuard },
                      onToggleTracks = { showTracks = !showTracks },
                      onToggleView = { showView = !showView },
                      onToggleReplay = { replay = !replay },
                      onToggleAcq = { showAcq = !showAcq },
                      onToggleChart = { showChart = !showChart },
                      onToggleHelp = { showHelp = !showHelp },
                      // Esc 关闭任意打开的浮层。
                      onCloseHelp = {
                          showHelp = false; showMonitor = false
                          showTrial = false; showTheme = false; showBite = false
                          showGuard = false; showTracks = false; showView = false
                          showAcq = false
                      },
                  )
              },
      ) {
        com.shipradar.app.framework.RadarScaffold(
            // W4-A: control + databar now share one canonical RadarDisplaySettings — pass it straight through.
            top = {
                DataBar(
                    ownShip = ownShipState,
                    status = status,
                    settings = display,
                )
            },
            // ControlPanel already bundles ModeControls (range/motion/orientation) + its own
            // width(360) + internal vertical scroll, so it goes straight into the side slot.
            side = {
                ControlPanel(
                    status = status,
                    controller = controller,
                    display = display,
                    onDisplayChange = { display = it },
                )
            },
            // modes slot intentionally empty — controls all live in the side panel; nothing floats over the PPI.
            center = {
                if (dualRange) {
                    // 双量程:并排两幅 PPI,各自量程可独立调、各自叠加目标(RADAR A 主控 / RADAR B)。
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                        RangePane(
                            label = "RADAR A", rangeNm = display.rangeScaleNm,
                            spokes = spokes, targets = targetsFlow, ownShip = ownShipFlow,
                            orientation = display.orientation,
                            headingDeg = ownShipState.headingDeg, courseDeg = ownShipState.cogDeg,
                            live = live, paneKey = true, modifier = Modifier.weight(1f),
                            onRangeIn = {
                                val nm = RangeModel.previousRangeScale(display.rangeScaleNm)
                                if (nm != display.rangeScaleNm) {
                                    display = display.copy(rangeScaleNm = nm)
                                    controller.send(RadarCommand.SetRange(RangeModel.nmToMeters(nm).toInt()))
                                }
                            },
                            onRangeOut = {
                                val nm = RangeModel.nextRangeScale(display.rangeScaleNm)
                                if (nm != display.rangeScaleNm) {
                                    display = display.copy(rangeScaleNm = nm)
                                    controller.send(RadarCommand.SetRange(RangeModel.nmToMeters(nm).toInt()))
                                }
                            },
                        )
                        RangePane(
                            label = "RADAR B", rangeNm = rangeScaleNmB,
                            spokes = spokesB, targets = targetsFlow, ownShip = ownShipFlow,
                            orientation = display.orientation,
                            headingDeg = ownShipState.headingDeg, courseDeg = ownShipState.cogDeg,
                            live = live, paneKey = false, modifier = Modifier.weight(1f),
                            onRangeIn = { rangeScaleNmB = RangeModel.previousRangeScale(rangeScaleNmB) },
                            onRangeOut = { rangeScaleNmB = RangeModel.nextRangeScale(rangeScaleNmB) },
                        )
                    }
                } else {
                    // key(live, replay):切换数据源(SIM/REPLAY/LIVE)时重建 PPI,清空持久回波位图。
                    androidx.compose.runtime.key(live, replay) {
                        PpiSurface(
                            spokes = spokes,
                            config = PpiConfig(
                                rangeScaleNm = display.rangeScaleNm,
                                orientation = display.orientation,          // wire the orientation control to echoes
                                headingDeg = ownShipState.headingDeg,       // so north-up/course-up actually rotate
                                courseDeg = ownShipState.cogDeg,
                                centerOffsetX = viewOff.x,                  // 偏心显示(O 键)
                                centerOffsetY = viewOff.y,
                                palette = themeState.mode.toEchoPalette(),  // PPI 回波色随昼/黄昏/夜主题
                            ),
                        )
                    }
                }
            },
            overlay = {
                // 单量程才叠加目标/数据框(双量程的并排 PPI 自带各自标签,布局不同)。
                if (!dualRange) {
                    // 海图/底图(X 键,默认开):画在最底,目标/回波在其上。随本船位置/量程/方位/偏心。
                    if (showChart) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val d = LocalDensity.current
                            val wPx = with(d) { maxWidth.toPx() }
                            val hPx = with(d) { maxHeight.toPx() }
                            val rad = com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density)
                            ChartOverlay(
                                ownLat = ownShipState.latitude,
                                ownLon = ownShipState.longitude,
                                headingDeg = ownShipState.headingDeg,
                                courseDeg = ownShipState.cogDeg,
                                rangeScaleNm = display.rangeScaleNm,
                                orientation = display.orientation,
                                center = Offset(wPx / 2f + viewOff.x * rad, hPx / 2f + viewOff.y * rad),
                                radiusPx = rad,
                            )
                        }
                    }
                    TargetOverlay(
                        targets = if (live) (boundEngine?.targets ?: liveTargets) else router.targets,
                        ownShip = ownShipFlow,
                        rangeScaleNm = display.rangeScaleNm,
                        orientation = display.orientation,              // targets follow the same orientation
                        centerOffset = Offset(viewOff.x, viewOff.y),   // 偏心:目标随本船移位
                        // 过去航迹由 H 键的时长驱动(复用现有 A.823 航迹,非冗余系统)。
                        config = OverlayConfig(
                            showTrails = trackLength.enabled,
                            maxTrailPoints = when (trackLength) {
                                TrackLength.OFF -> 0
                                TrackLength.MIN_1 -> 4
                                TrackLength.MIN_3 -> 6
                                TrackLength.MIN_6 -> 8
                            },
                        ),
                    )
                    // On-PPI data boxes (GAIN/SEA/RAIN top, EBL/VRM bottom, RANGE) — standard IMO layout.
                    PpiDataBoxes(status = status, display = display, model = interaction.model)
                    // 报警圈轮廓(仅启用的区)画在 PPI 上,与 Z 键面板共用同一份 zones。
                    if (guardZones.any { it.enabled }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val d = LocalDensity.current
                            val wPx = with(d) { maxWidth.toPx() }
                            val hPx = with(d) { maxHeight.toPx() }
                            GuardZoneOverlay(
                                center = Offset(
                                    wPx / 2f + viewOff.x * com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                                    hPx / 2f + viewOff.y * com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                                ),
                                radiusPx = com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                                rangeScaleNm = display.rangeScaleNm,
                                orientation = display.orientation,
                                zones = guardZones.filter { it.enabled },
                                headingDeg = ownShipState.headingDeg,
                                courseDeg = ownShipState.cogDeg,
                            )
                        }
                    }
                    // 自动捕获区轮廓(仅启用的区),与 C 键面板共用同一份 zones。
                    if (acqZones.any { it.enabled }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val d = LocalDensity.current
                            val wPx = with(d) { maxWidth.toPx() }
                            val hPx = with(d) { maxHeight.toPx() }
                            val rad = com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density)
                            AcqZoneOverlay(
                                center = Offset(wPx / 2f + viewOff.x * rad, hPx / 2f + viewOff.y * rad),
                                radiusPx = rad,
                                rangeScaleNm = display.rangeScaleNm,
                                orientation = display.orientation,
                                zones = acqZones.filter { it.enabled },
                                headingDeg = ownShipState.headingDeg,
                                courseDeg = ownShipState.cogDeg,
                            )
                        }
                    }
                }
                // 模式明显标识(IEC 62388):SIM=模拟横幅,REPLAY=真录像回放横幅,LIVE 时无。
                if (!live) ModeBanner(if (replay) "● REPLAY 真数据回放" else "● SIMULATION 模拟")
                // LIVE 常驻链路状态指示(认证要求);右上角。
                if (live) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .padding(8.dp)
                            .background(androidx.compose.ui.graphics.Color(0xCC0B1418))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        val ls by (if (live) (boundEngine?.linkState ?: liveLink) else router.linkState).collectAsState()
                        BoxScopeLinkChip(ls)
                    }
                }
            },
            alarms = { AlarmBar(uiState = alarms, controller = NoopAlarmController) },
            // T2.5 interaction layer over the PPI: measure the operational area so touch/key/mouse
            // hit-testing (select/EBL/VRM) aligns with the rendered echoes/targets.
            input = {
                // 双量程时不启用单 PPI 的交互层(命中测试假设单一全区 PPI);单量程照常。
                if (!dualRange) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val d = LocalDensity.current
                        val wPx = with(d) { maxWidth.toPx() }
                        val hPx = with(d) { maxHeight.toPx() }
                        RadarInputLayer(
                            center = Offset(
                                wPx / 2f + viewOff.x * com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                                hPx / 2f + viewOff.y * com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                            ),
                            radiusPx = com.shipradar.app.ppi.PpiLayout.operationalRadiusPx(wPx, hPx, d.density),
                            orientation = display.orientation,
                            rangeScaleNm = display.rangeScaleNm,
                            targets = targetList,
                            ownHeadingDeg = ownShipState.headingDeg,
                            ownCourseDeg = ownShipState.cogDeg,
                            state = interaction,
                        )
                    }
                }
            },
            // Standard IMO layout area ③ — own-ship + target data + TT/AIS settings + collision danger.
            right = {
                RightInfoPanel(
                    ownShip = ownShipState,
                    targets = targetList,
                    display = display,
                    conning = conning,
                    selected = selectedTarget,
                    simulated = !live,
                    onToggleSource = { live = !live },
                    dualRange = dualRange,
                    onToggleDual = { dualRange = !dualRange },
                )
            },
        )
        // 快捷键帮助浮层(F1 / ? 切换),覆盖全屏。
        if (showHelp) HotkeyHelpOverlay(onDismiss = { showHelp = false })
        // 数据链路监视浮层(L 切换)。
        if (showMonitor) LinkMonitorOverlay(snapshot = linkSnapshot, onDismiss = { showMonitor = false })
        // 试操船(Y)/ 主题(K)/ BITE(B)浮层(W6 员工面板)。
        if (showTrial) DismissOverlay({ showTrial = false }) {
            TrialManeuverPanel(ownShip = ownShipState, targets = targetList)
        }
        if (showTheme) DismissOverlay({ showTheme = false }) {
            ThemePanel(
                mode = themeState.mode,
                brilliance = themeState.brilliance,
                onModeChange = { themeState.setMode(it) },
                onBrillianceChange = { themeState.changeBrilliance(it) },
            )
        }
        if (showBite) DismissOverlay({ showBite = false }) {
            // 周期刷新 BITE 报告(链路快照 + 自船有效性 → BiteReport)。
            var report by remember { mutableStateOf(BiteMapping.from(linkSnapshot(System.currentTimeMillis()), ownShipState)) }
            LaunchedEffect(Unit) {
                while (true) {
                    report = BiteMapping.from(linkSnapshot(System.currentTimeMillis()), ownShipState)
                    kotlinx.coroutines.delay(1000)
                }
            }
            BitePanel(report = report)
        }
        // 报警圈/捕获区 设置(Z)—— 面板向雷达下发 guard-zone 命令。
        if (showGuard) DismissOverlay({ showGuard = false }) {
            GuardZoneSetupPanel(controller = controller, zones = guardZones, onZonesChange = { guardZones = it })
        }
        // 过去航迹 时长(H)—— 驱动现有 TargetOverlay 航迹。
        if (showTracks) DismissOverlay({ showTracks = false }) {
            TracksControlPanel(length = trackLength, onLengthChange = { trackLength = it })
        }
        // 偏心显示 / 真运动复位(O)。
        if (showView) DismissOverlay({ showView = false }) {
            ViewControlPanel(state = viewCtl)
        }
        // 自动捕获区(C)。
        if (showAcq) DismissOverlay({ showAcq = false }) {
            AcqZoneSetupPanel(zones = acqZones, onZonesChange = { acqZones = it })
        }
      }
    }
}

/**
 * 双量程的单个画面窗格 — 一幅 PPI + 叠加目标 + 左上角通道/量程标注 + 右上角量程 +/− 按钮。
 * 双量程模式下单 PPI 的交互层被停用,所以本窗格的按钮处于顶层、可直接点击(各窗格独立调量程)。
 */
@Composable
private fun RangePane(
    label: String,
    rangeNm: Double,
    spokes: kotlinx.coroutines.flow.Flow<com.shipradar.contract.EchoSpoke>,
    targets: kotlinx.coroutines.flow.StateFlow<List<com.shipradar.contract.TrackedTarget>>,
    ownShip: kotlinx.coroutines.flow.StateFlow<com.shipradar.contract.OwnShipData>,
    orientation: com.shipradar.uicore.ppi.PpiOrientation,
    headingDeg: Double?,
    courseDeg: Double?,
    live: Boolean,
    paneKey: Boolean,
    onRangeIn: () -> Unit,
    onRangeOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        // key(live, paneKey):切换数据源/窗格时重建 PPI,清空持久回波位图。
        androidx.compose.runtime.key(live, paneKey) {
            PpiSurface(
                spokes = spokes,
                config = PpiConfig(
                    rangeScaleNm = rangeNm,
                    orientation = orientation,
                    headingDeg = headingDeg,
                    courseDeg = courseDeg,
                ),
            )
        }
        // 叠加目标(同一目标集,各自按本窗格量程投影)。
        TargetOverlay(
            targets = targets,
            ownShip = ownShip,
            rangeScaleNm = rangeNm,
            orientation = orientation,
        )
        // 通道 + 量程标注(左上)。
        androidx.compose.foundation.layout.Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .padding(8.dp)
                .background(androidx.compose.ui.graphics.Color(0xCC0B1418))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            androidx.compose.material3.Text(
                "$label  %.2f NM".format(rangeNm),
                color = androidx.compose.ui.graphics.Color(0xFF9FE6C2),
                fontSize = 12.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
        // 量程 +/− 按钮(右上),各窗格独立调量程。
        androidx.compose.foundation.layout.Column(
            Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
        ) {
            RangeStepButton("+", onRangeOut) // 量程放大(更大 NM)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(2.dp))
            RangeStepButton("−", onRangeIn)  // 量程缩小
        }
    }
}

@Composable
private fun RangeStepButton(symbol: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(vertical = 2.dp)
            .background(androidx.compose.ui.graphics.Color(0xDD14323A))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.Text(
            symbol,
            color = androidx.compose.ui.graphics.Color(0xFFE6F2F5),
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

/** 通用浮层:半透明遮罩居中显示面板;点遮罩关闭,点面板本身不关(吸收点击,保证滑条/按钮可用)。 */
@Composable
private fun DismissOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xCC000810))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {},
        ) { content() }
    }
}

/** 模式横幅 — IEC 62388 要求模拟/测试/回放模式必须明显、常驻标识。PPI 顶部居中,琥珀色。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ModeBanner(text: String) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .align(androidx.compose.ui.Alignment.TopCenter)
            .padding(top = 4.dp)
            .background(androidx.compose.ui.graphics.Color(0xCCB36B00))
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        androidx.compose.material3.Text(
            text,
            color = androidx.compose.ui.graphics.Color(0xFFFFF1D6),
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
