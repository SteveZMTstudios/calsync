package top.stevezmt.calsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.app.AlertDialog
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private val pendingUiLogLines = ArrayDeque<String>()
    private var uiLogFlushScheduled = false
    private val uiLogMaxChars = 60_000

    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var testBtn: Button
    private lateinit var settingsBtn: ExtendedFloatingActionButton
    private lateinit var notificationAccessBtn: Button
    private lateinit var resultView: TextView
    private lateinit var selectCalendarBtn: Button
    private lateinit var calendarIndicator: TextView
    private lateinit var keepAliveSwitch: MaterialSwitch
    private lateinit var refreshNotificationServiceBtn: Button
    private lateinit var batteryOptimizationBtn: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    private val debugLogReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action != NotificationUtils.ACTION_DEBUG_LOG) return
                val line = intent.getStringExtra(NotificationUtils.EXTRA_DEBUG_LINE) ?: return
                appendResult(line)
            } catch (_: Throwable) {}
        }
    }

    private val requestCalendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map.values.all { it }
        Toast.makeText(this, if (granted) "日历权限已授予" else "日历权限缺失", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // Open navigation drawer when menu/home button is pressed
                drawerLayout.openDrawer(navView)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "未授予通知权限，确认通知将无法显示", Toast.LENGTH_LONG).show()
    }

    private val eventCreatedReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent == null) return
                if (intent.action == NotificationUtils.ACTION_EVENT_CREATED) {
                    val id = intent.getLongExtra(NotificationUtils.EXTRA_EVENT_ID, -1L)
                    val title = intent.getStringExtra(NotificationUtils.EXTRA_EVENT_TITLE) ?: ""
                    val start = intent.getLongExtra(NotificationUtils.EXTRA_EVENT_START, 0L)
                    val base = intent.getLongExtra(NotificationUtils.EXTRA_EVENT_BASE, -1L)
                    appendResult("事件创建: id=$id title=$title start=${java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.getDefault()).format(java.util.Date(start))}")
                    if (base > 0 && BuildConfig.DEBUG) {
                        appendResult("解析时的 now (DateTimeParser base): ${DateTimeParser.getNowFormatted()}  (wall clock now); parser baseMillis=$base")
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        setSupportActionBar(toolbar)
        // Enable menu button on the left side of the app bar (same place as back button)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.menu_24)

        // Handle back press to move task to back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Instead of finishing the activity, move the app to background per requirement
                moveTaskToBack(true)
            }
        })


    titleInput = findViewById(R.id.edit_test_title)
        contentInput = findViewById(R.id.edit_test_content)
    testBtn = findViewById(R.id.btn_test_parse)
    settingsBtn = findViewById(R.id.btn_open_settings)
    notificationAccessBtn = findViewById(R.id.btn_open_notification_access_main)
    resultView = findViewById(R.id.text_result)
    selectCalendarBtn = findViewById(R.id.btn_select_calendar)
    calendarIndicator = findViewById(R.id.text_calendar_selected)
    keepAliveSwitch = findViewById(R.id.switch_keep_alive)
        // Use a mutable buffer so we can efficiently cap log size
        resultView.text = SpannableStringBuilder()
        resultView.movementMethod = ScrollingMovementMethod()
        // Allow TextView to scroll inside parent NestedScrollView
        resultView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            false
        }

        testBtn.setOnClickListener { simulateNotification() }
    // settings button is now in the appbar menu and also available as FAB
    settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        notificationAccessBtn.setOnClickListener { openNotificationAccessSettings() }
        findViewById<Button>(R.id.btn_open_notification_status).setOnClickListener {
            startActivity(Intent(this, NotificationStatusActivity::class.java))
        }
        selectCalendarBtn.setOnClickListener { showCalendarPicker() }
        keepAliveSwitch.isChecked = SettingsStore.isKeepAliveEnabled(this)
        keepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setKeepAliveEnabled(this, isChecked)
            if (isChecked) startKeepAlive() else stopKeepAlive()
        }

        // 添加刷新通知服务按钮
        refreshNotificationServiceBtn = findViewById(R.id.btn_refresh_notification_service)
        refreshNotificationServiceBtn.setOnClickListener { refreshNotificationService() }
        
        // 添加电池优化按钮
        batteryOptimizationBtn = findViewById(R.id.btn_battery_optimization)
        batteryOptimizationBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }

        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

        // Populate nav header title and version dynamically
        try {
            val header = navView.getHeaderView(0)
            InsetsHelper.applyTopInsetOnce(header)
            val titleView = header.findViewById<TextView>(R.id.nav_header_title)
            val versionView = header.findViewById<TextView>(R.id.nav_header_version)
            titleView?.text = getString(R.string.app_name)
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            versionView?.text = getString(R.string.version_format, pkgInfo.versionName)
        } catch (_: Exception) {}

        // Set up navigation view item selection
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        updateCalendarIndicator()

        if (!SettingsStore.isPrivacyAccepted(this)) {
            showPrivacyDialog()
        } else {
            onPrivacyAccepted(fromDialog = false)
        }

        // Print DateTimeParser current now/time when opening main UI
        if (BuildConfig.DEBUG) {
            try {
                appendResult("DateTimeParser now: ${DateTimeParser.getNowFormatted()}")
            } catch (_: Throwable) {}
        }

        // Load recent logs captured in background (avoid blocking UI thread)
        try {
            kotlin.concurrent.thread(name = "calsync-load-logs") {
                try {
                    val cached = NotificationCache.snapshot(applicationContext)
                    // snapshot is newest-first; print oldest-first for readable chronology
                    cached.asReversed().forEach { appendResult(it) }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_privacy)
            .setMessage(R.string.dialog_message_privacy)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_agree) { _, _ ->
                SettingsStore.setPrivacyAccepted(this, true)
                onPrivacyAccepted(fromDialog = true)
            }
            .setNegativeButton(R.string.btn_disagree) { _, _ ->
                finish()
            }
            .setNeutralButton(R.string.link_privacy_policy) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/stevezmtstudios/calsync/blob/main/POLICY.md"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
                }
                // Re-show the dialog because clicking neutral button closes it
                showPrivacyDialog()
            }
            .show()
    }

    private fun onPrivacyAccepted(fromDialog: Boolean) {
        if (SettingsStore.isKeepAliveEnabled(this)) startKeepAlive()
        updateCalendarIndicator()
        
        // 仅在从对话框点击同意时检查，避免 onCreate 和 onResume 重复弹出
        if (fromDialog) {
            checkNotificationListenerStatus()
        }

        requestCalendarRuntime()

        // 首次启动：初始化通知通道并请求通知权限（如果需要）
        try {
            val prefs = getSharedPreferences("calsync_prefs", MODE_PRIVATE)
            val firstRun = prefs.getBoolean("first_run", true)
            if (firstRun) {
                // Ensure notification channels exist so user can manage them immediately
                NotificationUtils.ensureChannels(this)

                // Request runtime POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= 33) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                prefs.edit { putBoolean("first_run", false) }
            }
        } catch (_: Exception) {
            // 无论如何不阻塞主流程
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            val f = android.content.IntentFilter(NotificationUtils.ACTION_EVENT_CREATED)
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                eventCreatedReceiver,
                f,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Throwable) {}

        try {
            val f2 = android.content.IntentFilter(NotificationUtils.ACTION_DEBUG_LOG)
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                debugLogReceiver,
                f2,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Throwable) {}
    }

    override fun onStop() {
        try {
            unregisterReceiver(eventCreatedReceiver)
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(debugLogReceiver)
        } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!SettingsStore.isPrivacyAccepted(this)) return

        if (SettingsStore.isKeepAliveEnabled(this)) startKeepAlive()
        updateCalendarIndicator()
        
        // 检查通知监听权限状态
        checkNotificationListenerStatus()
    }
    
    /**
     * 检查通知监听服务状态，并在必要时提示用户
     */
    private fun checkNotificationListenerStatus() {
        if (!NotificationHelper.isNotificationListenerEnabled(this)) {
            // 通知监听未启用，显示警告对话框
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_notification_permission)
                .setMessage(R.string.dialog_message_notification_permission)
                .setPositiveButton(R.string.btn_go_to_settings) { _, _ ->
                    NotificationHelper.openNotificationListenerSettings(this)
                }
                .setNegativeButton(R.string.btn_later) { dialog, _ -> dialog.dismiss() }
                .show()
        } else if (NotificationHelper.needsSpecialRomSettings()) {
            // 检查是否需要特殊厂商设置的提示
            val sharedPrefs = getSharedPreferences("calsync_prefs", MODE_PRIVATE)
            val romHintShown = sharedPrefs.getBoolean("rom_hint_shown", false)
            
            if (!romHintShown) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title_extra_settings)
                    .setMessage(NotificationHelper.getSpecialRomSettingsHint())
                    .setPositiveButton(R.string.btn_got_it) { _, _ ->
                        // 设置标记，只显示一次
                        sharedPrefs.edit { putBoolean("rom_hint_shown", true) }
                    }
                    .setNegativeButton("稍后再说") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    // No options menu for this activity (AppBar right side intentionally empty)

    private fun startKeepAlive() {
        startService(Intent(this, KeepAliveService::class.java))
    }

    private fun stopKeepAlive() {
        stopService(Intent(this, KeepAliveService::class.java))
    }

    private fun updateCalendarIndicator() {
        val name = SettingsStore.getSelectedCalendarName(this)
        calendarIndicator.text = if (name == null) getString(R.string.calendar_not_selected) else getString(R.string.calendar_current_format, name)
    }

    private fun showCalendarPicker() {
        val list = CalendarHelper.listWritableCalendars(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "未找到可写日历或无权限", Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择日历")
            .setItems(names) { d, which ->
                val cal = list[which]
                SettingsStore.setSelectedCalendar(this, cal.id, cal.name)
                updateCalendarIndicator()
                appendResult("已选择日历: ${cal.name}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestCalendarRuntime() {
        val hasWrite = checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val hasRead = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        if (hasWrite && !hasRead) {
            // 检测到“仅允许创建”权限（常见于 MIUI 等 ROM）
            AlertDialog.Builder(this)
                .setTitle("需要完整日历权限")
                .setMessage("检测到您仅授予了“仅允许创建”权限。由于系统限制，应用需要“读取”权限才能列出日历并自动选择默认日历。请在接下来的弹窗中授予完整权限。")
                .setPositiveButton("确定") { _, _ ->
                    performCalendarRequest()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            performCalendarRequest()
        }
    }

    private fun performCalendarRequest() {
        val perms = mutableListOf<String>().apply {
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestCalendarPermission.launch(perms.toTypedArray())
    }

    private fun simulateNotification() {
        val t = titleInput.text.toString().trim()
        val c = contentInput.text.toString().trim()
        if (t.isEmpty() && c.isEmpty()) {
            Toast.makeText(this, "请输入测试标题或正文", Toast.LENGTH_SHORT).show(); return
        }

        // IMPORTANT: do NOT run parsing (especially GGUF) on main thread; it can easily ANR.
        testBtn.isEnabled = false
        appendResult("开始解析(测试)…")
        kotlin.concurrent.thread(name = "calsync-test-parse") {
            try {
                val ctx = applicationContext
                val res = NotificationProcessor.process(
                    ctx,
                    NotificationProcessor.ProcessInput("test.pkg", t, c, isTest = true),
                    object: NotificationProcessor.ConfirmationNotifier{
                        override fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?) {
                            appendResult("成功: 事件ID=$eventId 标题=$title 开始=${java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.getDefault()).format(java.util.Date(startMillis))}")
                        }
                        override fun onError(message: String?) { appendResult("错误: $message") }
                        override fun onDebugLog(line: String) {
                            if (BuildConfig.DEBUG) appendResult("[debug] $line")
                        }
                    }
                )
                if (!res.handled) appendResult("未处理: ${res.reason}")
            } catch (t2: Throwable) {
                appendResult("错误: ${t2.message}")
            } finally {
                try {
                    runOnUiThread { testBtn.isEnabled = true }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun appendResult(line: String) {
        // Thread-safe: can be called from background (notification parsing / test thread)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            resultView.post { appendResult(line) }
            return
        }

        pendingUiLogLines.addLast(line)
        if (uiLogFlushScheduled) return
        uiLogFlushScheduled = true

        // Batch multiple log lines into one UI update to reduce TextView relayout cost.
        resultView.postDelayed({
            uiLogFlushScheduled = false
            if (pendingUiLogLines.isEmpty()) return@postDelayed

            val chunk = buildString {
                while (pendingUiLogLines.isNotEmpty()) {
                    append(pendingUiLogLines.removeFirst())
                    append('\n')
                }
            }

            val buf = (resultView.text as? SpannableStringBuilder) ?: SpannableStringBuilder(resultView.text)
            buf.append(chunk)

            // Cap total size to avoid ANR from extremely large TextView layouts.
            val excess = buf.length - uiLogMaxChars
            if (excess > 0) {
                buf.delete(0, excess)
            }

            if (resultView.text !== buf) resultView.text = buf

            // Auto-scroll to bottom for new logs
            resultView.post {
                try {
                    val scrollAmount = (resultView.layout?.getLineTop(resultView.lineCount) ?: 0) - resultView.height
                    if (scrollAmount > 0) resultView.scrollTo(0, scrollAmount) else resultView.scrollTo(0, 0)
                } catch (_: Throwable) {}
            }
        }, 50)
    }

    private fun openNotificationAccessSettings() {
        if (!NotificationHelper.openNotificationListenerSettings(this)) {
            Toast.makeText(this, getString(R.string.error_open_notification_access), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 刷新通知监听服务
     */
    private fun refreshNotificationService() {
        NotificationHelper.refreshNotificationListenerService(this)
        Toast.makeText(this, getString(R.string.toast_service_refresh_attempted), Toast.LENGTH_SHORT).show()
        appendResult(getString(R.string.log_service_refreshed))
    }
    
    /**
     * 请求忽略电池优化
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, getString(R.string.toast_battery_optimization_ignored), Toast.LENGTH_SHORT).show()
            appendResult(getString(R.string.log_battery_optimization_status_ignored))
        } else {
            if (BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)) {
                appendResult(getString(R.string.log_battery_optimization_requested))
            } else {
                // 尝试打开通用电池设置
                try {
                    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    startActivity(intent)
                    appendResult(getString(R.string.log_battery_optimization_fallback))
                } catch (e: Exception) {
                    appendResult(getString(R.string.log_battery_optimization_error, e.message))
                }
            }
        }
    }
}
