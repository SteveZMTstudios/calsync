package top.stevezmt.calsync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView

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
    private lateinit var reminderMinutesEdit: EditText
    private var selectAppBtn: Button? = null
    private var selectAppsBtn: Button? = null
    private var selectedAppsText: android.widget.TextView? = null
    private var fillRuleTemplateBtn: Button? = null
    private var resetRelativeWordsBtn: Button? = null

    private var parseEngineInput: MaterialAutoCompleteTextView? = null
    private var eventEngineInput: MaterialAutoCompleteTextView? = null
    private var aiModelPathEdit: EditText? = null
    private var pickAiModelBtn: Button? = null
    private var aiPromptEdit: EditText? = null
    private var aiSection: android.view.View? = null
    private var guessBeforeParseSwitch: com.google.android.material.materialswitch.MaterialSwitch? = null
    private var fabSave: com.google.android.material.floatingactionbutton.FloatingActionButton? = null

    private val pickAiModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
            // Some providers don't allow persistable permissions; best effort.
        }
        val uriStr = uri.toString()
        aiModelPathEdit?.setText(uriStr)
        SettingsStore.setAiGgufModelUri(this, uriStr)
        Toast.makeText(this, "已选择模型文件", Toast.LENGTH_SHORT).show()
    }

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

        findViewById<MaterialToolbar>(R.id.settings_toolbar)?.let { setSupportActionBar(it) }

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
        reminderMinutesEdit = findViewById(R.id.edit_reminder_minutes)
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

        parseEngineInput = findViewById(R.id.input_parse_engine)
        eventEngineInput = findViewById(R.id.input_event_engine)
        aiModelPathEdit = findViewById(R.id.edit_ai_model_path)
        pickAiModelBtn = findViewById(R.id.btn_pick_ai_model)
        aiPromptEdit = findViewById(R.id.edit_ai_prompt)
        aiSection = findViewById(R.id.ai_section)
        guessBeforeParseSwitch = findViewById(R.id.switch_guess_before_parse)
        fabSave = findViewById(R.id.fab_save)

        updateSelectedAppsSummary()

        keywordsEdit.setText(SettingsStore.getKeywords(this).joinToString(","))
        relativeWordsEdit.setText(SettingsStore.getRelativeDateWords(this).joinToString(","))
        customRulesEdit.setText(SettingsStore.getCustomRules(this).joinToString(","))
        reminderMinutesEdit.setText(SettingsStore.getReminderMinutes(this).toString())
        refreshPreferFutureSelection()

        setupParsingEngineUi()
        setupAiModelUi()
        setupBatterySaverUi()

        saveBtn.setOnClickListener {
            saveAllSettings()
        }

        fabSave?.setOnClickListener {
            saveAllSettings()
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

        pickAiModelBtn?.setOnClickListener {
            // SAF picker: keep it permissive, validate extension later when runtime exists.
            pickAiModelLauncher.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateSelectedAppsSummary()
        refreshPreferFutureSelection()

        // Check GMS for ML Kit
        if (!isGooglePlayServicesAvailable()) {
            if (SettingsStore.getParsingEngine(this) == ParseEngine.ML_KIT) {
                SettingsStore.setParsingEngine(this, ParseEngine.BUILTIN)
                parseEngineInput?.setText(ParseEngine.BUILTIN.displayName, false)
                eventEngineInput?.setText(EventParseEngine.BUILTIN.displayName, false)
                val msg = if (BuildConfig.FLAVOR == "foss") "FOSS版本不提供闭源组件" else getString(R.string.error_google_play_required)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        if (BuildConfig.FLAVOR == "foss") return false
        return try {
            packageManager.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateSelectedAppsSummary() {
        val names = SettingsStore.getSelectedSourceAppNames(this)
        selectedAppsText?.text = if (names.isEmpty()) getString(R.string.apps_none) else names.joinToString(", ")
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

    private fun refreshPreferFutureSelection() {
        try {
            when (SettingsStore.getPreferFutureOption(this)) {
                0 -> radioAuto?.isChecked = true
                1 -> radioPrefer?.isChecked = true
                2 -> radioDisable?.isChecked = true
                else -> radioPrefer?.isChecked = true
            }
        } catch (_: Exception) {}
    }

    private fun setupParsingEngineUi() {
        try {
            val isFoss = BuildConfig.FLAVOR == "foss"
            val isGmsAvailable = isGooglePlayServicesAvailable()
            val engines = ParseEngine.entries
            val adapter = object : android.widget.ArrayAdapter<ParseEngine>(this, R.layout.item_engine_option, engines) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_engine_option, parent, false)
                    val item = getItem(position)
                    view.findViewById<android.widget.TextView>(R.id.text_title).text = item?.displayName
                    view.findViewById<android.widget.TextView>(R.id.text_description).text = item?.description
                    
                    if (item == ParseEngine.ML_KIT && !isGmsAvailable) {
                        view.alpha = 0.5f
                    } else {
                        view.alpha = 1.0f
                    }
                    return view
                }

                override fun isEnabled(position: Int): Boolean {
                    val item = getItem(position)
                    if (isFoss && item == ParseEngine.ML_KIT) return true
                    return !(item == ParseEngine.ML_KIT && !isGmsAvailable)
                }
            }
            parseEngineInput?.setAdapter(adapter)
            parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)

            parseEngineInput?.setOnItemClickListener { _, _, pos, _ ->
                val picked = engines.getOrNull(pos) ?: ParseEngine.BUILTIN
                
                if (isFoss && picked == ParseEngine.ML_KIT) {
                    Toast.makeText(this, "FOSS版本不提供闭源组件", Toast.LENGTH_SHORT).show()
                    parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
                    return@setOnItemClickListener
                }

                // Show warning dialog if selecting AI_GGUF
                if (picked == ParseEngine.AI_GGUF) {
                    showAiWarningDialog { confirmed ->
                        if (confirmed) {
                            SettingsStore.setParsingEngine(this@SettingsActivity, picked)
                            // Mirror to event engine per rule
                            eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this@SettingsActivity).displayName, false)
                            syncUiForEngineCoupling()
                        } else {
                            // Revert to previous selection
                            parseEngineInput?.setText(SettingsStore.getParsingEngine(this@SettingsActivity).displayName, false)
                        }
                    }
                } else {
                    SettingsStore.setParsingEngine(this, picked)
                    // Mirror to event engine per rule
                    eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
                    syncUiForEngineCoupling()
                }
            }
        } catch (_: Throwable) {}

        try {
            val isFoss = BuildConfig.FLAVOR == "foss"
            val isGmsAvailable = isGooglePlayServicesAvailable()
            val engines = EventParseEngine.entries
            val adapter = object : android.widget.ArrayAdapter<EventParseEngine>(this, R.layout.item_engine_option, engines) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_engine_option, parent, false)
                    val item = getItem(position)
                    view.findViewById<android.widget.TextView>(R.id.text_title).text = item?.displayName
                    view.findViewById<android.widget.TextView>(R.id.text_description).text = item?.description
                    
                    if (item == EventParseEngine.ML_KIT && !isGmsAvailable) {
                        view.alpha = 0.5f
                    } else {
                        view.alpha = 1.0f
                    }
                    return view
                }

                override fun isEnabled(position: Int): Boolean {
                    val item = getItem(position)
                    if (isFoss && item == EventParseEngine.ML_KIT) return true
                    return !(item == EventParseEngine.ML_KIT && !isGmsAvailable)
                }
            }
            eventEngineInput?.setAdapter(adapter)
            eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)

            eventEngineInput?.setOnItemClickListener { _, _, pos, _ ->
                val picked = engines.getOrNull(pos) ?: EventParseEngine.BUILTIN

                if (isFoss && picked == EventParseEngine.ML_KIT) {
                    Toast.makeText(this, "FOSS版本不提供闭源组件", Toast.LENGTH_SHORT).show()
                    eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
                    return@setOnItemClickListener
                }

                // Show warning dialog if selecting AI_GGUF
                if (picked == EventParseEngine.AI_GGUF) {
                    showAiWarningDialog { confirmed ->
                        if (confirmed) {
                            SettingsStore.setEventParsingEngine(this@SettingsActivity, picked)
                            // Mirror back to datetime engine per rule
                            parseEngineInput?.setText(SettingsStore.getParsingEngine(this@SettingsActivity).displayName, false)
                            syncUiForEngineCoupling()
                        } else {
                            // Revert to previous selection
                            eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this@SettingsActivity).displayName, false)
                        }
                    }
                } else {
                    SettingsStore.setEventParsingEngine(this, picked)
                    // Mirror back to datetime engine per rule
                    parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
                    syncUiForEngineCoupling()
                }
            }
        } catch (_: Throwable) {}

        syncUiForEngineCoupling()
    }

    private fun setupAiModelUi() {
        try {
            aiModelPathEdit?.setText(SettingsStore.getAiGgufModelUri(this) ?: "")
            aiPromptEdit?.setText(SettingsStore.getAiSystemPrompt(this))
            syncUiForEngineCoupling()
        } catch (_: Throwable) {}
    }

    private fun setupBatterySaverUi() {
        try {
            guessBeforeParseSwitch?.isChecked = SettingsStore.isGuessBeforeParseEnabled(this)
            guessBeforeParseSwitch?.setOnCheckedChangeListener { _, isChecked ->
                SettingsStore.setGuessBeforeParseEnabled(this, isChecked)
            }
        } catch (_: Throwable) {}
    }

    private fun syncUiForEngineCoupling() {
        val isAi = SettingsStore.getParsingEngine(this) == ParseEngine.AI_GGUF
        // Show AI config only when AI selected
        aiSection?.visibility = if (isAi) android.view.View.VISIBLE else android.view.View.GONE
        // When AI is selected, event engine is fixed to AI in SettingsStore
        eventEngineInput?.isEnabled = !isAi
    }

    private fun saveAllSettings() {
        val rawK = keywordsEdit.text.toString()
        val kwList = rawK.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        SettingsStore.setKeywords(this, kwList)

        val rawR = relativeWordsEdit.text.toString()
        val rwList = rawR.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        SettingsStore.setRelativeDateWords(this, rwList)

        val rawCR = customRulesEdit.text.toString()
        val crList = rawCR.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        SettingsStore.setCustomRules(this, crList)

        val reminderMins = reminderMinutesEdit.text.toString().toIntOrNull() ?: 10
        SettingsStore.setReminderMinutes(this, reminderMins)

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

        // save parsing engine selections (SettingsStore enforces AI coupling)
        try {
            parseEngineInput?.text?.toString()?.let { label ->
                val engine = ParseEngine.entries.firstOrNull { it.displayName == label } ?: ParseEngine.BUILTIN
                SettingsStore.setParsingEngine(this, engine)
            }
        } catch (_: Exception) {}
        try {
            eventEngineInput?.text?.toString()?.let { label ->
                val engine = EventParseEngine.entries.firstOrNull { it.displayName == label } ?: EventParseEngine.BUILTIN
                SettingsStore.setEventParsingEngine(this, engine)
            }
        } catch (_: Exception) {}

        // save battery saver
        try {
            SettingsStore.setGuessBeforeParseEnabled(this, guessBeforeParseSwitch?.isChecked == true)
        } catch (_: Exception) {}

        // save AI prompt and model uri (model uri is typically saved on pick)
        try {
            val prompt = aiPromptEdit?.text?.toString() ?: ""
            SettingsStore.setAiSystemPrompt(this, prompt)
        } catch (_: Exception) {}
        try {
            val uri = aiModelPathEdit?.text?.toString()?.takeIf { it.isNotBlank() }
            SettingsStore.setAiGgufModelUri(this, uri)
        } catch (_: Exception) {}

        // Refresh UI to reflect coupling/visibility
        try {
            parseEngineInput?.setText(SettingsStore.getParsingEngine(this).displayName, false)
            eventEngineInput?.setText(SettingsStore.getEventParsingEngine(this).displayName, false)
            syncUiForEngineCoupling()
        } catch (_: Throwable) {}

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun showAiWarningDialog(callback: (Boolean) -> Unit) {
        try {

            AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.dialog_title_warning)
                .setMessage(R.string.ai_warning_message)
                .setPositiveButton(R.string.btn_confirm) { dialog, _ ->
                    dialog.dismiss()
                    callback(true)
                }
                .setNegativeButton(R.string.btn_cancel) { dialog, _ ->
                    dialog.dismiss()
                    callback(false)
                }
                .setCancelable(false)
                .show()
        } catch (_: Throwable) {
            callback(false)
        }
    }
}

