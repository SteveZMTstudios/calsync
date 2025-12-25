package top.stevezmt.calsync

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.util.Log
import android.annotation.SuppressLint

/**
 * 电池优化助手类
 * 用于处理各种省电模式和后台限制相关的设置
 */
@SuppressLint("BatteryLife")
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimizationHelper"

    /**
     * 检查应用是否被忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化
     * @return 是否成功打开设置页面
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (!isIgnoringBatteryOptimizations(context)) {
            try {
                // Primary: ask system to request ignore for this package
                val pkg = context.packageName
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS not found, will try fallback", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request ignore battery optimizations", e)
            }

            // Fallback 1: open the system battery optimization settings list
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS not found", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization settings", e)
            }

            // Fallback 2: open app details settings so user can find battery options manually
            try {
                val pkg = context.packageName
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open application details settings", e)
            }
        }
        return false
    }

    /**
     * 尝试打开ColorOS的自启动设置页
     * 注意: 这是一个尝试性方法，不同版本的ColorOS可能路径不同
     */
    fun openColorOsAutoStartSettings(context: Context): Boolean {
        try {
            val intent = Intent()
            intent.component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ColorOS autostart settings", e)
            return false
        }
    }
}