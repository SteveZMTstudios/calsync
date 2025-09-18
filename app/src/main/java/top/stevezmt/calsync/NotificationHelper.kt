package top.stevezmt.calsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 通知权限助手类
 * 处理通知监听服务的权限检查、刷新和特殊ROM适配
 */
object NotificationHelper {
    private const val TAG = "NotificationHelper"

    /**
     * 检查通知监听服务是否已启用
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /**
     * 打开通知监听服务设置页面
     * @return 是否成功打开设置页
     */
    fun openNotificationListenerSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification listener settings", e)
            false
        }
    }

    /**
     * 刷新通知监听服务，适用于某些ROM上服务被系统停止的情况
     * 通过禁用再启用组件来强制系统重新绑定服务
     */
    fun refreshNotificationListenerService(context: Context) {
        try {
            Log.i(TAG, "Refreshing notification listener service")
            val packageManager = context.packageManager
            val componentName = ComponentName(context, NotificationMonitorService::class.java)
            
            // 禁用组件
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // 启用组件
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            Log.i(TAG, "Notification listener service refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh notification listener service", e)
        }
    }

    /**
     * 检查是否可能需要特殊厂商设置
     * 对于OPPO/ColorOS/Realme设备可能需要额外设置
     */
    fun needsSpecialRomSettings(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("oppo") || 
               manufacturer.contains("realme") || 
               manufacturer.contains("oneplus") || 
               manufacturer.contains("vivo")
    }

    /**
     * 获取厂商特殊设置的提示文本
     */
    fun getSpecialRomSettingsHint(): String {
        val manufacturer = Build.MANUFACTURER
        return when {
            manufacturer.contains("OPPO", ignoreCase = true) -> 
                "ColorOS需要额外设置:\n" +
                "1. 设置 > 电池 > 电池优化 > 从列表中找到并选择此应用 > 不优化\n" +
                "2. 设置 > 应用管理 > 找到此应用 > 权限管理 > 自启动 > 允许\n" +
                "3. 设置 > 应用管理 > 找到此应用 > 权限管理 > 通知管理 > 允许通知\n" +
                "4. 最近任务清理时，长按应用卡片，选择锁定"
                
            manufacturer.contains("vivo", ignoreCase = true) ->
                "vivo系统需要额外设置:\n" +
                "1. 设置 > 电池 > 后台高耗电 > 找到此应用并允许\n" +
                "2. 设置 > 应用管理 > 找到此应用 > 权限 > 自启动 > 允许\n" +
                "3. 设置 > 应用管理 > 找到此应用 > 权限 > 允许通知服务\n" +
                "4. 在最近任务界面长按应用并锁定"
                
            else -> 
                "此设备($manufacturer)可能需要额外设置:\n" +
                "1. 设置中找到电池或电源管理选项，禁用此应用的电池优化\n" +
                "2. 应用管理中允许自启动权限\n" +
                "3. 确保通知权限已完全授予\n" +
                "4. 在最近任务中锁定应用防止被自动清理"
        }
    }
}