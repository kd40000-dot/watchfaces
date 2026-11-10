package dev.kd40000.arcscompasslive

import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.editor.EditorSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/**
 * On-watch editor for AndroidX legacy Watch Face Service.
 *
 * The face picker starts this activity using WATCH_FACE_EDITOR. Creating a real
 * EditorSession is important: launching a generic configuration screen cannot
 * commit complication provider selections back to the watch face.
 */
class ArcsEditorActivity : ComponentActivity() {
    private var editorSession: EditorSession? = null
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private var selecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = Color.BLACK
        window.statusBarColor = Color.BLACK

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(35), dp(15), dp(35), dp(22))
        }
        root.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val title = TextView(this).apply {
            text = getString(R.string.editor_title)
            textSize = 21f
            setTextColor(Color.rgb(189,147,249))
            gravity = Gravity.CENTER
        }
        column.addView(title, LinearLayout.LayoutParams(-1, dp(48)))

        status = TextView(this).apply {
            text = getString(R.string.editor_loading)
            textSize = 14f
            setTextColor(Color.rgb(248,248,242))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        column.addView(status, LinearLayout.LayoutParams(-1, dp(45)))

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        column.addView(buttons, LinearLayout.LayoutParams(-1,-2))

        val done = Button(this).apply {
            text = getString(R.string.editor_done)
            setOnClickListener {
                // OnWatchEditorSession registers its own lifecycle observer.
                // Finishing the Activity triggers its single authoritative close,
                // which broadcasts the selected providers to the watchface host.
                editorSession?.commitChangesOnClose = true
                android.util.Log.i("ArcsEditor", "Done pressed; committing editor session")
                finish()
            }
        }
        column.addView(done, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(root)

        // Register the activity-result handler during the activity's creation
        // lifecycle, before the complication chooser can be started.
        lifecycleScope.launch {
            runCatching {
                EditorSession.createOnWatchEditorSession(this@ArcsEditorActivity)
            }.onSuccess { session ->
                editorSession = session
                // Changes must be committed by AndroidX when the editor ends.
                session.commitChangesOnClose = true
                android.util.Log.i("ArcsEditor", "EditorSession created; watchface=${session.watchFaceComponentName}")
                showEditor()
                // Recover artwork for providers chosen in previous versions too,
                // including data sources whose live SHORT_TEXT contains no image.
                lifecycleScope.launch {
                    session.complicationsDataSourceInfo.collect { providers ->
                        for ((id, info) in providers) {
                            if (id in 1..6) {
                                if (info != null) {
                                    ProviderIconStore.save(this@ArcsEditorActivity,id,info)
                                } else {
                                    ProviderIconStore.clear(this@ArcsEditorActivity,id)
                                }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                status.text = getString(
                    R.string.editor_failed, error.message ?: error.javaClass.simpleName
                )
                android.util.Log.e("ArcsEditor", "Unable to start editor", error)
            }
        }
    }

    private fun showEditor() {
        status.text = getString(R.string.editor_prompt)
        buttons.visibility = View.VISIBLE
        val names = intArrayOf(
            R.string.slot_1, R.string.slot_2, R.string.slot_3,
            R.string.slot_4, R.string.slot_5, R.string.slot_6
        )
        for (index in names.indices) {
            val id = index + 1
            val button = Button(this).apply {
                text = getString(names[index])
                isAllCaps = false
                setTextColor(Color.rgb(248,248,242))
                setOnClickListener { choose(id) }
            }
            buttons.addView(button, LinearLayout.LayoutParams(-1, dp(52)))
        }
    }

    private fun choose(slotId: Int) {
        val session = editorSession ?: return
        if (selecting) return
        selecting = true
        val complicationPermission =
            "com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA"
        val permissionGranted =
            checkSelfPermission(complicationPermission) == PackageManager.PERMISSION_GRANTED
        status.text = if (permissionGranted) getString(R.string.editor_selecting)
            else getString(R.string.editor_permission_prompt)
        android.util.Log.i("ArcsEditor",
            "Launching chooser for slot $slotId; complication permission=$permissionGranted")
        lifecycleScope.launch {
            try {
                // AndroidX opens ComplicationHelperActivity, which requests
                // RECEIVE_COMPLICATION_DATA before launching the system picker.
                val result = session.openComplicationDataSourceChooser(slotId)
                // A complication can send only text/numbers at runtime.
                // Preserve the actual data source picker icon separately.
                if (result != null) {
                    val selectedInfo = result.complicationDataSourceInfo
                    if (selectedInfo == null) {
                        ProviderIconStore.clear(this@ArcsEditorActivity,slotId)
                    } else {
                        ProviderIconStore.save(this@ArcsEditorActivity,slotId,selectedInfo)
                    }
                }
                val grantedNow =
                    checkSelfPermission(complicationPermission) == PackageManager.PERMISSION_GRANTED
                status.text = when {
                    result != null -> getString(R.string.editor_selected)
                    !grantedNow -> getString(R.string.editor_permission_denied)
                    else -> getString(R.string.editor_cancelled)
                }
                // Keep the Activity and EditorSession alive until Done is pressed.
                // The chooser only changes the *pending* editor selection; closing
                // the session is what commits it to the system watchface service.
                if (result == null) {
                    Toast.makeText(this@ArcsEditorActivity, status.text,
                        Toast.LENGTH_LONG).show()
                }
                android.util.Log.i("ArcsEditor",
                    "Chooser returned for slot $slotId; chosen=${result != null}; permission=$grantedNow")
            } catch (error: Exception) {
                status.text = getString(
                    R.string.editor_failed, error.message ?: error.javaClass.simpleName
                )
                Toast.makeText(this@ArcsEditorActivity, status.text,
                    Toast.LENGTH_LONG).show()
                android.util.Log.e("ArcsEditor", "Chooser failed for slot $slotId", error)
            } finally {
                selecting = false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        android.util.Log.i("ArcsEditor", "onStart selecting=$selecting")
    }

    override fun onStop() {
        android.util.Log.i("ArcsEditor",
            "onStop selecting=$selecting finishing=$isFinishing")
        super.onStop()
    }

    override fun onDestroy() {
        android.util.Log.i("ArcsEditor",
            "onDestroy finishing=$isFinishing selecting=$selecting; AndroidX owns session close")
        // DO NOT call editorSession.close() here. AndroidX
        // createOnWatchEditorSession already installed its own onDestroy
        // lifecycle observer. Double-closing could race the EditorState
        // broadcast and cause provider changes to be lost.
        editorSession = null
        super.onDestroy()
    }
}
