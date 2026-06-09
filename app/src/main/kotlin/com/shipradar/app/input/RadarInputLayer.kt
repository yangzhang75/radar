package com.shipradar.app.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.ScreenPoint

/**
 * Holder for the mutable interaction state, hoisted so the PPI/target layers can read it and the
 * orchestrator can survive recomposition. Backed by Compose snapshot state.
 */
class RadarInteractionState internal constructor(initial: InteractionModel) {
    var model by mutableStateOf(initial)
        internal set

    /** Last pointer position (px), so keyboard acquire/measure has a designated point. */
    internal var lastCursor: Offset? by mutableStateOf(null)

    /** Accumulated pinch factor between discrete range-scale steps. */
    internal var zoomAccum: Float = 1f
}

@Composable
fun rememberRadarInteractionState(
    initial: InteractionModel = InteractionModel(),
): RadarInteractionState = remember { RadarInteractionState(initial) }

/**
 * **The single entry Composable for T2.5** (orchestrator wires it into the `input` slot of
 * `RadarScreen`). A transparent overlay over the PPI operational area that turns **touch, keyboard
 * and mouse** events into the SAME [RadarInteractionController] operations — zoom, pan (off-centre),
 * target select/acquire/cancel, and EBL/VRM/PI adjustment — then:
 *   1. updates [state] (which it also renders as EBL/VRM/PI/selection/cursor overlays), and
 *   2. emits [InteractionEvent]s outward via [onEvent] for the PPI render layer, the target overlay
 *      and the radar controller to consume. It never edits those packages directly.
 *
 * Develop independently of T1.1: pass preview/fake [targets] + viewport params; nothing here needs
 * the live data bus. Geometry is reused from `ui-core` (no re-implementation).
 *
 * CAT 1 three-input equivalence (IEC 62288 §5.5/§5.7): see the per-class handlers below — each
 * delegates to the shared controller, so the behaviour is identical regardless of input class.
 *
 * @param center CCRP screen position in this layer's pixel space (already off-centred by the host,
 *   or pass the geometric centre and let pan drive [InteractionEvent.OffCenterChanged]).
 * @param radiusPx operational-area radius in pixels.
 * @param rangeScaleNm selected range scale, NM (mandatory scale, IEC 62388 §9.4.1).
 */
@Composable
fun RadarInputLayer(
    center: Offset,
    radiusPx: Float,
    orientation: PpiOrientation,
    rangeScaleNm: Double,
    targets: List<TrackedTarget>,
    modifier: Modifier = Modifier,
    ownHeadingDeg: Double? = null,
    ownCourseDeg: Double? = null,
    state: RadarInteractionState = rememberRadarInteractionState(),
    onEvent: (InteractionEvent) -> Unit = {},
) {
    // Keep gesture closures reading fresh viewport values without restarting pointerInput.
    val centerS by rememberUpdatedState(center)
    val radiusS by rememberUpdatedState(radiusPx)
    val orientationS by rememberUpdatedState(orientation)
    val rangeS by rememberUpdatedState(rangeScaleNm)
    val targetsS by rememberUpdatedState(targets)
    val headingS by rememberUpdatedState(ownHeadingDeg)
    val courseS by rememberUpdatedState(ownCourseDeg)
    val onEventS by rememberUpdatedState(onEvent)

    fun ctx(): InteractionContext = InteractionContext.create(
        center = ScreenPoint(centerS.x.toDouble(), centerS.y.toDouble()),
        radiusPx = radiusS.toDouble(),
        orientation = orientationS,
        rangeScaleNm = rangeS,
        ownHeadingDeg = headingS,
        ownCourseDeg = courseS,
        targets = targetsS,
    )

    fun apply(update: RadarInteractionController.Update) {
        state.model = update.model
        update.events.forEach { onEventS(it) }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            // ---- TOUCH + MOUSE: tap = select, double-tap/long-press = acquire ------------------
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { p ->
                        state.lastCursor = p
                        apply(RadarInteractionController.updateCursorReadout(state.model, p.toScreen(), ctx()))
                        apply(RadarInteractionController.selectAt(state.model, p.toScreen(), ctx(), InputClass.TOUCH))
                    },
                    onDoubleTap = { p ->
                        state.lastCursor = p
                        apply(RadarInteractionController.acquireAt(state.model, p.toScreen(), ctx(), InputClass.TOUCH))
                    },
                    onLongPress = { p ->
                        state.lastCursor = p
                        apply(RadarInteractionController.acquireAt(state.model, p.toScreen(), ctx(), InputClass.TOUCH))
                    },
                )
            }
            // ---- TOUCH: pinch = zoom (stepped to mandatory scales), drag = pan or active tool --
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.lastCursor = centroid
                    // Discrete range stepping: accumulate the continuous pinch factor until it
                    // crosses a threshold, then step one mandatory range scale.
                    state.zoomAccum *= zoom
                    when {
                        state.zoomAccum >= PINCH_STEP_IN -> {
                            apply(RadarInteractionController.zoomIn(state.model, ctx(), InputClass.TOUCH))
                            state.zoomAccum = 1f
                        }
                        state.zoomAccum <= PINCH_STEP_OUT -> {
                            apply(RadarInteractionController.zoomOut(state.model, ctx(), InputClass.TOUCH))
                            state.zoomAccum = 1f
                        }
                    }
                    // Pan moves either the active tool (EBL/VRM/PI) or the CCRP off-centre.
                    if (state.model.activeTool is ActiveTool.None) {
                        apply(RadarInteractionController.panByPixels(state.model, pan.x.toDouble(), pan.y.toDouble(), ctx(), InputClass.TOUCH))
                    } else {
                        apply(RadarInteractionController.dragActiveTool(state.model, centroid.toScreen(), ctx(), InputClass.TOUCH))
                    }
                }
            }
            // ---- MOUSE: wheel = zoom ----------------------------------------------------------
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy < 0f) apply(RadarInteractionController.zoomIn(state.model, ctx(), InputClass.MOUSE))
                            else if (dy > 0f) apply(RadarInteractionController.zoomOut(state.model, ctx(), InputClass.MOUSE))
                        } else {
                            event.changes.firstOrNull()?.let { state.lastCursor = it.position }
                        }
                    }
                }
            }
            // ---- KEYBOARD: arrows = pan, +/- = zoom, Enter = acquire, etc. ---------------------
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ke ->
                if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                handleKey(ke.key, state, ::ctx, ::apply)
            },
    ) {
        InputOverlay(
            model = state.model,
            center = center,
            radiusPx = radiusPx,
            orientation = orientation,
            rangeScaleNm = rangeScaleNm,
            ownHeadingDeg = ownHeadingDeg,
            targets = targets,
        )
    }
}

