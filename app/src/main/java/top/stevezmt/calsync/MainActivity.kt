package top.stevezmt.calsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.app.AlertDialog
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var testBtn: Button
    private lateinit var settingsBtn: ImageButton
    private lateinit var notificationAccessBtn: Button
    private lateinit var resultView: TextView
    private lateinit var selectCalendarBtn: Button
    private lateinit var calendarIndicator: TextView
    private lateinit var keepAliveSwitch: Switch
    private lateinit var refreshNotificationServiceBtn: Button
    private lateinit var batteryOptimizationBtn: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

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
                    appendResult("事件创建: id=$id title=$title start=${java.text.SimpleDateFormat("M月d日 H:mm").format(java.util.Date(start))}")
                    if (base > 0) {
                        appendResult("解析时的 now (DateTimeParser base): ${DateTimeParser.getNowFormatted()}  (wall clock now); parser baseMillis=$base")
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        resultView.movementMethod = ScrollingMovementMethod()

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
            val titleView = header.findViewById<TextView>(R.id.nav_header_title)
            val versionView = header.findViewById<TextView>(R.id.nav_header_version)
            titleView?.text = getString(R.string.app_name)
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            versionView?.text = "版本 ${pkgInfo.versionName}"
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
        // Print DateTimeParser current now/time when opening main UI
        try {
            appendResult("DateTimeParser now: ${DateTimeParser.getNowFormatted()}")
        } catch (_: Throwable) {}
    }

    override fun onStart() {
        super.onStart()
        try {
            val f = android.content.IntentFilter(NotificationUtils.ACTION_EVENT_CREATED)
            registerReceiver(eventCreatedReceiver, f)
        } catch (_: Throwable) {}
    }

    override fun onStop() {
        try {
            unregisterReceiver(eventCreatedReceiver)
        } catch (_: Throwable) {}
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (SettingsStore.isKeepAliveEnabled(this)) startKeepAlive()
        
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
                .setTitle("需要授予通知访问权限")
                .setMessage("此应用需要通知访问权限才能监听通知并创建日历事件。请在下一屏幕中允许权限。")
                .setPositiveButton("前往设置") { _, _ ->
                    NotificationHelper.openNotificationListenerSettings(this)
                }
                .setNegativeButton("稍后") { dialog, _ -> dialog.dismiss() }
                .show()
        } else if (NotificationHelper.needsSpecialRomSettings()) {
            // 检查是否需要特殊厂商设置的提示
            val sharedPrefs = getSharedPreferences("calsync_prefs", MODE_PRIVATE)
            val romHintShown = sharedPrefs.getBoolean("rom_hint_shown", false)
            
            if (!romHintShown) {
                AlertDialog.Builder(this)
                    .setTitle("需要额外设置")
                    .setMessage(NotificationHelper.getSpecialRomSettingsHint())
                    .setPositiveButton("知道了") { _, _ ->
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
        calendarIndicator.text = if (name == null) "未选择日历(使用默认)" else "当前日历: $name"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perms = mutableListOf<String>().apply {
                add(Manifest.permission.WRITE_CALENDAR)
                add(Manifest.permission.READ_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestCalendarPermission.launch(perms.toTypedArray())
        }
    }

    private fun simulateNotification() {
        val t = titleInput.text.toString().trim()
        val c = contentInput.text.toString().trim()
        if (t.isEmpty() && c.isEmpty()) {
            Toast.makeText(this, "请输入测试标题或正文", Toast.LENGTH_SHORT).show(); return
        }
        val res = NotificationProcessor.process(this, NotificationProcessor.ProcessInput("test.pkg", t, c, isTest = true), object: NotificationProcessor.ConfirmationNotifier{
            override fun onEventCreated(eventId: Long, title: String, startMillis: Long, endMillis: Long, location: String?) {
                appendResult("成功: 事件ID=$eventId 标题=$title 开始=${java.text.SimpleDateFormat("M月d日 H:mm").format(java.util.Date(startMillis))}")
            }
            override fun onError(message: String?) { appendResult("错误: $message") }
        })
        if (!res.handled) appendResult("未处理: ${res.reason}")
    }

    private fun appendResult(line: String) {
        resultView.append(line + "\n")
    }

    private fun openNotificationAccessSettings() {
        if (!NotificationHelper.openNotificationListenerSettings(this)) {
            Toast.makeText(this, "无法打开通知访问设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 刷新通知监听服务
     */
    private fun refreshNotificationService() {
        NotificationHelper.refreshNotificationListenerService(this)
        Toast.makeText(this, "已尝试刷新通知监听服务", Toast.LENGTH_SHORT).show()
        appendResult("已刷新通知监听服务")
    }
    
    /**
     * 请求忽略电池优化
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            appendResult("电池优化状态：已忽略")
        } else {
            if (BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)) {
                appendResult("已请求忽略电池优化")
            } else {
                // 尝试打开通用电池设置
                try {
                    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    startActivity(intent)
                    appendResult("无法直接设置，已打开电池设置页")
                } catch (e: Exception) {
                    appendResult("无法打开电池设置：${e.message}")
                }
            }
        }
    }
}
