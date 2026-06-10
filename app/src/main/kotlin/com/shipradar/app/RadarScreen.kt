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
import com.shipradar.app.infopanel.PpiDataBoxes
import com.shipradar.app.infopanel.RightInfoPanel
import com.shipradar.app.input.rememberRadarInteractionState
import com.shipradar.app.ppi.PpiConfig
import com.shipradar.app.ppi.PpiSurface
import com.shipradar.app.target.FakeTargets
import com.shipradar.app.target.TargetOverlay
import com.shipradar.comms.service.AndroidMulticastTransport
import com.shipradar.comms.service.CommsConfig
import com.shipradar.comms.service.CommsRouter
import com.shipradar.comms.service.RadarCommsEngine
import com.shipradar.comms.service.RealtimeIngest
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.RadarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

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
    val ctx = LocalContext.current
    // 高实时性:真实数据接口的摄取跑在专用提升优先级线程池上(与 UI 线程隔离),不阻塞合成、不丢帧。
    // 生产环境由前台 RadarCommsService 承接同一引擎;此处内联引擎用同样的实时调度纪律。
    val ingestScope = remember { CoroutineScope(SupervisorJob() + RealtimeIngest.dispatcher()) }

    val router = remember { CommsRouter(CommsConfig()) }            // SIM 解析器
    val sim = remember { SimRadar() }                              // SIM 控制/状态回路
    // LIVE:实际数据接口 profile(法定 236.6.7.x 端口),实时调度摄取。
    val engine = remember { RadarCommsEngine(AndroidMulticastTransport(ctx), CommsConfig.actual(), ingestScope) }

    // DemoFeed 仅在模拟模式喂数据;切到 LIVE 时取消(键控于 live)。
    LaunchedEffect(live) { if (!live) DemoFeed.run(router) }
    // 真组播引擎仅在 LIVE 模式收发(握手 / 看门狗 / 解析);切回 SIM 时关闭释放组播锁。
    DisposableEffect(live) {
        if (live) engine.start()
        onDispose { if (live) engine.stop() }
    }
    // 屏幕销毁时取消实时摄取 scope,回收专用线程池。
    DisposableEffect(Unit) { onDispose { ingestScope.cancel() } }

    // 按模式选择数据源(SIM=router/sim/假目标 ; LIVE=engine 真总线流)。
    val spokes = if (live) engine.echoSpokes else router.echoSpokes
    val ownShipFlow = if (live) engine.ownShip else router.ownShip
    val ownShipState by ownShipFlow.collectAsState()
    val targets = remember { MutableStateFlow(FakeTargets.mixedScene()) }
    val targetList by (if (live) engine.targets else targets).collectAsState()
    val status by (if (live) engine.radarStatus else sim.status).collectAsState()
    val controller: RadarController = if (live) engine else sim

    // 双量程画面 (HALO dual-range / Radar B):并排两幅 PPI,各自量程。B 流见 CommsRouter.echoSpokesB。
    var dualRange by remember { mutableStateOf(false) }
    val rangeScaleNmB = 1.5 // Radar B 短量程(近距景象)
    val spokesB = if (live) engine.echoSpokesB else router.echoSpokesB

    // Hoisted interaction state so target selection + EBL/VRM drive the info panel and on-PPI boxes.
    val interaction = rememberRadarInteractionState()
    val selectedTarget = targetList.firstOrNull { it.id == interaction.model.selectedTargetId }
    val alarms = if (live) {
        AlarmPresentation.uiStateOf(emptyList())                    // LIVE 报警接 engine.alarms(后续)
    } else {
        AlarmPresentation.uiStateOf(
            listOf(
                AlarmEvent(3048, AlarmPriority.WARNING, AlarmState.ACTIVE_UNACK, "New target in guard zone", "RADAR"),
            ),
        )
    }

    var showHelp by remember { mutableStateOf(false) }

    OpenBridgeTheme(ObTheme.DAY) {
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
                      onToggleHelp = { showHelp = !showHelp },
                      onCloseHelp = { showHelp = false },
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
                    // 双量程:并排两幅 PPI(RADAR A 远景 / RADAR B 近景),各自量程独立。
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                        DualPane("RADAR A", display.rangeScaleNm, Modifier.weight(1f)) {
                            androidx.compose.runtime.key(live, true) {
                                PpiSurface(
                                    spokes = spokes,
                                    config = PpiConfig(
                                        rangeScaleNm = display.rangeScaleNm,
                                        orientation = display.orientation,
                                        headingDeg = ownShipState.headingDeg,
                                        courseDeg = ownShipState.cogDeg,
                                    ),
                                )
                            }
                        }
                        DualPane("RADAR B", rangeScaleNmB, Modifier.weight(1f)) {
                            androidx.compose.runtime.key(live, false) {
                                PpiSurface(
                                    spokes = spokesB,
                                    config = PpiConfig(
                                        rangeScaleNm = rangeScaleNmB,
                                        orientation = display.orientation,
                                        headingDeg = ownShipState.headingDeg,
                                        courseDeg = ownShipState.cogDeg,
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    // key(live):切换数据源时重建 PPI,清空持久回波位图,避免模拟回波残留进 LIVE(反之亦然)。
                    androidx.compose.runtime.key(live) {
                        PpiSurface(
                            spokes = spokes,
                            config = PpiConfig(
                                rangeScaleNm = display.rangeScaleNm,
                                orientation = display.orientation,          // wire the orientation control to echoes
                                headingDeg = ownShipState.headingDeg,       // so north-up/course-up actually rotate
                                courseDeg = ownShipState.cogDeg,
                            ),
                        )
                    }
                }
            },
            overlay = {
                // 单量程才叠加目标/数据框(双量程的并排 PPI 自带各自标签,布局不同)。
                if (!dualRange) {
                    TargetOverlay(
                        targets = if (live) engine.targets else targets,
                        ownShip = ownShipFlow,
                        rangeScaleNm = display.rangeScaleNm,
                        orientation = display.orientation,              // targets follow the same orientation
                    )
                    // On-PPI data boxes (GAIN/SEA/RAIN top, EBL/VRM bottom, RANGE) — standard IMO layout.
                    PpiDataBoxes(status = status, display = display, model = interaction.model)
                }
                // 模拟模式明显标识(IEC 62388):PPI 顶部居中常驻 "SIMULATION" 横幅,LIVE 时隐藏。
                if (!live) SimulationBanner()
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
                            center = Offset(wPx / 2f, hPx / 2f),
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
      }
    }
}

/** 双量程的单个画面窗格 — 内嵌一幅 PPI,左上角标注雷达通道 + 当前量程。 */
@Composable
private fun DualPane(
    label: String,
    rangeNm: Double,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        content()
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
    }
}

/** 模拟模式横幅 — IEC 62388 要求模拟/测试模式必须明显、常驻标识。PPI 顶部居中,琥珀色。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SimulationBanner() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .align(androidx.compose.ui.Alignment.TopCenter)
            .padding(top = 4.dp)
            .background(androidx.compose.ui.graphics.Color(0xCCB36B00))
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        androidx.compose.material3.Text(
            "● SIMULATION 模拟",
            color = androidx.compose.ui.graphics.Color(0xFFFFF1D6),
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
