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
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

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
                // update subtitle to show success and path last segment
                findViewById<TextView>(R.id.backup_subtitle).text = "已保存: ${uri.lastPathSegment}"
                Toast.makeText(this, "备份已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "写入备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "配置已恢复", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "恢复失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("关于")

        // Set version
        val versionText = findViewById<TextView>(R.id.text_version)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "版本 ${packageInfo.versionName}"
        } catch (e: Exception) {
            versionText.text = "版本 1.0.0"
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
                Toast.makeText(this, "备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show()
            }
        }

        // Report bug
        findViewById<LinearLayout>(R.id.row_report_bug).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stevezmtstudios/calsync/issues/new/choose"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        // Open source repo
        findViewById<LinearLayout>(R.id.row_open_source).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stevezmtstudios/calsync"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }

        // Contact author
        findViewById<LinearLayout>(R.id.row_contact_author).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://stevezmt.top"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
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
}