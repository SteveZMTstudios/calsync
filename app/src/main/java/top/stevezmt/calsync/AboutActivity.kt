package top.stevezmt.calsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutActivity : AppCompatActivity() {

    private val backupCreateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                // We expect that lastGeneratedBackupJson is set before launching
                val jsonString = lastGeneratedBackupJson ?: return@registerForActivityResult
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonString.toByteArray())
                    out.flush()
                }
                        SettingsStore.setLastBackupInfo(this, System.currentTimeMillis(), uri.lastPathSegment)
                        updateBackupSubtitle()
                Toast.makeText(this, getString(R.string.toast_backup_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_backup_failed, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                lastGeneratedBackupJson = null
            }
        }
    }

    // hold the JSON while waiting for user to pick save location
    private var lastGeneratedBackupJson: String? = null

    private val backupRestoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val jsonString = inputStream?.bufferedReader().use { it?.readText() }
                if (jsonString != null) {
                    val json = JSONObject(jsonString)
                    // Restore settings
                    if (json.has("keywords")) {
                        try {
                            val obj = json.get("keywords")
                            val kws = when (obj) {
                                is JSONArray -> (0 until obj.length()).map { obj.getString(it) }
                                is String -> {
                                    // tolerate formats like "a,b" or "a，b" or "[a,b]"
                                    val s = obj.trim().removePrefix("[").removeSuffix("]")
                                    s.split(Regex("[,，;；]")).map { it.trim() }.filter { it.isNotEmpty() }
                                }
                                else -> emptyList()
                            }
                            if (kws.isNotEmpty()) SettingsStore.setKeywords(this, kws)
                        } catch (e: Exception) {
                            // fallback: ignore malformed keywords field
                        }
                    }
                    if (json.has("relativeWords")) {
                        try {
                            val obj = json.get("relativeWords")
                            val list = when (obj) {
                                is JSONArray -> (0 until obj.length()).map { obj.getString(it) }
                                is String -> {
                                    val s = obj.trim().removePrefix("[").removeSuffix("]")
                                    s.split(Regex("[,，;；\\n\\r]")).map { it.trim() }.filter { it.isNotEmpty() }
                                }
                                else -> emptyList()
                            }
                            if (list.isNotEmpty()) SettingsStore.setRelativeDateWords(this, list)
                        } catch (e: Exception) {
                            // ignore malformed relativeWords
                        }
                    }
                    if (json.has("customRules")) {
                        try {
                            val obj = json.get("customRules")
                            val list = when (obj) {
                                is JSONArray -> (0 until obj.length()).map { obj.getString(it) }
                                is String -> {
                                    val s = obj.trim().removePrefix("[").removeSuffix("]")
                                    s.split(Regex("[,，;；\\n\\r]")).map { it.trim() }.filter { it.isNotEmpty() }
                                }
                                else -> emptyList()
                            }
                            if (list.isNotEmpty()) SettingsStore.setCustomRules(this, list)
                        } catch (e: Exception) {
                            // ignore malformed customRules
                        }
                    }
                    if (json.has("preferFuture")) {
                        SettingsStore.setPreferFutureOption(this, json.getInt("preferFuture"))
                    }
                    if (json.has("keepAlive")) {
                        SettingsStore.setKeepAliveEnabled(this, json.getBoolean("keepAlive"))
                    }
                    if (json.has("selectedCalendarId")) {
                        SettingsStore.setSelectedCalendar(this, json.getLong("selectedCalendarId"), json.optString("selectedCalendarName"))
                    }
                    if (json.has("selectedApps")) {
                        val apps = json.getJSONArray("selectedApps")
                        val pkgs = mutableListOf<String>()
                        val names = mutableListOf<String>()
                        for (i in 0 until apps.length()) {
                            val app = apps.getJSONObject(i)
                            pkgs += app.optString("packageName")
                            names += app.optString("label")
                        }
                        if (pkgs.isNotEmpty()) SettingsStore.setSelectedSourceApps(this, pkgs, names)
                    }
                    Toast.makeText(this, getString(R.string.toast_config_restored), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_restore_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.about_toolbar)?.let { setSupportActionBar(it) }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_about)
        updateBackupSubtitle()

        // Set version
        val versionText = findViewById<TextView>(R.id.text_version)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = getString(R.string.version_format, packageInfo.versionName)
        } catch (e: Exception) {
            versionText.text = getString(R.string.version_format, "1.0.0")
        }

        // Backup config (save via system file picker)
        findViewById<LinearLayout>(R.id.row_backup).setOnClickListener {
            try {
                val json = JSONObject()
                json.put("keywords", SettingsStore.getKeywords(this))
                json.put("relativeWords", SettingsStore.getRelativeDateWords(this))
                json.put("customRules", SettingsStore.getCustomRules(this))
                json.put("preferFuture", SettingsStore.getPreferFutureOption(this))
                json.put("keepAlive", SettingsStore.isKeepAliveEnabled(this))
                val calendarId = SettingsStore.getSelectedCalendarId(this)
                if (calendarId != null) {
                    json.put("selectedCalendarId", calendarId)
                    json.put("selectedCalendarName", SettingsStore.getSelectedCalendarName(this))
                }
                val selectedPkgs = SettingsStore.getSelectedSourceAppPkgs(this)
                val selectedNames = SettingsStore.getSelectedSourceAppNames(this)
                if (selectedPkgs.isNotEmpty() && selectedNames.isNotEmpty()) {
                    val appsArray = JSONArray()
                    for (i in selectedPkgs.indices) {
                        val obj = JSONObject()
                        obj.put("packageName", selectedPkgs[i])
                        obj.put("label", selectedNames.getOrNull(i) ?: selectedPkgs[i])
                        appsArray.put(obj)
                    }
                    json.put("selectedApps", appsArray)
                }

                // launch the system save dialog
                lastGeneratedBackupJson = json.toString()
                val suggested = "calsync_backup_${System.currentTimeMillis()}.json"
                backupCreateLauncher.launch(suggested)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_backup_failed_simple, e.message), Toast.LENGTH_SHORT).show()
            }
        }

        // Restore config
        findViewById<LinearLayout>(R.id.row_restore).setOnClickListener {
            backupRestoreLauncher.launch("application/json")
        }

        // App settings
        findViewById<LinearLayout>(R.id.row_app_settings).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_open_app_settings), Toast.LENGTH_SHORT).show()
            }
        }

        // Report bug
        findViewById<LinearLayout>(R.id.row_report_bug).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stevezmtstudios/calsync/issues/new/choose"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
            }
        }

        // Open source repo
        findViewById<LinearLayout>(R.id.row_open_source).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stevezmtstudios/calsync"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
            }
        }

        // Contact author
        findViewById<LinearLayout>(R.id.row_contact_author).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://stevezmt.top"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateBackupSubtitle() {
        val sub = findViewById<TextView>(R.id.backup_subtitle)
        val ts = SettingsStore.getLastBackupTimestamp(this)
        if (ts == null) {
            sub?.text = getString(R.string.backup_none)
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val name = SettingsStore.getLastBackupName(this)
        val label = name?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        sub?.text = getString(R.string.backup_format, fmt.format(Date(ts)), label)
    }
}