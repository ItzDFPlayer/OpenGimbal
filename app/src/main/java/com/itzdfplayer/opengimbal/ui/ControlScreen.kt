package com.itzdfplayer.opengimbal.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.itzdfplayer.opengimbal.R
import com.itzdfplayer.opengimbal.accessibility.GimbalAccessibilityService
import com.itzdfplayer.opengimbal.gimbal.GimbalBleClient
import com.itzdfplayer.opengimbal.gimbal.StickController
import com.itzdfplayer.opengimbal.gimbal.StickMode
import com.itzdfplayer.opengimbal.gimbal.StickSettings
import com.itzdfplayer.opengimbal.mapping.MappingStore
import com.itzdfplayer.opengimbal.tracking.TrackingPhase
import com.itzdfplayer.opengimbal.tracking.TrackingStatus
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val PAD_SIZE = 260.dp
private val THUMB_SIZE = 64.dp
private val THUMB_MARGIN = 10.dp

/**
 * Fourth screen: steer the gimbal from the screen instead of the physical knob.
 *
 * The pad is a d-pad and a stick at once, because they are the same gesture with different
 * amounts of care: hold near an edge and it drives that way at full rate, drag a little
 * from the middle and it nudges. That matters because how far the stick sits from the
 * centre is the rate, so a stick exposes a whole range an on/off d-pad cannot.
 */
