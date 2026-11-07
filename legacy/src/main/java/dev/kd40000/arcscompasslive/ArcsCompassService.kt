@file:Suppress("DEPRECATION")
package dev.kd40000.arcscompasslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasComplicationFactory
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationExperimental
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationStyle
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * OnePlus Watch 2 Arcs-style Dracula watch face with a magnetometer-driven compass bezel.
 *
 * Legacy AndroidX watch-face service: Wear OS 5 support depends on whether the specific
 * upgraded OnePlus Watch 2 firmware continues to permit sideloaded legacy faces.
 */
class ArcsCompassService : WatchFaceService() {
    // The stock renderer draws dark pill backgrounds and thick outlines over the
    // Arcs dial. Keep its icon/text support, but remove its background and border.
    private val complicationFactory = CanvasComplicationFactory { state, listener ->
        val drawable = ComplicationDrawable(this).apply {
            for (style in listOf(activeStyle, ambientStyle)) {
                style.backgroundColor = Color.TRANSPARENT
                style.borderStyle = ComplicationStyle.BORDER_STYLE_NONE
                style.borderWidth = 0
                style.textColor = Color.rgb(248, 248, 242)
                style.titleColor = Color.rgb(189, 147, 249)
                style.iconColor = Color.rgb(248, 248, 242)
                style.textSize = 19
                style.titleSize = 13
                style.rangedValuePrimaryColor = Color.TRANSPARENT
                style.rangedValueSecondaryColor = Color.TRANSPARENT
            }
        }
        CanvasComplicationDrawable(drawable, state, listener)
    }

    override fun createUserStyleSchema() = UserStyleSchema(emptyList())

