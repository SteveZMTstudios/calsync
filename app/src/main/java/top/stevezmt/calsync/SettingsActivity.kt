package top.stevezmt.calsync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
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

        keywordsEdit = findViewById(R.id.edit_keywords)
    saveBtn = findViewById(R.id.btn_save)
        permBtn = findViewById(R.id.btn_request_permission)
        notifyBtn = findViewById(R.id.btn_open_notification_access)
        relativeWordsEdit = findViewById(R.id.edit_relative_words)
        customRulesEdit = findViewById(R.id.edit_custom_rules)
    // try to locate optional button id without referencing a missing R.id constant
    val selectAppId = resources.getIdentifier("btn_select_app", "id", packageName) // legacy single-select id if exists
    selectAppBtn = if (selectAppId != 0) findViewById(selectAppId) else null
    selectAppsBtn = findViewById(R.id.btn_select_apps)
    selectedAppsText = findViewById(R.id.text_selected_apps)
    fillRuleTemplateBtn = findViewById(R.id.btn_fill_rule_template)
    resetRelativeWordsBtn = findViewById(R.id.btn_reset_relative_words)
    updateSelectedAppsSummary()

    keywordsEdit.setText(SettingsStore.getKeywords(this).joinToString(","))
    relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
    customRulesEdit.setText(SettingsStore.getCustomRules(this).joinToString(","))

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
            startActivity(android.content.Intent(this, AppPickerActivity::class.java))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestCalendarPermission.launch(Manifest.permission.WRITE_CALENDAR)
        } else {
            Toast.makeText(this, "Android 版本不需要运行时权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationAccessSettings() {
        // Opens the Notification Listener Settings screen
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            // fallback: open app details
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri: Uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        }
    }
}
