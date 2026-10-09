package io.github.unstko.garminexport

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.lifecycle.lifecycleScope
import io.github.unstko.garminexport.garmin.ExportWalker
import io.github.unstko.garminexport.garmin.GarminExport
import io.github.unstko.garminexport.garmin.ImportData
import io.github.unstko.garminexport.hc.Importer
import io.github.unstko.garminexport.hc.RecordMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Survives the activity being recreated, e.g. on rotation. */
private object Loaded {
    var data: ImportData? = null
    var fileKey: String? = null
}

class MainActivity : ComponentActivity() {

    private val permissions = RecordMapper.allTypes.map { HealthPermission.getWritePermission(it) }.toSet()

    private lateinit var log: TextView
    private lateinit var cutOff: EditText
    private lateinit var withSleep: CheckBox
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var pickExport: ActivityResultLauncher<Array<String>>
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            say("Granted ${granted.intersect(permissions).size} of ${permissions.size} write permissions.")
        }
        pickExport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::load) }

        log = TextView(this).apply { setPadding(0, 32, 0, 0); setTextIsSelectable(true) }
        cutOff = EditText(this).apply {
            hint = "Skip from this day on, e.g. 2025-07-12 (optional)"
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        withSleep = CheckBox(this).apply {
            text = "Include sleep (Google Health may keep it even after deletion — import it last)"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(button("1. Choose Garmin export (.zip)") { pickExport.launch(arrayOf("application/zip", "application/octet-stream")) })
            addView(cutOff)
            addView(withSleep)
            addView(button("2. Grant permissions") { requestPermissions.launch(permissions) })
            addView(button("3. Import into Health Connect") { import() })
            addView(button("Rewrite resting heart rate before the day above") { rewriteRestingHeartRate() })
            addView(button("Delete everything this app wrote") { deleteAll() })
            addView(log)
        }
        val root = ScrollView(this).apply { addView(layout) }
        // targetSdk 35+ draws edge to edge: keep the buttons clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        val status = HealthConnectClient.getSdkStatus(this)
        say(if (status == HealthConnectClient.SDK_AVAILABLE) "Health Connect available." else "Health Connect NOT available (status $status).")
        Loaded.data?.let { say("Export already loaded."); summary(it) }
    }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    private fun say(line: String) {
        runOnUiThread { log.append(line + "\n") }
    }

    private fun cutOffDate(): LocalDate? = cutOff.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { _ -> say("Not a date: $it (use YYYY-MM-DD)"); throw IllegalArgumentException() }
    }

    private fun load(uri: Uri) = guarded("Reading the export …") {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) "${it.getString(0)}:${it.getLong(1)}" else null
        } ?: uri.toString()
        val data = withContext(Dispatchers.Default) {
            val export = GarminExport()
            contentResolver.openInputStream(uri)!!.buffered().use { ExportWalker.walk(it) { n, bytes -> export.accept(n, bytes) } }
            say("Read ${export.fitFiles} FIT files.")
            export.finish()
        }
        Loaded.data = data
        Loaded.fileKey = name
        summary(data)
    }

    private fun summary(data: ImportData) {
        val first = data.days.firstOrNull()?.date
        val last = data.days.lastOrNull()?.date
        say("Export covers $first to $last.")
        data.problems.forEach { say("Note: $it") }
        val mapper = RecordMapper(data, runCatching { cutOffDate() }.getOrNull())
        mapper.categories.forEach { say("  ${it.name}: ${it.count}") }
    }

    private fun import() = guarded("Starting the import …") {
        val data = Loaded.data ?: return@guarded say("Choose the export first.")
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) return@guarded say("Grant all permissions first (${granted.intersect(permissions).size} of ${permissions.size}).")
        val before = cutOffDate()
        val mapper = withContext(Dispatchers.Default) { RecordMapper(data, before, withSleep.isChecked) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            Importer(client, getSharedPreferences("progress", MODE_PRIVATE), ::say).run(mapper, "${Loaded.fileKey}|$before")
        } finally {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Writes the resting heart rate before the cut-off day once more, as version 2 of the same
     * records. Google Health showed it only from a certain day on, although Health Connect held
     * all of it; the rewrite shows whether Google Health picks up records that change later.
     */
    private fun rewriteRestingHeartRate() = guarded("Rewriting resting heart rate …") {
        val data = Loaded.data ?: return@guarded say("Choose the export first.")
        val before = cutOffDate() ?: return@guarded say("Enter the day to stop before.")
        val client = HealthConnectClient.getOrCreate(this)
        val records = withContext(Dispatchers.Default) {
            RecordMapper(data, before, withSleep = false, version = 2).categories
                .single { it.type == RestingHeartRateRecord::class }.records().toList()
        }
        records.chunked(500).forEach { client.insertRecords(it) }
        say("Rewrote ${records.size} resting heart rate records before $before as version 2.")
    }

    private fun deleteAll() = guarded("Deleting …") {
        Importer(HealthConnectClient.getOrCreate(this), getSharedPreferences("progress", MODE_PRIVATE), ::say).deleteAll()
    }

    private fun guarded(start: String, block: suspend () -> Unit) {
        if (busy) return say("Still busy.")
        busy = true
        say(start)
        lifecycleScope.launch {
            try {
                block()
            } catch (e: IllegalArgumentException) {
                // message already shown
            } catch (e: Exception) {
                say("Failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }
}