    @OptIn(ComplicationExperimental::class)
    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        val types = listOf(
            ComplicationType.SHORT_TEXT,
            ComplicationType.RANGED_VALUE,
            ComplicationType.GOAL_PROGRESS,
            ComplicationType.MONOCHROMATIC_IMAGE,
            ComplicationType.SMALL_IMAGE
        )
        val boxes = listOf(
            RectF(109f/466, 261f/466, 221f/466, 355f/466),
            RectF(245f/466, 261f/466, 357f/466, 355f/466),
            RectF(54f/466, 66f/466, 173f/466, 103f/466),
            RectF(294f/466, 66f/466, 413f/466, 103f/466),
            RectF(45f/466, 347f/466, 162f/466, 385f/466),
            RectF(304f/466, 347f/466, 421f/466, 385f/466)
        )
        val systemIds = listOf(
            SystemDataSources.DATA_SOURCE_STEP_COUNT,
            SystemDataSources.DATA_SOURCE_WATCH_BATTERY,
            SystemDataSources.DATA_SOURCE_STEP_COUNT,
            SystemDataSources.DATA_SOURCE_WATCH_BATTERY,
            SystemDataSources.DATA_SOURCE_DATE,
            SystemDataSources.DATA_SOURCE_WATCH_BATTERY
        )
        val resources = intArrayOf(
            R.string.slot_1, R.string.slot_2, R.string.slot_3,
            R.string.slot_4, R.string.slot_5, R.string.slot_6
        )
        val slots = boxes.indices.map { index ->
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                index + 1,
                complicationFactory,
                types,
                DefaultComplicationDataSourcePolicy(
                    systemIds[index], ComplicationType.SHORT_TEXT
                ),
                ComplicationSlotBounds(boxes[index])
            )
                .setNameResourceId(resources[index])
                .setScreenReaderNameResourceId(resources[index])
                .build()
        }
        return ComplicationSlotsManager(slots, currentUserStyleRepository)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ) = WatchFace(
        WatchFaceType.DIGITAL,
        ArcsRenderer(
            surfaceHolder,
            currentUserStyleRepository,
            watchState,
            this,
            complicationSlotsManager
        )
    )

    private class ArcsRenderer(
        holder: SurfaceHolder,
        style: CurrentUserStyleRepository,
        private val state: WatchState,
        private val context: Context,
        private val slots: ComplicationSlotsManager
    ) : Renderer.CanvasRenderer(
        holder, style, state, CanvasType.HARDWARE, 1000L,
        clearWithBackgroundTintBeforeRenderingHighlightLayer = true
    ), SensorEventListener {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bg = Color.BLACK // OLED black, as in the stock Arcs face
        private val fg = Color.rgb(248, 248, 242)
        private val muted = Color.rgb(98, 114, 164)
        private val track = Color.rgb(68, 71, 90)
        private val purple = Color.rgb(189, 147, 249)
        private val pink = Color.rgb(255, 121, 198)
        private val green = Color.rgb(80, 250, 123)
        private val orange = Color.rgb(255, 184, 108)
        private val cyan = Color.rgb(139, 233, 253)
        private val red = Color.rgb(255, 85, 85)
        // Avoid decoding the same provider icons once per second.
        private val outerIconCache = mutableMapOf<Int, Pair<Icon, Drawable>>()
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        private val accel = if(rotation == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        private val magnet = if(rotation == null) sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        private val gravityValues = FloatArray(3)
        private val magneticValues = FloatArray(3)
        private var haveGravity = false
        private var haveMag = false
        private val matrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var listening = false
        private var heading = 0f
        private var haveHeading = false

        init {
            scope.launch {
                combine(state.isVisible, state.isAmbient) { visible, ambient ->
                    visible == true && ambient != true
                }.collect { on ->
                    if (on) startCompass() else stopCompass()
                }
            }
        }

        private fun startCompass() {
            if (listening) return
            listening = if (rotation != null) {
                sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
            } else {
                val a = accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } ?: false
                val m = magnet?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } ?: false
                a && m
            }
        }

        private fun stopCompass() {
            if (!listening) return
            sensorManager.unregisterListener(this)
            listening = false
        }

        override fun onSensorChanged(event: SensorEvent) {
            val success = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    event.values.copyInto(gravityValues, endIndex = 3)
                    haveGravity = true
                    haveGravity && haveMag &&
                        SensorManager.getRotationMatrix(matrix, null, gravityValues, magneticValues)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    event.values.copyInto(magneticValues, endIndex = 3)
                    haveMag = true
                    haveGravity && haveMag &&
                        SensorManager.getRotationMatrix(matrix, null, gravityValues, magneticValues)
                }
                else -> false
            }
            if (!success) return
            SensorManager.getOrientation(matrix, orientation)
            val azimuth = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
            if (haveHeading) {
                val delta = ((azimuth - heading + 540f) % 360f) - 180f
                if (abs(delta) < 1.8f) return
                heading = (heading + delta * 0.25f + 360f) % 360f
            } else {
                heading = azimuth
                haveHeading = true
            }
            invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // SensorManager retains the last reliable heading; wait for next sample.
        }

        override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
            val ambient = renderParameters.drawMode == DrawMode.AMBIENT
            canvas.drawColor(if (ambient) Color.BLACK else bg)
            val scale = min(bounds.width(), bounds.height()) / 466f
            val saved = canvas.save()
            canvas.translate(bounds.exactCenterX() - 233f * scale,
                bounds.exactCenterY() - 233f * scale)
            canvas.scale(scale, scale)
            if (!ambient) {
                drawCompass(canvas)
                drawArcs(canvas)
                circles(canvas)
                val dateString = zonedDateTime.format(
                    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
                )
                text(canvas, dateString, 233f, 137f, 27f, fg)
            }
            val h = if (DateFormat.is24HourFormat(context)) zonedDateTime.hour
                else ((zonedDateTime.hour + 11) % 12 + 1)
            val time = String.format(Locale.getDefault(), "%02d:%02d", h, zonedDateTime.minute)
            text(canvas, time, 233f, 228f, 99f, if (ambient) muted else fg,
                if (ambient) Typeface.NORMAL else Typeface.BOLD)
            if (!ambient) drawOuterComplicationLabels(canvas)
            canvas.restoreToCount(saved)
            if (!ambient) {
                // Only the two center circles use the standard complication
                // renderer. The four edge slots remain configurable/tappable,
                // but their icon and value are rendered tangentially above.
                for (id in 1..2) {
                    val slot = slots[id]
                    if (slot != null && slot.enabled) {
                        slot.render(canvas, zonedDateTime, renderParameters)
                    }
                }
            }
        }

        /**
         * Quadrant labels ride along the arc instead of being drawn into
         * rectangular complication boxes. The angles are measured clockwise
         * from 12 o'clock. For lower quadrants, rotate by angle - 180 so
         * glyphs remain readable left-to-right.
         *
         * Rendering remains independent from the slot bounds; the original
         * slots are still available to edit and launch their providers.
         */
        private fun drawOuterComplicationLabels(canvas: Canvas) {
            // upper-left, upper-right, lower-right, lower-left
            val data = arrayOf(
                Triple(3, 317f, orange),
                Triple(4, 43f, green),
                Triple(6, 137f, cyan),
                Triple(5, 223f, green)
            )
            for ((id, bearing, tint) in data) {
                val slot = slots[id] ?: continue
                if (!slot.enabled) continue

                val complication = slot.complicationData.value
                val value = outerValue(id, complication) ?: continue

                val theta = Math.toRadians(bearing.toDouble())
                val radius = 180f
                val x = 233f + sin(theta).toFloat() * radius
                val y = 233f - cos(theta).toFloat() * radius
                val tangentRotation = if (bearing > 90f && bearing < 270f) {
                    bearing - 180f
                } else {
                    bearing
                }

                val save = canvas.save()
                canvas.translate(x, y)
                canvas.rotate(tangentRotation)

                p.reset()
                p.isAntiAlias = true
                p.style = Paint.Style.FILL
                p.color = fg
                p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                p.textSize = if (value.length > 6) 17f else 21f
                p.textAlign = Paint.Align.LEFT

                val valueWidth = p.measureText(value)
                val iconSize = 21f
                val gap = 7f
                val total = iconSize + gap + valueWidth
                val iconX = -total / 2f
                val valueX = iconX + iconSize + gap
                drawOuterIcon(canvas, id, complication, iconX, -iconSize / 2f, tint)
                p.color = fg
                canvas.drawText(value, valueX, 7f, p)
                canvas.restoreToCount(save)
            }
        }

        private fun outerValue(id: Int, data: ComplicationData?): String? {
            fun clean(raw: String): String? =
                raw.trim().takeIf { it.isNotEmpty() && it != "--" }
            val value = when (data) {
                is ShortTextComplicationData ->
                    clean(data.text.getTextAt(context.resources, Instant.now()).toString())
                is RangedValueComplicationData ->
                    clean(data.text?.getTextAt(context.resources, Instant.now()).toString())
                        ?.takeUnless { it == "null" }
                        ?: String.format(Locale.US, "%.0f", data.value)
                is GoalProgressComplicationData ->
                    clean(data.text?.getTextAt(context.resources, Instant.now()).toString())
                        ?.takeUnless { it == "null" }
                        ?: String.format(Locale.US, "%.0f", data.value)
                else -> null
            }
            if (value != null) return value
            // Without a provider value, show only reliably-known system data.
            return when (id) {
                4, 6 -> batteryProgress()?.let { "${(it * 100).toInt()}%" }
                3 -> slots[1]?.complicationData?.value?.let {
                    if (it is ShortTextComplicationData)
                        clean(it.text.getTextAt(context.resources, Instant.now()).toString())
                    else null
                }
                else -> null
            }
        }

        private fun outerIcon(data: ComplicationData?): Icon? = when (data) {
            is ShortTextComplicationData -> data.monochromaticImage?.image
            is RangedValueComplicationData -> data.monochromaticImage?.image
            is GoalProgressComplicationData -> data.monochromaticImage?.image
            is MonochromaticImageComplicationData -> data.monochromaticImage.image
            is SmallImageComplicationData -> data.smallImage.image
            else -> null
        }

        private fun drawOuterIcon(
            canvas: Canvas, id: Int, data: ComplicationData?,
            left: Float, top: Float, color: Int
        ) {
            val icon = outerIcon(data)
            if (icon != null) {
                val previous = outerIconCache[id]
                val drawable = if (previous?.first == icon) previous.second
                    else runCatching { icon.loadDrawable(context)?.mutate() }.getOrNull()?.also {
                        outerIconCache[id] = icon to it
                    }
                if (drawable != null) {
                    val save = canvas.save()
                    canvas.translate(left, top)
                    drawable.setTint(color)
                    drawable.setBounds(0, 0, 21, 21)
                    drawable.draw(canvas)
                    canvas.restoreToCount(save)
                    return
                }
            }
            // Fallback vector glyphs for providers without an icon.
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.strokeCap = Paint.Cap.ROUND
            p.color = color
            if (id == 4 || id == 6) {
                canvas.drawRoundRect(left + 1f, top + 3f, left + 17f,
                    top + 18f, 3f, 3f, p)
                p.style = Paint.Style.FILL
                canvas.drawRect(left + 18f, top + 8f, left + 21f,
                    top + 13f, p)
            } else if (id == 5) {
                canvas.drawRoundRect(left + 2f, top + 3f, left + 19f,
                    top + 19f, 2f, 2f, p)
                canvas.drawLine(left + 3f, top + 8f, left + 18f, top + 8f, p)
            } else {
                // Two minimal footprints, recognizable at ~20px.
                p.style = Paint.Style.FILL
                canvas.drawOval(left + 2f, top + 10f, left + 9f, top + 19f, p)
                canvas.drawOval(left + 11f, top + 2f, left + 18f, top + 11f, p)
            }
            p.style = Paint.Style.FILL
        }

        private fun circles(canvas: Canvas) {
            p.style = Paint.Style.FILL
            p.color = track
            canvas.drawCircle(165f, 309f, 55f, p)
            canvas.drawCircle(301f, 309f, 55f, p)
        }

        private fun drawArcs(canvas: Canvas) {
            val rect = RectF(39f, 39f, 427f, 427f)
            val ranges = arrayOf(
                floatArrayOf(285f, 352f), floatArrayOf(8f, 75f),
                floatArrayOf(105f, 172f), floatArrayOf(188f, 255f)
            )
            val colors = intArrayOf(orange, green, cyan, green)
            val ids = intArrayOf(3, 4, 6, 5)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 10f
            p.strokeCap = Paint.Cap.ROUND
            ranges.forEachIndexed { i, a ->
                val start = a[0] - 90f
                val sweep = a[1] - a[0]
                p.color = track
                canvas.drawArc(rect, start, sweep, false, p)
                // SHORT_TEXT steps/battery are the default system providers on
                // OnePlus Watch 2; they do not expose RANGED_VALUE data.
                // Use known central step count / system battery as fallbacks.
                val percentage = complicationProgress(ids[i]) ?: when (ids[i]) {
                    3, 5 -> stepProgress()
                    4, 6 -> batteryProgress()
                    else -> null
                }
                if (percentage != null) {
                    p.color = colors[i]
                    canvas.drawArc(rect, start, sweep * percentage.coerceIn(0f,1f), false, p)
                }
            }
            p.style = Paint.Style.FILL
        }

        private fun complicationProgress(id: Int): Float? {
            val data = slots[id]?.complicationData?.value ?: return null
            return when (data) {
                is RangedValueComplicationData ->
                    if (data.max > data.min) (data.value - data.min) / (data.max - data.min)
                    else null
                is GoalProgressComplicationData ->
                    if (data.targetValue > 0f) data.value / data.targetValue else null
                else -> null
            }?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        }

        private fun batteryProgress(): Float? {
            val value = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (value in 0..100) value / 100f else null
        }

        private fun stepProgress(): Float? {
            // Center step complication is the most reliable readout of the
            // user's chosen step provider. Default reference goal: 8000 steps.
            for (id in intArrayOf(1, 3)) {
                val data = slots[id]?.complicationData?.value
                if (data is RangedValueComplicationData || data is GoalProgressComplicationData) {
                    val progress = complicationProgress(id)
                    if (progress != null) return progress
                }
                if (data is ShortTextComplicationData) {
                    val raw = data.text.getTextAt(context.resources, Instant.now()).toString()
                    val count = raw.filter { it.isDigit() }.toIntOrNull() ?: continue
                    // Ignore unrelated SHORT_TEXT values such as "07" (day).
                    if (count in 0..100000) return (count / 8000f).coerceIn(0f, 1f)
                }
            }
            return null
        }

        private fun drawCompass(canvas: Canvas) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.color = muted
            canvas.drawCircle(233f, 233f, 224f, p)
            p.style = Paint.Style.FILL
            // With no sensor reading, show a neutral calibrated ring, not a false heading.
            val offset = if (haveHeading) heading else 0f
            for (bearing in 0 until 360 step 15) {
                if (bearing % 90 == 0) continue
                val angle = Math.toRadians((bearing - offset - 90).toDouble())
                val outer = 221f
                val inner = if (bearing % 45 == 0) 212f else 217f
                val sx = 233f + cos(angle).toFloat() * inner
                val sy = 233f + sin(angle).toFloat() * inner
                val ex = 233f + cos(angle).toFloat() * outer
                val ey = 233f + sin(angle).toFloat() * outer
                p.color = if (bearing % 45 == 0) fg else muted
                p.strokeWidth = if (bearing % 45 == 0) 2.8f else 1.6f
                canvas.drawLine(sx, sy, ex, ey, p)
            }
            val labels = listOf(
                Triple(0f,"N",red), Triple(90f,"E",fg),
                Triple(180f,"S",fg), Triple(270f,"W",fg)
            )
            for ((bearing, label, color) in labels) {
                val angle = Math.toRadians((bearing - offset - 90).toDouble())
                val x = 233f + cos(angle).toFloat() * 207f
                val y = 233f + sin(angle).toFloat() * 205f
                text(canvas, label, x, y + 9f, 20f, color, Typeface.BOLD)
            }
            // Heading readout on the lower open space, live and suppressed if unavailable.
            text(canvas, if (haveHeading) "%03d°".format(Locale.US, heading.toInt())
                 else "–°", 233f, 400f, 20f, purple)
            // Red triangle is a fixed 12-o'clock orientation indicator.
            val triangle = Path().apply {
                moveTo(233f, 8f); lineTo(225f, 25f); lineTo(241f, 25f); close()
            }
            p.color = red
            canvas.drawPath(triangle,p)
        }

        private fun text(
            canvas: Canvas, value: String,
            x: Float, baseline: Float, size: Float, color: Int,
            style: Int = Typeface.NORMAL
        ) {
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif", style)
            p.textSize = size
            p.color = color
            canvas.drawText(value, x, baseline, p)
        }

        override fun renderHighlightLayer(
            canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime
        ) {
            canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
            for (slot in slots.complicationSlots.values) {
                slot.renderHighlightLayer(canvas,zonedDateTime,renderParameters)
            }
        }

        override fun onDestroy() {
            stopCompass()
            scope.cancel()
            super.onDestroy()
        }
    }
}