@Composable
fun ControlScreen(
    client: GimbalBleClient,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE

    var mode by remember {
        mutableStateOf(
            StickMode.entries.firstOrNull { it.name == MappingStore.stickMode(context) }
                ?: StickMode.AIM
        )
    }
    var invertX by remember { mutableStateOf(MappingStore.stickInvertX(context)) }
    var invertY by remember { mutableStateOf(MappingStore.stickInvertY(context)) }
    var appDriven by remember { mutableStateOf(MappingStore.stickAppDriven(context)) }
    var aimRate by remember {
        mutableStateOf(MappingStore.nearestAimRate(MappingStore.aimRate(context)))
    }
    val aimRateIndex = MappingStore.AIM_RATES.indexOf(aimRate).coerceAtLeast(0)
    val aimRateLabel = if (aimRate == aimRate.roundToInt().toFloat()) {
        aimRate.roundToInt().toString()
    } else {
        aimRate.toString()
    }

    // The M01 and M0X families aim with the angle command; the velocity command is only
    // reachable for the models the official app steers that way.
    val offersSteering = !client.connectedModel.onePtz

    // The controller reads its settings on every frame it sends, so a change takes effect
    // immediately rather than needing the loop to be restarted.
    val controller = remember(client) {
        StickController(client) {
            StickSettings(
                mode = StickMode.entries.firstOrNull { it.name == MappingStore.stickMode(context) }
                    ?: StickMode.AIM,
                invertX = MappingStore.stickInvertX(context),
                invertY = MappingStore.stickInvertY(context),
                appDriven = MappingStore.stickAppDriven(context),
                landscape = landscape,
                aimRate = MappingStore.aimRate(context),
            )
        }
    }

    // The steering command leaves the gimbal moving, so leaving mid-press must still stop
    // it. An aim is absolute and needs nothing, which release() takes care of.
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.nav_control),
            subtitle = stringResource(R.string.control_subtitle),
        )

        TrackingCard()

        SectionCard(title = stringResource(R.string.stick_title)) {
            if (!client.isConnected) {
                Text(
                    stringResource(R.string.stick_connect_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                StickPad(
                    enabled = client.isConnected,
                    onMove = { x, y -> controller.deflect(x, y) },
                    onRelease = { controller.release() },
                )
            }

            Text(
                stringResource(
                    if (mode == StickMode.AIM) {
                        R.string.stick_help_aim
                    } else {
                        R.string.stick_help_steer
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mode == StickMode.AIM) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                InfoRow(
                    stringResource(R.string.label_aim),
                    stringResource(
                        R.string.value_aim,
                        controller.aimYaw.roundToInt(),
                        controller.aimPitch.roundToInt(),
                    ),
                )

                Text(
                    stringResource(R.string.stick_sweep_speed, aimRateLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = aimRateIndex.toFloat(),
                    onValueChange = { position ->
                        val index = position.roundToInt()
                            .coerceIn(0, MappingStore.AIM_RATES.lastIndex)
                        aimRate = MappingStore.AIM_RATES[index]
                    },
                    onValueChangeFinished = { MappingStore.setAimRate(context, aimRate) },
                    valueRange = 0f..MappingStore.AIM_RATES.lastIndex.toFloat(),
                    steps = MappingStore.AIM_RATES.size - 2,
                )

                OutlinedButton(onClick = { controller.recentre() }) {
                    Text(stringResource(R.string.action_back_to_level))
                }
            }
        }

        if (offersSteering) {
            SectionCard(title = stringResource(R.string.command_title)) {
                Text(
                    stringResource(R.string.command_description),
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                StickMode.entries.forEach { entry ->
                    ChoiceRow(
                        label = when (entry) {
                            StickMode.AIM -> stringResource(R.string.stick_mode_aim)
                            StickMode.STEER -> stringResource(R.string.stick_mode_steer)
                        },
                        description = when (entry) {
                            StickMode.AIM ->
                                stringResource(R.string.stick_mode_aim_description)
                            StickMode.STEER ->
                                stringResource(R.string.stick_mode_steer_description)
                        },
                        selected = mode == entry,
                        onSelect = {
                            mode = entry
                            MappingStore.setStickMode(context, entry.name)
                        },
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.direction_title)) {
            Text(
                stringResource(R.string.direction_description),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            ToggleRow(
                label = stringResource(R.string.direction_invert_x),
                checked = invertX,
                onCheckedChange = {
                    invertX = it
                    MappingStore.setStickInvertX(context, it)
                },
            )
            ToggleRow(
                label = stringResource(R.string.direction_invert_y),
                checked = invertY,
                onCheckedChange = {
                    invertY = it
                    MappingStore.setStickInvertY(context, it)
                },
            )
            ToggleRow(
                label = stringResource(R.string.direction_app_driven),
                description = stringResource(R.string.direction_app_driven_description),
                checked = appDriven,
                onCheckedChange = {
                    appDriven = it
                    MappingStore.setStickAppDriven(context, it)
                },
            )
        }
    }
}

/**
 * The object-tracking switch, deliberately the first thing on the screen.
 *
 * The floating button itself is drawn by the accessibility service, not here, because that
 * service is already allowed to draw over other apps. This card only records the wish and
 * nudges that service to act on it.
 */
@Composable
private fun TrackingCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MappingStore.trackingOverlay(context)) }
    var strength by remember { mutableStateOf(MappingStore.trackingStrength(context)) }

    // Read straight from the shared status object, so the readout follows the session
    // wherever it is in the app - the overlay is not owned by this screen.
    val phase = TrackingStatus.phase
    val cameraReady = TrackingStatus.cameraReady
    val accessibilityOn = remember(context) { GimbalAccessibilityService.isEnabled(context) }

    SectionCard(title = stringResource(R.string.tracking_title) + " (ALPHA)") {
        Text(
            stringResource(R.string.tracking_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.tracking_trigger_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.tracking_battery_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        ToggleRow(
            label = stringResource(R.string.tracking_button_label),
            description = when {
                !accessibilityOn ->
                    stringResource(R.string.tracking_needs_service)
                phase == TrackingPhase.OFF || !cameraReady ->
                    stringResource(R.string.tracking_button_hint)
                else ->
                    stringResource(R.string.tracking_button_live)
            },
            checked = enabled,
            onCheckedChange = { value ->
                enabled = value
                MappingStore.setTrackingOverlay(context, value)
                GimbalAccessibilityService.instanceOrNull()?.applyTrackingOverlay()
            },
        )

        if (enabled) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            InfoRow(
                stringResource(R.string.label_state),
                stringResource(phase.statusLabel(TrackingStatus.cameraReady)),
            )
            if (phase == TrackingPhase.TRACKING || phase == TrackingPhase.LOST) {
                InfoRow(
                    stringResource(R.string.tracking_label_screen_read),
                    stringResource(
                        R.string.tracking_value_fps,
                        "%.0f".format(TrackingStatus.fps),
                    ),
                )
                InfoRow(
                    stringResource(R.string.tracking_label_match),
                    stringResource(
                        R.string.tracking_value_percent,
                        (TrackingStatus.similarity * 100f).roundToInt(),
                    ),
                )
                InfoRow(
                    stringResource(R.string.tracking_label_object_at),
                    offsetLabel(TrackingStatus.offsetX, TrackingStatus.offsetY),
                )
                InfoRow(
                    stringResource(R.string.tracking_label_correcting),
                    stringResource(
                        R.string.tracking_value_correction,
                        "%.1f".format(TrackingStatus.correctionYaw),
                        "%.1f".format(TrackingStatus.correctionPitch),
                    ),
                )
                InfoRow(
                    stringResource(R.string.tracking_label_measured),
                    stringResource(
                        R.string.tracking_value_px_per_degree,
                        "%.0f".format(TrackingStatus.pixelsPerDegreeHorizontal),
                    ),
                )
                InfoRow(
                    stringResource(R.string.tracking_label_scale),
                    stringResource(
                        R.string.tracking_value_scale,
                        "%.2f".format(TrackingStatus.scale),
                    ),
                )
                Text(
                    stringResource(R.string.tracking_readout_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (TrackingStatus.reversedMeasurements > 2) {
                    Text(
                        stringResource(
                            R.string.tracking_reversed_warning,
                            TrackingStatus.reversedMeasurements,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TrackingStatus.messageRes?.let { messageRes ->
                Text(
                    stringResource(messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                stringResource(R.string.tracking_strength, strength.roundToInt()),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = strength,
                onValueChange = { strength = it },
                onValueChangeFinished = { MappingStore.setTrackingStrength(context, strength) },
                valueRange = 10f..400f,
            )
            Text(
                stringResource(R.string.tracking_strength_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun TrackingPhase.label(): Int = when (this) {
    TrackingPhase.OFF -> R.string.tracking_phase_off
    TrackingPhase.IDLE -> R.string.tracking_phase_idle
    TrackingPhase.CONNECTING -> R.string.tracking_phase_connecting
    TrackingPhase.SELECTING -> R.string.tracking_phase_selecting
    TrackingPhase.TRACKING -> R.string.tracking_phase_tracking
    TrackingPhase.LOST -> R.string.tracking_phase_lost
}

/** The same, plus the one thing that decides whether the button is even there. */
@StringRes
private fun TrackingPhase.statusLabel(cameraReady: Boolean): Int = when {
    this == TrackingPhase.IDLE && !cameraReady ->
        R.string.tracking_phase_waiting_for_camera
    else -> label()
}

/** How far off centre the object is, in words rather than signed pixel counts. */
@Composable
private fun offsetLabel(x: Int, y: Int): String {
    val across = when {
        abs(x) < 20 -> null
        x > 0 -> stringResource(R.string.value_offset_right, x)
        else -> stringResource(R.string.value_offset_left, -x)
    }
    val down = when {
        abs(y) < 20 -> null
        y > 0 -> stringResource(R.string.value_offset_below, y)
        else -> stringResource(R.string.value_offset_above, -y)
    }
    val parts = listOfNotNull(across, down)
    return if (parts.isEmpty()) {
        stringResource(R.string.value_offset_centred)
    } else {
        parts.joinToString(", ")
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(label)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Press-and-hold rather than drag, so touching an arrow behaves like a d-pad button while
 * a short drag from the middle gives fine control. Both are the same code path: wherever
 * the finger is, that is where the stick is.
 */
@Composable
private fun StickPad(
    enabled: Boolean,
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    var padSize by remember { mutableStateOf(0f) }
    var thumb by remember { mutableStateOf(Offset.Zero) }

    val thumbRadius = with(LocalDensity.current) { THUMB_SIZE.toPx() / 2f }
    val margin = with(LocalDensity.current) { THUMB_MARGIN.toPx() }
    val travel = (padSize / 2f - thumbRadius - margin).coerceAtLeast(1f)
    val active = enabled && padSize > 0f

    Box(
        modifier = Modifier
            .size(PAD_SIZE)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (active) 1f else 0.4f)
            )
            .onSizeChanged { padSize = it.width.toFloat() }
            .pointerInput(active, travel) {
                if (!active) return@pointerInput
                val centre = Offset(size.width / 2f, size.height / 2f)

                fun push(position: Offset) {
                    val raw = position - centre
                    val length = hypot(raw.x.toDouble(), raw.y.toDouble()).toFloat()
                    val limited = if (length > travel && length > 0f) {
                        raw * (travel / length)
                    } else {
                        raw
                    }
                    thumb = limited
                    // Screen y grows downwards; the drawing and the stick do not.
                    onMove(limited.x / travel, -limited.y / travel)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    push(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        push(change.position)
                        change.consume()
                    }
                    thumb = Offset.Zero
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PadArrows()

        Box(
            modifier = Modifier
                .offset { IntOffset(thumb.x.roundToInt(), thumb.y.roundToInt()) }
                .size(THUMB_SIZE)
                .clip(CircleShape)
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
        )
    }
}

/** Four direction hints, reusing the dropdown caret rotated to point each way. */
@Composable
private fun PadArrows() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    DirectionArrow(tint, Alignment.TopCenter, rotation = 180f, padding = Modifier.padding(top = 10.dp))
    DirectionArrow(tint, Alignment.BottomCenter, rotation = 0f, padding = Modifier.padding(bottom = 10.dp))
    DirectionArrow(tint, Alignment.CenterStart, rotation = 90f, padding = Modifier.padding(start = 10.dp))
    DirectionArrow(tint, Alignment.CenterEnd, rotation = 270f, padding = Modifier.padding(end = 10.dp))
}

@Composable
private fun DirectionArrow(
    tint: Color,
    alignment: Alignment,
    rotation: Float,
    padding: Modifier,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_drop_down),
            contentDescription = null,
            tint = tint,
            modifier = padding.size(32.dp).rotate(rotation),
        )
    }
}
