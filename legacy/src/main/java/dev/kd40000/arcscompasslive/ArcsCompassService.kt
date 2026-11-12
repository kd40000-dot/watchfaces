@file:Suppress("DEPRECATION")
package dev.kd40000.arcscompasslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.content.ComponentName
import android.util.Log
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
import android.os.SystemClock
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
        holder, style, state, CanvasType.HARDWARE, 60000L,
        clearWithBackgroundTintBeforeRenderingHighlightLayer = true
    ), SensorEventListener {
        // Rendering is driven by system minute ticks, new complication data,
        // and deliberate sensor invalidations — never a perpetual FPS loop.
        // This also lets the watch return to ambient without an animation timer.
        override fun shouldAnimate(): Boolean = false

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
        private val dateFormatter = DateTimeFormatter.ofPattern(
            "EEE, MMM d", Locale.getDefault()
        )
        private val percentPattern =
            Regex("""^([0-9]{1,3}(?:[.,][0-9]+)?)\s*%$""")
        private val progressRect = RectF(
            233f - 188f, 233f - 188f, 233f + 188f, 233f + 188f
        )
        private val arcSegments = arrayOf(
            floatArrayOf(285f, 352f), floatArrayOf(8f, 75f),
            floatArrayOf(105f, 172f), floatArrayOf(188f, 255f)
        )
        private val arcColors = intArrayOf(orange,green,cyan,green)
        private val arcSlotIds = intArrayOf(3,4,6,5)
        private val outerSlots = arrayOf(
            Triple(3, 315f, orange), Triple(4, 45f, green),
            Triple(6, 137f, cyan), Triple(5, 223f, green)
        )
        private val centerSlots = arrayOf(Triple(1,165f,green), Triple(2,301f,pink))
        private val compassPointer = Path().apply {
            moveTo(233f, 1f); lineTo(223f, 10f); lineTo(243f, 10f); close()
        }
        // Three separated radial bands: compass outside, arcs in the middle,
        // larger complication labels inside. Distances use 466x466 source units.
        private val compassLabelRadius = 212f
        private val progressArcRadius = 188f
        private val outerLabelRadius = 157f
        private val centerBadgeY = 285f
        private val centerBadgeRadius = 46f
        // Provider-supplied icon and chooser metadata cache. Never hold
        // generic footsteps/battery icons based only on slot position.
        private val outerIconCache = mutableMapOf<Int, Pair<Icon, Drawable>>()
        private val providerServiceIconCache = mutableMapOf<String, Drawable?>()
        private val selectedIconCache = mutableMapOf<Int, Pair<String, Bitmap?>>()
        private val iconPrefs = context.getSharedPreferences(
            "selected_complication_provider_icons", Context.MODE_PRIVATE
        )
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // The OnePlus Watch 2 reliably delivered TYPE_ROTATION_VECTOR
        // in v0.4.6. On this firmware the geomagnetic fusion source can be
        // advertised but not deliver usable updates to the legacy face.
        // Prefer the confirmed working path; fall back if it is absent.
        private val rotation =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
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
        private var lastCompassRedrawAtMs = 0L
        // Cap sensor-driven paints without starving the live compass.
        // Sensor events can be more frequent; actual paints max ~4/second.
        private val minCompassRedrawIntervalMs = 250L

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
            // Re-acquire an immediate valid heading when the user wakes the face.
            haveHeading = false
            lastCompassRedrawAtMs = 0L
            listening = if (rotation != null) {
                sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
            } else {
                val a = accel?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                } ?: false
                val m = magnet?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                } ?: false
                if (!(a && m)) sensorManager.unregisterListener(this)
                a && m
            }
        }

        private fun stopCompass() {
            // Always unregister, including partial fallback registration.
            sensorManager.unregisterListener(this)
            listening = false
        }

        override fun onSensorChanged(event: SensorEvent) {
            // The registration lifecycle already guarantees the sensors are
            // disabled for AOD/hidden display. Do not additionally gate on a
            // nullable WatchState snapshot: that suppressed heading events on
            // some legacy engines before the state flow settled.
            if (!listening) return
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
                if (abs(delta) < 2.0f) return
                heading = (heading + delta * 0.40f + 360f) % 360f
            } else {
                heading = azimuth
                haveHeading = true
            }
            val now = SystemClock.elapsedRealtime()
            if (lastCompassRedrawAtMs == 0L ||
                now - lastCompassRedrawAtMs >= minCompassRedrawIntervalMs) {
                lastCompassRedrawAtMs = now
                // Sensor callbacks may arrive off the UI thread. Using the
                // documented thread-safe API avoids silently lost redraws.
                postInvalidate()
            }
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
                val dateString = zonedDateTime.format(dateFormatter)
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
            // Coordinates and colors are immutable between sensor frames.
            for ((id, bearing, tint) in outerSlots) {
                val slot = slots[id] ?: continue
                if (!slot.enabled) continue

                val complication = slot.complicationData.value
                val value = outerValue(id, complication) ?: when(complication) {
                    is MonochromaticImageComplicationData, is SmallImageComplicationData -> ""
                    else -> continue
                }

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

        // The selected data source controls the icon. The provider can send
        // either a monochrome image or a full-color small image along with
        // SHORT_TEXT, RANGED_VALUE or GOAL_PROGRESS.
        private fun outerIcon(data: ComplicationData?): Icon? = when (data) {
            is ShortTextComplicationData ->
                data.monochromaticImage?.image ?: data.smallImage?.image
            is RangedValueComplicationData ->
                data.monochromaticImage?.image ?: data.smallImage?.image
            is GoalProgressComplicationData ->
                data.monochromaticImage?.image ?: data.smallImage?.image
            is MonochromaticImageComplicationData -> data.monochromaticImage.image
            is SmallImageComplicationData -> data.smallImage.image
            else -> null
        }

        private fun chosenProviderIcon(id: Int, data: ComplicationData?): Bitmap? {
            val revision = iconPrefs.getLong("revision_$id", -1L)
            val source = data?.dataSource
            val key = "$revision:${source?.flattenToString() ?: ""}"
            val cached = selectedIconCache[id]
            if (cached?.first == key) return cached.second
            val bitmap = ProviderIconStore.read(context, id, source)
            selectedIconCache[id] = key to bitmap
            return bitmap
        }

        private fun serviceIcon(source: ComponentName?): Drawable? {
            if (source == null) return null
            val key = source.flattenToString()
            if (providerServiceIconCache.containsKey(key)) return providerServiceIconCache[key]
            val drawable = runCatching {
                val pm = context.packageManager
                pm.getServiceInfo(source, 0).loadIcon(pm)?.mutate()
                    ?: pm.getApplicationIcon(source.packageName)?.mutate()
            }.getOrElse {
                Log.d("ArcsIcons","No service icon for $key: ${it.javaClass.simpleName}")
                null
            }
            providerServiceIconCache[key] = drawable
            return drawable
        }

        private fun drawIconDrawable(
            canvas: Canvas, drawable: Drawable,
            left: Float, top: Float, color: Int
        ) {
            val save = canvas.save()
            canvas.translate(left, top)
            drawable.setTint(color)
            drawable.setBounds(0, 0, 21, 21)
            drawable.draw(canvas)
            canvas.restoreToCount(save)
        }

        private fun fallbackTitle(data: ComplicationData?): String? = when(data) {
            is ShortTextComplicationData ->
                data.title?.getTextAt(context.resources, Instant.now())?.toString()
            is RangedValueComplicationData ->
                data.title?.getTextAt(context.resources, Instant.now())?.toString()
            is GoalProgressComplicationData ->
                data.title?.getTextAt(context.resources, Instant.now())?.toString()
            else -> null
        }

        private fun drawOuterIcon(
            canvas: Canvas, id: Int, data: ComplicationData?,
            left: Float, top: Float, color: Int
        ) {
            // 1. Live provider artwork wins, including smallImage if monochrome
            //    wasn't supplied.
            val image = outerIcon(data)
            if (image != null) {
                val previous = outerIconCache[id]
                val drawable = if (previous?.first == image) previous.second
                    else runCatching { image.loadDrawable(context)?.mutate() }.getOrNull()?.also {
                        outerIconCache[id] = image to it
                    }
                if (drawable != null) {
                    drawIconDrawable(canvas,drawable,left,top,color)
                    return
                }
            }
            // 2. Exact icon captured from the selected provider's chooser.
            //    Stored per slot; changes are observed through revision ids.
            val chosen = chosenProviderIcon(id,data)
            if (chosen != null && !chosen.isRecycled) {
                p.reset()
                p.isAntiAlias = true
                p.color = Color.WHITE
                p.colorFilter = PorterDuffColorFilter(color,PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(chosen,null,RectF(left,top,left+21f,top+21f),p)
                p.colorFilter = null
                return
            }
            // 3. Service icon for selections made outside our editor.
            serviceIcon(data?.dataSource)?.let {
                drawIconDrawable(canvas,it,left,top,color)
                return
            }
            // 4. No icon supplied anywhere. Show a neutral data-source glyph,
            //    or the first letter of its actual title. Never show the old
            //    fixed shoes/battery/calendar icon based on the *slot number*.
            p.reset()
            p.isAntiAlias = true
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.3f
            p.strokeCap = Paint.Cap.ROUND
            p.color = color
            val title = fallbackTitle(data)?.trim()
            val initial = title?.firstOrNull { it.isLetter() }
            if (initial != null) {
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.create("sans-serif",Typeface.BOLD)
                p.textSize = 19f
                canvas.drawText(initial.uppercaseChar().toString(),left+10.5f,top+18f,p)
            } else {
                canvas.drawRoundRect(left+2f,top+2f,left+19f,top+19f,4f,4f,p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(left+7f,top+10.5f,1.5f,p)
                canvas.drawCircle(left+14f,top+10.5f,1.5f,p)
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
            p.style = Paint.Style.STROKE
            p.strokeWidth = 10f
            p.strokeCap = Paint.Cap.ROUND
            arcSegments.forEachIndexed { i, a ->
                val start = a[0] - 90f
                val sweep = a[1] - a[0]
                p.color = track
                canvas.drawArc(progressRect, start, sweep, false, p)
                // A filled arc must represent THIS slot's value.
                // Ranged/goal complications provide a real range. Plain
                // percentage strings are safe to normalize; other text
                // (date, world clock, notifications) remains an unfilled track.
                val percentage = displayProgress(arcSlotIds[i])
                if (percentage != null) {
                    p.color = arcColors[i]
                    canvas.drawArc(progressRect, start, sweep * percentage.coerceIn(0f,1f), false, p)
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
            val match = percentPattern.matchEntire(text)
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
            for ((id, x, tint) in centerSlots) {
                val slot = slots[id] ?: continue
                if (!slot.enabled) continue
                val data = slot.complicationData.value
                val value = centerValue(data)
                // Big, brightly-colored data-source icon as on stock Arcs.
                // The icon is resolved dynamically from the provider payload,
                // selected provider metadata or source service, never slot ID.
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
            p.color = red
            canvas.drawPath(compassPointer,p)
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
