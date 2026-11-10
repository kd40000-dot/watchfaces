package dev.kd40000.arcscompasslive

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.wear.watchface.complications.ComplicationDataSourceInfo
import java.io.File

/**
 * Keeps the actual chosen complication provider icon available to the
 * WatchFaceService after the on-watch editor has been destroyed.
 *
 * Data sources are free to send SHORT_TEXT with no image. In that case the
 * service's picker icon is the only trustworthy illustration of the choice.
 */
internal object ProviderIconStore {
    private const val TAG = "ArcsProviderIcons"
    private const val PREFS = "selected_complication_provider_icons"
    private const val SIZE = 96
    private fun iconFile(context: Context, slotId: Int) =
        File(context.filesDir, "complication_provider_icon_${slotId}.png")

    fun save(context: Context, slotId: Int, info: ComplicationDataSourceInfo) {
        if (slotId !in 1..6) return
        val component = info.componentName?.flattenToString() ?: ""
        val identity = "$component|${info.name}|${info.type}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("identity_$slotId", null) == identity &&
            iconFile(context, slotId).length() > 0L) return
        val drawable = runCatching { info.icon.loadDrawable(context) }.getOrNull()
        if (drawable == null) {
            clear(context, slotId)
            return
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(canvas)
            context.openFileOutput(iconFile(context,slotId).name,Context.MODE_PRIVATE).use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            prefs.edit().putString("identity_$slotId", identity)
                .putString("component_$slotId",component)
                .putLong("revision_$slotId", System.currentTimeMillis()).apply()
        } catch (error: Exception) {
            Log.w(TAG,"Could not cache icon for slot $slotId",error)
            clear(context,slotId)
        } finally {
            bitmap.recycle()
        }
    }

    fun clear(context: Context, slotId: Int) {
        iconFile(context,slotId).delete()
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove("identity_$slotId").remove("component_$slotId")
            .putLong("revision_$slotId",System.currentTimeMillis()).apply()
    }

    fun read(context: Context, slotId: Int, currentDataSource: ComponentName?): Bitmap? {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val selected=prefs.getString("identity_$slotId",null) ?: return null
        val cachedComponent=prefs.getString("component_$slotId","")
        // If Wear OS reports a different provider than the editor selected,
        // do not show a stale icon from the previous configuration.
        if (currentDataSource != null && !cachedComponent.isNullOrEmpty() &&
            cachedComponent != currentDataSource.flattenToString()) return null
        return try { BitmapFactory.decodeFile(iconFile(context,slotId).absolutePath) }
        catch (error:Exception) {
            Log.w(TAG,"Unable to read chosen icon for slot $slotId: $selected",error)
            null
        }
    }
}