/** Map a key to the equivalent controller operation. Returns true if the key was consumed. */
private fun handleKey(
    key: Key,
    state: RadarInteractionState,
    ctx: () -> InteractionContext,
    apply: (RadarInteractionController.Update) -> Unit,
): Boolean {
    val c = InputClass.KEYBOARD
    val m = state.model
    val frac = RadarInteractionController.Step.PAN_FRACTION
    when (key) {
        Key.Plus, Key.Equals, Key.NumPadAdd -> apply(RadarInteractionController.zoomIn(m, ctx(), c))
        Key.Minus, Key.NumPadSubtract -> apply(RadarInteractionController.zoomOut(m, ctx(), c))
        Key.DirectionUp -> apply(RadarInteractionController.panBy(m, 0.0, -frac, c))
        Key.DirectionDown -> apply(RadarInteractionController.panBy(m, 0.0, frac, c))
        Key.DirectionLeft -> apply(RadarInteractionController.panBy(m, -frac, 0.0, c))
        Key.DirectionRight -> apply(RadarInteractionController.panBy(m, frac, 0.0, c))
        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            val p = state.lastCursor?.toScreen() ?: ctx().projection.center
            apply(RadarInteractionController.acquireAt(m, p, ctx(), c))
        }
        Key.Delete, Key.Backspace -> apply(RadarInteractionController.cancelSelected(m, c))
        Key.Tab -> apply(cycleSelection(m, ctx(), c))
        Key.R -> apply(RadarInteractionController.recenter(m, c))
        Key.E -> apply(RadarInteractionController.toggleEbl(m, 0, c))
        Key.V -> apply(RadarInteractionController.toggleVrm(m, 0, c))
        Key.P -> apply(RadarInteractionController.togglePi(m, 0, c))
        Key.G -> apply(RadarInteractionController.togglePiGroup(m))
        Key.LeftBracket -> apply(RadarInteractionController.nudgeEbl(m, 0, -RadarInteractionController.Step.EBL_DEG))
        Key.RightBracket -> apply(RadarInteractionController.nudgeEbl(m, 0, RadarInteractionController.Step.EBL_DEG))
        Key.Comma -> apply(RadarInteractionController.nudgeVrm(m, 0, -RadarInteractionController.Step.VRM_NM))
        Key.Period -> apply(RadarInteractionController.nudgeVrm(m, 0, RadarInteractionController.Step.VRM_NM))
        else -> return false
    }
    return true
}

/** Cycle the target selection (keyboard equivalent of cursor pick). */
private fun cycleSelection(
    model: InteractionModel, ctx: InteractionContext, ic: InputClass,
): RadarInteractionController.Update {
    val ids = ctx.targets.map { it.id }
    if (ids.isEmpty()) return RadarInteractionController.Update(model)
    val nextId = when (val cur = model.selectedTargetId) {
        null -> ids.first()
        else -> ids[(ids.indexOf(cur) + 1).mod(ids.size)]
    }
    return RadarInteractionController.Update(
        model.copy(selectedTargetId = nextId),
        listOf(InteractionEvent.TargetSelectionChanged(nextId, ic)),
    )
}

private fun Offset.toScreen(): ScreenPoint = ScreenPoint(x.toDouble(), y.toDouble())

/** Pinch factor at which one mandatory range step is taken (in / out). */
private const val PINCH_STEP_IN = 1.30f
private const val PINCH_STEP_OUT = 0.77f
