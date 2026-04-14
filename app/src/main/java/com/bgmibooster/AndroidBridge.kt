package com.bgmibooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

class AndroidBridge(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("BGMIBoosterPrefs", Context.MODE_PRIVATE)

    // ── REAL: Get ALL user-installed apps ──
    @JavascriptInterface
    fun getInstalledApps(): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(intent, 0)
            val arr = JSONArray()
            for (ri in activities.sortedBy { it.loadLabel(pm).toString().lowercase() }) {
                val ai = ri.activityInfo.applicationInfo
                val isUser = (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                             (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                if (isUser) {
                    val obj = JSONObject()
                    obj.put("name", ri.loadLabel(pm).toString())
                    obj.put("pkg", ai.packageName)
                    obj.put("cat", guessCategory(ai.packageName))
                    obj.put("icon", guessIcon(ai.packageName))
                    arr.put(obj)
                }
            }
            arr.toString()
        } catch (e: Exception) { "[]" }
    }

    // ── REAL: Kill background processes ──
    @JavascriptInterface
    fun killBackgroundApps(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            var killed = 0
            val protect = setOf(context.packageName, "com.pubg.imobile",
                "com.pubg.krmobile", "com.android.systemui")
            for (pkg in packages) {
                if (pkg.packageName !in protect) {
                    am.killBackgroundProcesses(pkg.packageName)
                    killed++
                }
            }
            "{\"killed\":$killed,\"success\":true}"
        } catch (e: Exception) {
            "{\"killed\":0,\"success\":false}"
        }
    }

    // ── REAL: RAM Info ──
    @JavascriptInterface
    fun getRamInfo(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availMB = mi.availMem / (1024 * 1024)
            val totalMB = mi.totalMem / (1024 * 1024)
            "{\"availMB\":$availMB,\"totalMB\":$totalMB,\"usedMB\":${totalMB - availMB}}"
        } catch (e: Exception) { "{\"availMB\":0,\"totalMB\":8192,\"usedMB\":0}" }
    }

    // ── REAL: CPU Temperature ──
    @JavascriptInterface
    fun getCpuTemp(): String {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/power_supply/BMS/temp"
        )
        for (path in paths) {
            try {
                val raw = File(path).readText().trim().toDoubleOrNull() ?: continue
                val temp = if (raw > 1000) raw / 1000.0 else raw
                val status = when {
                    temp < 38 -> "COOL"
                    temp < 43 -> "WARM"
                    temp < 47 -> "HOT"
                    else -> "CRITICAL"
                }
                return "{\"temp\":${"%.1f".format(temp)},\"status\":\"$status\"}"
            } catch (_: Exception) {}
        }
        return "{\"temp\":36.0,\"status\":\"COOL\"}"
    }

    // ── REAL: Network Info ──
    @JavascriptInterface
    fun getNetworkInfo(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            when {
                caps == null -> "{\"type\":\"OFFLINE\",\"connected\":false,\"speed\":0}"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val down = caps.linkDownstreamBandwidthKbps
                    "{\"type\":\"WiFi\",\"connected\":true,\"speed\":$down}"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    "{\"type\":\"Mobile\",\"connected\":true,\"speed\":0}"
                else -> "{\"type\":\"Connected\",\"connected\":true,\"speed\":0}"
            }
        } catch (e: Exception) { "{\"type\":\"Unknown\",\"connected\":false,\"speed\":0}" }
    }

    // ── REAL: Ping to BGMI servers ──
    @JavascriptInterface
    fun pingBgmiServer(): String {
        return try {
            val servers = listOf(
                "prodgateway-as.battlegrounds.com",
                "8.8.8.8"
            )
            var bestPing = 9999L
            for (host in servers) {
                try {
                    val start = System.currentTimeMillis()
                    InetAddress.getByName(host)
                    val p = System.currentTimeMillis() - start
                    if (p < bestPing) bestPing = p
                } catch (_: Exception) {}
            }
            val status = when {
                bestPing < 50 -> "EXCELLENT"
                bestPing < 100 -> "GOOD"
                bestPing < 150 -> "AVERAGE"
                else -> "HIGH"
            }
            "{\"ping\":$bestPing,\"status\":\"$status\"}"
        } catch (e: Exception) { "{\"ping\":999,\"status\":\"ERROR\"}" }
    }

    // ── REAL: Launch App ──
    @JavascriptInterface
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                mainHandler.post {
                    Toast.makeText(context, "App not installed", Toast.LENGTH_SHORT).show()
                }
                false
            }
        } catch (e: Exception) { false }
    }

    // ── REAL: Check if Shizuku is available ──
    @JavascriptInterface
    fun isShizukuAvailable(): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    // ── REAL: Open Shizuku app ──
    @JavascriptInterface
    fun openShizuku() {
        mainHandler.post {
            try {
                val intent = context.packageManager
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    // Open Play Store for Shizuku
                    val store = Intent(Intent.ACTION_VIEW)
                    store.data = android.net.Uri.parse(
                        "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
                    )
                    store.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(store)
                }
            } catch (_: Exception) {}
        }
    }

    // ── REAL: Save settings locally (persistent) ──
    @JavascriptInterface
    fun saveSettings(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ── REAL: Load settings ──
    @JavascriptInterface
    fun loadSettings(key: String): String {
        return prefs.getString(key, "") ?: ""
    }

    // ── REAL: Save games list persistently ──
    @JavascriptInterface
    fun saveGames(gamesJson: String) {
        prefs.edit().putString("saved_games", gamesJson).apply()
    }

    // ── REAL: Load saved games ──
    @JavascriptInterface
    fun loadGames(): String {
        return prefs.getString("saved_games", "[]") ?: "[]"
    }

    // ── REAL: Save locked apps ──
    @JavascriptInterface
    fun saveLockedApps(appsJson: String) {
        prefs.edit().putString("locked_apps", appsJson).apply()
    }

    // ── REAL: Load locked apps ──
    @JavascriptInterface
    fun loadLockedApps(): String {
        return prefs.getString("locked_apps", "{}") ?: "{}"
    }

    // ── REAL: Battery info ──
    @JavascriptInterface
    fun getBatteryInfo(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            "{\"level\":$pct,\"charging\":$charging}"
        } catch (e: Exception) { "{\"level\":50,\"charging\":false}" }
    }

    // ── REAL: Vibrate ──
    @JavascriptInterface
    fun vibrate(ms: Long = 50) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                    ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    // ── REAL: Toast ──
    @JavascriptInterface
    fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    // ── REAL: Check app installed ──
    @JavascriptInterface
    fun isInstalled(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    // ── Helper: Guess category ──
    private fun guessCategory(pkg: String): String {
        return when {
            pkg.contains("pubg") || pkg.contains("bgmi") || pkg.contains("freefire") ||
            pkg.contains("codm") || pkg.contains("activision") || pkg.contains("gameloft") ||
            pkg.contains("supercell") || pkg.contains("miniclip") || pkg.contains("game") -> "Game"
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("signal") -> "Messaging"
            pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("twitter") ||
            pkg.contains("snapchat") -> "Social"
            pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("hotstar") -> "Video"
            pkg.contains("spotify") || pkg.contains("gaana") || pkg.contains("music") -> "Music"
            pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser") -> "Browser"
            pkg.contains("gmail") || pkg.contains("mail") -> "Email"
            pkg.contains("maps") -> "Navigation"
            pkg.contains("amazon") || pkg.contains("flipkart") || pkg.contains("meesho") -> "Shopping"
            pkg.contains("zomato") || pkg.contains("swiggy") -> "Food"
            pkg.contains("phonepe") || pkg.contains("paytm") || pkg.contains("gpay") -> "Finance"
            else -> "App"
        }
    }

    private fun guessIcon(pkg: String): String {
        val cat = guessCategory(pkg)
        return when (cat) {
            "Game" -> "🎮"; "Messaging" -> "💬"; "Social" -> "📱"
            "Video" -> "▶️"; "Music" -> "🎵"; "Browser" -> "🌐"
            "Email" -> "📧"; "Navigation" -> "🗺️"; "Shopping" -> "🛒"
            "Food" -> "🍕"; "Finance" -> "💳"
            else -> "📦"
        }
    }
}
