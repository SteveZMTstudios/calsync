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
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

class SettingsActivity : AppCompatActivity() {

    private lateinit var keywordsEdit: EditText
    private lateinit var saveBtn: Button
    private var radioGroupPreferFuture: android.widget.RadioGroup? = null
    private var radioAuto: android.widget.RadioButton? = null
    private var radioPrefer: android.widget.RadioButton? = null
    private var radioDisable: android.widget.RadioButton? = null
    private lateinit var permBtn: Button
    private lateinit var notifyBtn: Button
    private lateinit var reminderMinutesEdit: EditText
    private var selectAppBtn: Button? = null
    private var selectAppsBtn: Button? = null
    private var selectedAppsText: android.widget.TextView? = null

    private var parseEngineInput: MaterialAutoCompleteTextView? = null
    private var eventEngineInput: MaterialAutoCompleteTextView? = null
    private var aiModelPathEdit: EditText? = null
    private var pickAiModelBtn: Button? = null
    private var aiPromptEdit: EditText? = null
    private var aiSection: android.view.View? = null
    private var queueModeInput: MaterialAutoCompleteTextView? = null
    private var queueTimeoutEdit: EditText? = null
    private var queueMaxMessagesEdit: EditText? = null
    private var fuzzyPairsContainer: android.widget.LinearLayout? = null
    private var addFuzzyPairBtn: MaterialButton? = null
    private var resetFuzzyPairsBtn: MaterialButton? = null
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

        parseEngineInput = findViewById(R.id.input_parse_engine)
        eventEngineInput = findViewById(R.id.input_event_engine)
        aiModelPathEdit = findViewById(R.id.edit_ai_model_path)
        pickAiModelBtn = findViewById(R.id.btn_pick_ai_model)
        aiPromptEdit = findViewById(R.id.edit_ai_prompt)
        aiSection = findViewById(R.id.ai_section)
        queueModeInput = findViewById(R.id.input_notification_queue_mode)
        queueTimeoutEdit = findViewById(R.id.edit_notification_queue_timeout)
        queueMaxMessagesEdit = findViewById(R.id.edit_notification_queue_max_messages)
        fuzzyPairsContainer = findViewById(R.id.container_fuzzy_time_pairs)
        addFuzzyPairBtn = findViewById(R.id.btn_add_fuzzy_time_pair)
        resetFuzzyPairsBtn = findViewById(R.id.btn_reset_fuzzy_time_pairs)
        fabSave = findViewById(R.id.fab_save)

        updateSelectedAppsSummary()

        keywordsEdit.setText(SettingsStore.getKeywords(this).joinToString(","))
        reminderMinutesEdit.setText(SettingsStore.getReminderMinutes(this).toString())
        refreshPreferFutureSelection()

        setupParsingEngineUi()
        setupAiModelUi()
        setupNotificationQueueUi()
        setupFuzzyTimePairsUi()

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

