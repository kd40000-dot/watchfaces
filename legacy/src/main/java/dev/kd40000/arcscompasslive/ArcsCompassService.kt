@file:Suppress("DEPRECATION")
package dev.kd40000.arcscompasslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
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
    private val complicationFactory = CanvasComplicationFactory { state, listener ->
        CanvasComplicationDrawable(ComplicationDrawable(this), state, listener)
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
            RectF(54f/466, 95f/466, 173f/466, 134f/466),
            RectF(294f/466, 95f/466, 413f/466, 134f/466),
            RectF(42f/466, 350f/466, 161f/466, 389f/466),
            RectF(305f/466, 350f/466, 424f/466, 389f/466)
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
        private val bg = Color.rgb(40, 42, 54) // #282A36
        private val fg = Color.rgb(248, 248, 242)
        private val muted = Color.rgb(98, 114, 164)
        private val track = Color.rgb(68, 71, 90)
        private val purple = Color.rgb(189, 147, 249)
        private val pink = Color.rgb(255, 121, 198)
        private val green = Color.rgb(80, 250, 123)
        private val orange = Color.rgb(255, 184, 108)
        private val cyan = Color.rgb(139, 233, 253)
        private val red = Color.rgb(255, 85, 85)
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
            canvas.restoreToCount(saved)
            if (!ambient) {
                // These bounds are normalized to the real canvas, not the 466-unit drawing.
                for (slot in slots.complicationSlots.values) {
                    if (slot.enabled) {
                        slot.render(canvas, zonedDateTime, renderParameters)
                    }
                }
            }
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
            val colors = intArrayOf(orange, green, purple, cyan)
            val ids = intArrayOf(3, 4, 6, 5)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 10f
            p.strokeCap = Paint.Cap.ROUND
            ranges.forEachIndexed { i, a ->
                val start = a[0] - 90f
                val sweep = a[1] - a[0]
                p.color = track
                canvas.drawArc(rect, start, sweep, false, p)
                val percentage = complicationProgress(ids[i]) ?: if (ids[i] == 4) {
                    (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        .takeIf { it >= 0 }?.div(100f)
                } else null
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
                val x = 233f + cos(angle).toFloat() * 205f
                val y = 233f + sin(angle).toFloat() * 205f
                text(canvas, label, x, y + 9f, 24f, color, Typeface.BOLD)
            }
            // Heading readout on the lower open space, live and suppressed if unavailable.
            text(canvas, if (haveHeading) "%03d°".format(Locale.US, heading.toInt())
                 else "–°", 233f, 415f, 20f, purple)
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
