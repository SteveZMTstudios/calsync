package top.stevezmt.calsync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var keywordsEdit: EditText
    private lateinit var saveBtn: Button
    private var radioGroupPreferFuture: android.widget.RadioGroup? = null
    private var radioAuto: android.widget.RadioButton? = null
    private var radioPrefer: android.widget.RadioButton? = null
    private var radioDisable: android.widget.RadioButton? = null
    private lateinit var permBtn: Button
    private lateinit var notifyBtn: Button
    private lateinit var relativeWordsEdit: EditText
    private lateinit var customRulesEdit: EditText
    private var selectAppBtn: Button? = null
    private var selectAppsBtn: Button? = null
    private var selectedAppsText: android.widget.TextView? = null
    private var fillRuleTemplateBtn: Button? = null
    private var resetRelativeWordsBtn: Button? = null

    private val requestCalendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "已授予日历写入权限", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "日历写入权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        keywordsEdit = findViewById(R.id.edit_keywords)
        radioGroupPreferFuture = findViewById(R.id.radio_prefer_future)
        radioAuto = findViewById(R.id.radio_auto)
        radioPrefer = findViewById(R.id.radio_prefer)
        radioDisable = findViewById(R.id.radio_disable)
        saveBtn = findViewById(R.id.btn_save)
        permBtn = findViewById(R.id.btn_request_permission)
        notifyBtn = findViewById(R.id.btn_open_notification_access)
        relativeWordsEdit = findViewById(R.id.edit_relative_words)
        customRulesEdit = findViewById(R.id.edit_custom_rules)
        // try to locate optional button id without crashing if it's absent in newer layouts
        // Use reflection to read the generated R.id.<name> field at runtime so we don't
        // reference a missing R.id constant at compile time.
        selectAppBtn = try {
            val rIdClass = Class.forName("${packageName}.R\$id")
            val field = rIdClass.getField("btn_select_app")
            val id = field.getInt(null)
            findViewById(id)
        } catch (_: Exception) {
            // If the id/class/field doesn't exist in this build variant/layout, keep null
            null
        }
        selectAppsBtn = findViewById(R.id.btn_select_apps)
        selectedAppsText = findViewById(R.id.text_selected_apps)
        fillRuleTemplateBtn = findViewById(R.id.btn_fill_rule_template)
        resetRelativeWordsBtn = findViewById(R.id.btn_reset_relative_words)
        updateSelectedAppsSummary()

        keywordsEdit.setText(SettingsStore.getKeywords(this).joinToString(","))
        relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
        customRulesEdit.setText(SettingsStore.getCustomRules(this).joinToString(","))

        // initialize preferFuture radio selection
        try {
            when (SettingsStore.getPreferFutureOption(this)) {
                0 -> radioAuto?.isChecked = true
                1 -> radioPrefer?.isChecked = true
                2 -> radioDisable?.isChecked = true
                else -> radioPrefer?.isChecked = true
            }
        } catch (_: Exception) {}

        saveBtn.setOnClickListener {
            val rawK = keywordsEdit.text.toString()
            val kwList = rawK.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            SettingsStore.setKeywords(this, kwList)

            val rawR = relativeWordsEdit.text.toString()
            val rwList = rawR.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            SettingsStore.setRelativeDateWords(this, rwList)

            val rawCR = customRulesEdit.text.toString()
            val crList = rawCR.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            SettingsStore.setCustomRules(this, crList)

            // save preferFuture selection
            try {
                val opt = when {
                    radioAuto?.isChecked == true -> 0
                    radioPrefer?.isChecked == true -> 1
                    radioDisable?.isChecked == true -> 2
                    else -> 1
                }
                SettingsStore.setPreferFutureOption(this, opt)
            } catch (_: Exception) {}

            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        permBtn.setOnClickListener {
            requestCalendarPermissionIfNeeded()
        }

        notifyBtn.setOnClickListener {
            openNotificationAccessSettings()
        }

        selectAppBtn?.setOnClickListener {
            // list installed apps and let user choose
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString() }
                val names = apps.map { pm.getApplicationLabel(it).toString() + " (${it.packageName})" }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择通知来源应用")
                    .setItems(names) { _, which ->
                        val app = apps[which]
                        val label = pm.getApplicationLabel(app).toString()
                        SettingsStore.setSelectedSourceApp(this, app.packageName, label)
                        Toast.makeText(this, "已选择: $label", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this, "无法列出应用: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        selectAppsBtn?.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        fillRuleTemplateBtn?.setOnClickListener {
            if (customRulesEdit.text.isNullOrBlank()) {
                customRulesEdit.setText("(\\d{1,2}月\\d{1,2}日)|(周[一二三四五六日天]\\d{1,2}[:：]\\d{2})|(下周[一二三四五六日天]?)")
                Toast.makeText(this, "已填入示例规则", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已存在内容，未覆盖", Toast.LENGTH_SHORT).show()
            }
        }

        resetRelativeWordsBtn?.setOnClickListener {
            SettingsStore.resetRelativeWords(this)
            relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
            Toast.makeText(this, "日期词已重置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSelectedAppsSummary()
    }

    private fun updateSelectedAppsSummary() {
        val names = SettingsStore.getSelectedSourceAppNames(this)
        selectedAppsText?.text = if (names.isEmpty()) "未选择(默认全部)" else names.joinToString(", ")
    }

    private fun requestCalendarPermissionIfNeeded() {
        requestCalendarPermission.launch(Manifest.permission.WRITE_CALENDAR)
    }

    private fun openNotificationAccessSettings() {
        // Opens the Notification Listener Settings screen
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            // fallback: open app details
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri: Uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
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