    private fun setupNotificationQueueUi() {
        try {
            val modes = NotificationQueueMode.entries
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, modes)
            queueModeInput?.setAdapter(adapter)
            queueModeInput?.setText(SettingsStore.getNotificationQueueMode(this).displayName, false)
            queueModeInput?.setOnItemClickListener { _, _, pos, _ ->
                SettingsStore.setNotificationQueueMode(this, modes.getOrNull(pos) ?: NotificationQueueMode.OFF)
            }
            queueTimeoutEdit?.setText(SettingsStore.getNotificationQueueTimeoutSeconds(this).toString())
            queueMaxMessagesEdit?.setText(SettingsStore.getNotificationQueueMaxMessages(this).toString())
        } catch (_: Throwable) {}
    }

    private fun setupFuzzyTimePairsUi() {
        try {
            fuzzyPairsContainer?.removeAllViews()
            SettingsStore.getFuzzyTimePairs(this).forEach { addFuzzyTimePairRow(it.word, it.minutesOfDay) }
            addFuzzyPairBtn?.setOnClickListener {
                addFuzzyTimePairRow("", 13 * 60)
            }
            resetFuzzyPairsBtn?.setOnClickListener {
                SettingsStore.resetFuzzyTimePairs(this)
                fuzzyPairsContainer?.removeAllViews()
                SettingsStore.getFuzzyTimePairs(this).forEach { addFuzzyTimePairRow(it.word, it.minutesOfDay) }
                Toast.makeText(this, "含糊时间词对已重置", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}
    }

    private fun addFuzzyTimePairRow(word: String, minutes: Int) {
        val container = fuzzyPairsContainer ?: return
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val wordLayout = TextInputLayout(this).apply {
            hint = "含糊词"
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        val wordEdit = TextInputEditText(wordLayout.context).apply {
            setText(word)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        wordLayout.addView(wordEdit)

        val spacer = android.widget.Space(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(8), 1)
        }

        val timeLayout = TextInputLayout(this).apply {
            hint = "默认时间"
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.access_time_24)
            setEndIconContentDescription("选择默认时间")
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val timeInput = TextInputEditText(timeLayout.context).apply {
            inputType = android.text.InputType.TYPE_NULL
            isFocusable = false
            isCursorVisible = false
            setSingleLine(true)
            setText(SettingsStore.formatMinutesOfDay(minutes))
            setOnClickListener {
                showFuzzyTimePicker(this)
            }
        }
        timeLayout.addView(timeInput)
        timeLayout.setEndIconOnClickListener {
            showFuzzyTimePicker(timeInput)
        }

        val deleteButton = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.delete_24)
            background = null
            contentDescription = "删除词对"
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            ).apply { leftMargin = dp(8) }
            setOnClickListener { container.removeView(row) }
        }

        row.addView(wordLayout)
        row.addView(spacer)
        row.addView(timeLayout)
        row.addView(deleteButton)
        container.addView(row)
    }

    private fun readFuzzyTimePairsFromUi(): List<SettingsStore.FuzzyTimePair> {
        val container = fuzzyPairsContainer ?: return emptyList()
        val out = mutableListOf<SettingsStore.FuzzyTimePair>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val wordLayout = row.getChildAt(0) as? TextInputLayout ?: continue
            val timeLayout = row.getChildAt(2) as? TextInputLayout ?: continue
            val word = wordLayout.editText?.text?.toString()?.trim().orEmpty()
            val minutes = SettingsStore.parseTimeLabelToMinutes(timeLayout.editText?.text?.toString().orEmpty()) ?: continue
            out += SettingsStore.FuzzyTimePair(word, minutes)
        }
        return out
    }

    private fun refreshFuzzyTimePairsUi() {
        fuzzyPairsContainer?.removeAllViews()
        SettingsStore.getFuzzyTimePairs(this).forEach { addFuzzyTimePairRow(it.word, it.minutesOfDay) }
    }

    private fun showFuzzyTimePicker(target: TextInputEditText) {
        val currentMinutes = SettingsStore.parseTimeLabelToMinutes(target.text?.toString().orEmpty()) ?: 13 * 60
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(currentMinutes / 60)
            .setMinute(currentMinutes % 60)
            .setTitleText("选择默认时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            target.setText(SettingsStore.formatMinutesOfDay(picker.hour * 60 + picker.minute))
        }
        picker.show(supportFragmentManager, "fuzzy_time_picker_${System.identityHashCode(target)}")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

        SettingsStore.setFuzzyTimePairs(this, readFuzzyTimePairsFromUi())

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

        // save notification queue settings
        try {
            SettingsStore.setNotificationQueueMode(this, NotificationQueueMode.fromDisplayName(queueModeInput?.text?.toString()))
            SettingsStore.setNotificationQueueTimeoutSeconds(this, queueTimeoutEdit?.text?.toString()?.toIntOrNull() ?: 40)
            SettingsStore.setNotificationQueueMaxMessages(this, queueMaxMessagesEdit?.text?.toString()?.toIntOrNull() ?: 3)
            queueTimeoutEdit?.setText(SettingsStore.getNotificationQueueTimeoutSeconds(this).toString())
            queueMaxMessagesEdit?.setText(SettingsStore.getNotificationQueueMaxMessages(this).toString())
            queueModeInput?.setText(SettingsStore.getNotificationQueueMode(this).displayName, false)
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
            refreshFuzzyTimePairsUi()
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

