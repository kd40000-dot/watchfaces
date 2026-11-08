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
import android.text.TextUtils
import android.text.TextPaint
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
            RectF(118f/466, 239f/466, 212f/466, 331f/466),
            RectF(254f/466, 239f/466, 348f/466, 331f/466),
            RectF(76f/466, 96f/466, 166f/466, 146f/466),
            RectF(300f/466, 96f/466, 390f/466, 146f/466),
            RectF(72f/466, 339f/466, 170f/466, 389f/466),
            RectF(296f/466, 339f/466, 394f/466, 389f/466)
        )
        val systemIds = listOf(
            // Six distinct safe system defaults; users can choose their
            // preferred fitness/health sources in the editor.
            SystemDataSources.DATA_SOURCE_STEP_COUNT,
            SystemDataSources.DATA_SOURCE_WATCH_BATTERY,
            SystemDataSources.DATA_SOURCE_UNREAD_NOTIFICATION_COUNT,
            SystemDataSources.DATA_SOURCE_SUNRISE_SUNSET,
            SystemDataSources.DATA_SOURCE_WORLD_CLOCK,
            SystemDataSources.DATA_SOURCE_DATE
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
        // Three separated radial bands: compass outside, arcs in the middle,
        // larger complication labels inside. Distances use 466x466 source units.
        private val compassLabelRadius = 212f
        private val progressArcRadius = 188f
        private val outerLabelRadius = 157f
        private val centerBadgeY = 285f
        private val centerBadgeRadius = 46f
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
            if (!ambient) {
                drawOuterComplicationLabels(canvas)
                drawCenterComplications(canvas)
            }
            canvas.restoreToCount(saved)
            // All six slot bounds remain active for taps and editor selection.
            // Values/icons are custom drawn to avoid stock renderer styling.
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
                Triple(3, 315f, orange),
                Triple(4, 45f, green),
                Triple(6, 137f, cyan),
                Triple(5, 223f, green)
            )
            for ((id, bearing, tint) in data) {
                val slot = slots[id] ?: continue
                if (!slot.enabled) continue

                val complication = slot.complicationData.value
                val value = outerValue(id, complication) ?: continue

                val theta = Math.toRadians(bearing.toDouble())
                val radius = outerLabelRadius
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
                // Larger than v0.4.4, but enforce a hard 94-unit limit so
                // long data-source text never runs into arcs or other elements.
                p.textSize = 24f
                p.textAlign = Paint.Align.LEFT
                val iconSize = 26f
                val gap = 8f
                val maxTextWidth = 60f
                val nominalWidth = p.measureText(value)
                if (nominalWidth > maxTextWidth) {
                    p.textScaleX = (maxTextWidth / nominalWidth).coerceIn(0.72f, 1f)
                }
                val visibleValue = TextUtils.ellipsize(
                    value, TextPaint(p), maxTextWidth, TextUtils.TruncateAt.END
                ).toString()
                val valueWidth = p.measureText(visibleValue)
                val total = iconSize + gap + valueWidth
                val iconX = -total / 2f
                val valueX = iconX + iconSize + gap
                // Fallback vector and provider icons share a 21-unit canvas,
                // so scale the drawing instead of enlarging only bitmaps.
                val iconSaved = canvas.save()
                canvas.translate(iconX, -iconSize / 2f)
                canvas.scale(iconSize / 21f, iconSize / 21f)
                drawOuterIcon(canvas, id, complication, 0f, 0f, tint)
                canvas.restoreToCount(iconSaved)
                p.color = fg
                canvas.drawText(visibleValue, valueX, 8f, p)
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
            // Never copy data from another slot. It caused false duplicates
            // and arcs that did not correspond to their numbers.
            return null
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
            // Fallback icons are matched to the NEW six distinct default
            // data sources. If a custom provider supplies a real icon above,
            // that icon takes precedence.
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.3f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = color
            when (id) {
                1 -> { // Steps / walking
                    p.style = Paint.Style.FILL
                    canvas.drawOval(left + 2f, top + 10f, left + 9f, top + 19f, p)
                    canvas.drawOval(left + 11f, top + 2f, left + 18f, top + 11f, p)
                }
                2 -> { // Watch battery
                    canvas.drawRoundRect(left + 1f, top + 3f,
                        left + 17f, top + 18f, 3f, 3f, p)
                    p.style = Paint.Style.FILL
                    canvas.drawRect(left + 18f, top + 8f,
                        left + 21f, top + 13f, p)
                }
                3 -> { // Notifications, bell
                    canvas.drawArc(left + 4f, top + 3f, left + 17f,
                        top + 17f, 185f, 170f, false, p)
                    canvas.drawLine(left + 3f, top + 17f, left + 18f, top + 17f,p)
                    p.style = Paint.Style.FILL
                    canvas.drawCircle(left + 10.5f,top + 19f,1.3f,p)
                }
                4 -> { // Sunrise / sunset
                    canvas.drawArc(left + 4f, top + 5f, left + 17f,
                        top + 18f, 180f, 180f, false, p)
                    canvas.drawLine(left + 2f,top + 18f,left + 19f,top + 18f,p)
                    canvas.drawLine(left + 10.5f,top + 1f,left + 10.5f,top + 5f,p)
                    canvas.drawLine(left + 2f,top + 6f,left + 5f,top + 9f,p)
                    canvas.drawLine(left + 19f,top + 6f,left + 16f,top + 9f,p)
                }
                5 -> { // World clock
                    canvas.drawCircle(left + 10.5f,top + 10.5f,8f,p)
                    canvas.drawLine(left + 10.5f,top + 10.5f,
                        left + 10.5f,top + 5f,p)
                    canvas.drawLine(left + 10.5f,top + 10.5f,
                        left + 15f,top + 13f,p)
                }
                6 -> { // Date
                    canvas.drawRoundRect(left + 2f, top + 3f,
                        left + 19f, top + 19f, 2f, 2f, p)
                    canvas.drawLine(left + 3f,top + 8f,left + 18f,top + 8f,p)
                    canvas.drawLine(left + 7f,top + 1f,left + 7f,top + 5f,p)
                    canvas.drawLine(left + 14f,top + 1f,left + 14f,top + 5f,p)
                }
            }
            p.style = Paint.Style.FILL
        }

        private fun circles(canvas: Canvas) {
            p.style = Paint.Style.FILL
            p.color = Color.rgb(31, 32, 42) // dark, less prominent than the arc colors
            canvas.drawCircle(165f, centerBadgeY, centerBadgeRadius, p)
            canvas.drawCircle(301f, centerBadgeY, centerBadgeRadius, p)
        }

        private fun drawArcs(canvas: Canvas) {
            val rect = RectF(233f - progressArcRadius, 233f - progressArcRadius,
                233f + progressArcRadius, 233f + progressArcRadius)
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
                // A filled arc must represent THIS slot's value.
                // Ranged/goal complications provide a real range. Plain
                // percentage strings are safe to normalize; other text
                // (date, world clock, notifications) remains an unfilled track.
                val percentage = displayProgress(ids[i])
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

        private fun displayProgress(id: Int): Float? {
            complicationProgress(id)?.let { return it }
            val data = slots[id]?.complicationData?.value as? ShortTextComplicationData
                ?: return null
            val text = data.text.getTextAt(context.resources, Instant.now()).toString().trim()
            // An explicit percent is already a normalized data source. Don't
            // invent progress for a date, count, calendar event, or clock.
            val match = Regex("""^([0-9]{1,3}(?:[.,][0-9]+)?)\s*%$""")
                .matchEntire(text)
            if (match != null) {
                return (match.groupValues[1].replace(',', '.').toFloatOrNull()
                    ?: return null).div(100f).coerceIn(0f,1f)
            }
            // Watch battery sources sometimes supply '76' without '%'.
            // Only accept this when it matches the actual watch battery.
            val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery in 0..100 && text.toIntOrNull() == battery)
                return battery / 100f
            // A step count is progress only if the exact same value appears
            // in the central step-count slot. This preserves the previous
            // 8000-step reference goal for manually duplicated step sources,
            // without treating a date or unrelated integer as steps.
            val center = slots[1]?.complicationData?.value as? ShortTextComplicationData
            if (center != null && text == center.text.getTextAt(context.resources,Instant.now()).toString().trim()) {
                val count = text.filter { it.isDigit() }.toIntOrNull()
                if (count != null && count >= 100)
                    return (count / 8000f).coerceIn(0f,1f)
            }
            return null
        }

        private fun drawCenterComplications(canvas: Canvas) {
            val centers = arrayOf(Triple(1,165f,green), Triple(2,301f,pink))
            for ((id, x, tint) in centers) {
                val slot = slots[id] ?: continue
                if (!slot.enabled) continue
                val data = slot.complicationData.value
                val value = centerValue(data)
                val icon = outerIcon(data)
                // Big, brightly-colored provider icon as on stock Arcs.
                // Short value remains readable, but subordinate to the icon.
                val iconSize = if (value == null) 54f else 47f
                val iconY = if (value == null) centerBadgeY - iconSize/2f else centerBadgeY - 39f
                val save = canvas.save()
                canvas.translate(x - iconSize / 2f,iconY)
                canvas.scale(iconSize/21f,iconSize/21f)
                drawOuterIcon(canvas,id,data,0f,0f,tint)
                canvas.restoreToCount(save)

                if (value != null) {
                    p.reset()
                    p.isAntiAlias = true
                    p.style = Paint.Style.FILL
                    p.textAlign = Paint.Align.CENTER
                    p.typeface = Typeface.create("sans-serif",Typeface.BOLD)
                    p.color = fg
                    p.textSize = 24f
                    val maxWidth = 84f
                    val rawWidth = p.measureText(value)
                    if (rawWidth > maxWidth) {
                        p.textScaleX = (maxWidth/rawWidth).coerceIn(0.72f, 1f)
                    }
                    val visibleValue = TextUtils.ellipsize(
                        value, TextPaint(p), maxWidth, TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(visibleValue, x, centerBadgeY + 38f, p)
                }
            }
        }

        private fun centerValue(data: ComplicationData?): String? {
            val value = when(data) {
                is ShortTextComplicationData ->
                    data.text.getTextAt(context.resources,Instant.now()).toString()
                is RangedValueComplicationData ->
                    data.text?.getTextAt(context.resources,Instant.now())?.toString()
                        ?: String.format(Locale.US,"%.0f",data.value)
                is GoalProgressComplicationData ->
                    data.text?.getTextAt(context.resources,Instant.now())?.toString()
                        ?: String.format(Locale.US,"%.0f",data.value)
                else -> null
            }
            return value?.trim()?.takeIf { it.isNotBlank() && it != "--" && it != "null" }
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
                val x = 233f + cos(angle).toFloat() * compassLabelRadius
                val y = 233f + sin(angle).toFloat() * compassLabelRadius
                text(canvas, label, x, y + 9f, 22f, color, Typeface.BOLD)
            }
            // Heading readout on the lower open space, live and suppressed if unavailable.
            text(canvas, if (haveHeading) "%03d°".format(Locale.US, heading.toInt())
                 else "–°", 233f, 403f, 25f, purple)
            // Red triangle is a fixed 12-o'clock orientation indicator.
            val triangle = Path().apply {
                moveTo(233f, 1f); lineTo(223f, 10f); lineTo(243f, 10f); close()
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
            // Picker, chooser and headless preview render paths can request
            // a highlight-layer callback without a highlight configuration.
            // Never crash the active engine in that case.
            val layer = renderParameters.highlightLayer ?: return
            canvas.drawColor(layer.backgroundTint)
            for (slot in slots.complicationSlots.values) {
                if (slot.enabled) {
                    slot.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
                }
            }
        }

        override fun onDestroy() {
            stopCompass()
            scope.cancel()
            super.onDestroy()
        }
    }
}
