package com.agent.arena

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * UI 를 거치지 않는 "시스템 API / 앱 API" 실행기.
 * 설정앱을 열어 슬라이더를 직접 누르는 대신 AudioManager, CameraManager, Intent 등을 바로 호출해
 * 한 번의 명령으로 끝낸다. 접근성 서비스가 꺼져 있어도 (대부분) 동작한다.
 */
object SystemApi {

    private val ctx: Context get() = AgentApp.instance

    /** 액티비티 시작에는 (백그라운드 시작이 허용되는) 접근성 서비스 컨텍스트를 우선 사용. */
    private val actCtx: Context get() = AgentAccessibilityService.instance ?: AgentApp.instance

    /** 화면을 바꾸지 않는 순수 백그라운드 액션. 이것만 실행했다면 화면 덤프를 생략해 왕복을 줄인다. */
    val PURE: Set<String> = setOf(
        "volume", "brightness", "flashlight", "torch", "ringer", "dnd", "media",
        "clipboard", "get_info", "find_app", "alarm", "timer",
    )

    private val HANDLED: Set<String> = PURE + setOf(
        "launch_app", "open_app", "open_url", "open_settings", "intent",
        "dial", "sms", "email", "search", "map",
    )

    fun handles(action: String): Boolean = action in HANDLED

    @Volatile private var torchOn = false

    suspend fun execute(c: JSONObject): String {
        val a = c.optString("action").lowercase().trim()
        return try {
            when (a) {
                "volume" -> volume(c)
                "brightness" -> brightness(c)
                "flashlight", "torch" -> flashlight(c)
                "ringer" -> ringer(c)
                "dnd" -> dnd(c)
                "media" -> media(c)
                "clipboard" -> clipboard(c)
                "get_info" -> getInfo(c)
                "find_app" -> findApp(c)
                "alarm" -> alarm(c)
                "timer" -> timer(c)
                "launch_app", "open_app" -> launchApp(c)
                "open_url" -> openUrl(c)
                "open_settings" -> openSettings(c)
                "intent" -> intent(c)
                "dial" -> dial(c)
                "sms" -> sms(c)
                "email" -> email(c)
                "search" -> search(c)
                "map" -> map(c)
                else -> "error: unknown api action '$a'"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivityNotFoundException) {
            "error: 이 요청을 처리할 앱/화면이 없음 (${e.message})"
        } catch (e: SecurityException) {
            "error: 권한 부족 (${e.message})"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ audio

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamOf(name: String): Int = when (name.lowercase().trim()) {
        "ring", "ringtone", "ringer" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamMin(am: AudioManager, st: Int): Int =
        if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(st) else 0

    private fun volume(c: JSONObject): String {
        val am = audio()
        val name = c.optString("stream", "music").ifBlank { "music" }
        val st = streamOf(name)
        val max = am.getStreamMaxVolume(st)
        val min = streamMin(am, st)

        if (c.has("mute")) {
            val mute = c.optBoolean("mute")
            am.adjustStreamVolume(
                st,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI,
            )
        } else {
            val target: Int? = when {
                c.has("percent") -> (min + (max - min) * c.getDouble("percent").coerceIn(0.0, 100.0) / 100.0).roundToInt()
                c.has("level") -> c.getInt("level").coerceIn(min, max)
                c.has("delta") -> (am.getStreamVolume(st) + c.getInt("delta")).coerceIn(min, max)
                else -> null
            }
            if (target != null) am.setStreamVolume(st, target, AudioManager.FLAG_SHOW_UI)
        }
        val now = am.getStreamVolume(st)
        val pct = if (max > min) ((now - min) * 100.0 / (max - min)).roundToInt() else 0
        val muted = if (am.isStreamMute(st)) " muted" else ""
        return "ok: $name volume $now/$max ($pct%)$muted"
    }

    private fun ringer(c: JSONObject): String {
        val am = audio()
        val mode = when (c.optString("mode").lowercase().trim()) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            else -> return "error: ringer mode must be normal|vibrate|silent"
        }
        am.ringerMode = mode
        val label = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return if (am.ringerMode == mode) "ok: ringer $label"
        else "error: ringer is $label (무음 전환은 '방해 금지 접근' 권한이 필요할 수 있음)"
    }

    private suspend fun media(c: JSONObject): String {
        val cmd = c.optString("command").lowercase().trim()
        val code = when (cmd) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "error: media command must be play|pause|play_pause|next|previous|stop"
        }
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        delay(250)
        return "ok: media $cmd (music_active=${am.isMusicActive})"
    }

    // ------------------------------------------------------------------ display / hardware

    private fun brightness(c: JSONObject): String {
        val app = ctx
        if (!Settings.System.canWrite(app)) {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            actCtx.startActivity(i)
            return "error: '시스템 설정 수정' 권한이 필요합니다. 권한 화면을 열었으니 need_user 로 허용을 요청하세요. (허용 후 다시 시도)"
        }
        val cr = app.contentResolver
        if (c.has("auto")) {
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (c.optBoolean("auto")) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target: Int? = when {
            c.has("percent") -> (c.getDouble("percent").coerceIn(0.0, 100.0) * 255 / 100).roundToInt()
            c.has("delta_percent") -> cur + (c.getDouble("delta_percent") * 255 / 100).roundToInt()
            else -> null
        }
        if (target != null) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target.coerceIn(1, 255))
        }
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return "ok: brightness ${(now * 100.0 / 255).roundToInt()}% (auto=$auto)"
    }

    private fun flashlight(c: JSONObject): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "error: 이 기기에는 플래시가 없음"
        val on = if (c.has("on")) c.optBoolean("on") else !torchOn
        cm.setTorchMode(id, on)
        torchOn = on
        return "ok: flashlight ${if (on) "on" else "off"}"
    }

    private fun dnd(c: JSONObject): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            val i = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            actCtx.startActivity(i)
            return "error: '방해 금지 접근' 권한이 필요합니다. 권한 화면을 열었으니 need_user 로 허용을 요청하세요."
        }
        val on = c.optBoolean("on", true)
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return "ok: do-not-disturb ${if (on) "on" else "off"}"
    }

    // ------------------------------------------------------------------ misc

    private fun clipboard(c: JSONObject): String {
        val text = c.optString("text")
        if (text.isEmpty()) return "error: clipboard requires text"
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("agent", text))
        return "ok: copied ${text.length} chars to clipboard"
    }

    private fun getInfo(c: JSONObject): String {
        val what = c.optString("what", "all").lowercase().trim().ifBlank { "all" }
        fun want(k: String) = what == "all" || what == k
        val sb = StringBuilder("ok:")
        val app = ctx

        if (want("battery")) {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            sb.append("\nbattery: $pct%${if (charging) " (charging)" else ""}")
        }
        if (want("volume")) {
            val am = audio()
            val parts = listOf(
                "music" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING,
                "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION,
                "system" to AudioManager.STREAM_SYSTEM, "call" to AudioManager.STREAM_VOICE_CALL,
            ).joinToString(", ") { (n, s) -> "$n=${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}" }
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            sb.append("\nvolume: $parts; ringer=$ringer; music_active=${am.isMusicActive}")
        }
        if (want("brightness")) {
            val cr = app.contentResolver
            val v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
            sb.append("\nbrightness: ${(v * 100.0 / 255).roundToInt()}% (auto=$auto, can_write=${Settings.System.canWrite(app)})")
        }
        if (want("network")) {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val wifiOn = try {
                (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled.toString()
            } catch (e: Exception) { "unknown" }
            sb.append("\nnetwork: active=$net; wifi_enabled=$wifiOn")
        }
        if (want("bluetooth")) {
            val bt = try {
                (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled?.toString() ?: "no_adapter"
            } catch (e: Exception) { "unknown" }
            sb.append("\nbluetooth_enabled: $bt")
        }
        if (want("time")) {
            sb.append("\ntime: ${ZonedDateTime.now()} locale=${Locale.getDefault().toLanguageTag()}")
        }
        if (want("storage")) {
            val st = StatFs(Environment.getDataDirectory().path)
            val gb = 1024.0 * 1024 * 1024
            sb.append("\nstorage: free %.1f GB / total %.1f GB".format(st.availableBytes / gb, st.totalBytes / gb))
        }
        if (want("flashlight")) sb.append("\nflashlight_on: $torchOn")
        return sb.toString()
    }

    // ------------------------------------------------------------------ apps

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    /** 앱 이름(라벨) → 패키지명. 정확 일치 → 접두 일치 → 부분 일치 순. */
    fun resolvePackage(name: String): String? {
        val n = norm(name)
        if (n.isEmpty()) return null
        val list = installedApps()
        return list.firstOrNull { norm(it.first) == n }?.second
            ?: list.firstOrNull { it.second.equals(name.trim(), true) }?.second
            ?: list.firstOrNull { norm(it.first).startsWith(n) }?.second
            ?: (if (n.length >= 2) list.firstOrNull { norm(it.first).contains(n) }?.second else null)
    }

    private fun findApp(c: JSONObject): String {
        val q = norm(c.optString("query").ifBlank { c.optString("name") })
        if (q.isEmpty()) return "error: find_app requires query"
        val hits = installedApps().filter { norm(it.first).contains(q) || it.second.lowercase().contains(q) }
            .sortedBy { it.first.lowercase() }.take(15)
        return if (hits.isEmpty()) "ok: no installed app matches" else
            "ok:\n" + hits.joinToString("\n") { "${it.first} | ${it.second}" }
    }

    private suspend fun startUi(i: Intent, waitMs: Long = 2500) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        actCtx.startActivity(i)
        val svc = AgentAccessibilityService.instance
        if (svc != null) svc.settle(waitMs, 250, true) else delay(700)
    }

    suspend fun launchApp(c: JSONObject): String {
        var pkg = c.optString("package")
        if (pkg.isBlank()) {
            val name = c.optString("name").trim()
            if (name.isBlank()) return "error: launch_app requires package or name"
            pkg = resolvePackage(name) ?: return "error: '$name' 앱을 찾지 못함 (find_app 으로 검색)"
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "error: $pkg 실행 불가(미설치/실행 인텐트 없음)"
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startUi(intent)
        return "ok: launched $pkg"
    }

    private suspend fun openUrl(c: JSONObject): String {
        val url = c.optString("url")
        if (url.isBlank()) return "error: url is empty"
        startUi(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return "ok: opened $url"
    }

    // ------------------------------------------------------------------ settings deep links

    private suspend fun openSettings(c: JSONObject): String {
        val page = c.optString("page", "main").lowercase().trim()
        val pkg = c.optString("package")
        val api29 = Build.VERSION.SDK_INT >= 29
        val i: Intent = when (page) {
            "wifi" -> Intent("android.settings.WIFI_SETTINGS")
            "bluetooth" -> Intent("android.settings.BLUETOOTH_SETTINGS")
            "display" -> Intent("android.settings.DISPLAY_SETTINGS")
            "sound" -> Intent("android.settings.SOUND_SETTINGS")
            "battery" -> Intent("android.settings.BATTERY_SAVER_SETTINGS")
            "apps" -> Intent("android.settings.APPLICATION_SETTINGS")
            "location" -> Intent("android.settings.LOCATION_SOURCE_SETTINGS")
            "accessibility" -> Intent("android.settings.ACCESSIBILITY_SETTINGS")
            "nfc" -> Intent("android.settings.NFC_SETTINGS")
            "airplane" -> Intent("android.settings.AIRPLANE_MODE_SETTINGS")
            "data", "mobile_data" -> Intent("android.settings.DATA_ROAMING_SETTINGS")
            "storage" -> Intent("android.settings.INTERNAL_STORAGE_SETTINGS")
            "date" -> Intent("android.settings.DATE_SETTINGS")
            "language" -> Intent("android.settings.LOCALE_SETTINGS")
            "keyboard" -> Intent("android.settings.INPUT_METHOD_SETTINGS")
            "default_apps" -> Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
            "hotspot", "tethering" -> Intent("android.settings.TETHER_SETTINGS")
            "vpn" -> Intent("android.settings.VPN_SETTINGS")
            "privacy" -> Intent("android.settings.PRIVACY_SETTINGS")
            "security" -> Intent("android.settings.SECURITY_SETTINGS")
            "developer" -> Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            "dnd_access" -> Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
            "write_settings" -> Intent("android.settings.action.MANAGE_WRITE_SETTINGS", Uri.parse("package:${pkg.ifBlank { ctx.packageName }}"))
            "app_info" -> {
                if (pkg.isBlank()) return "error: app_info requires package"
                Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:$pkg"))
            }
            "app_notifications" -> {
                if (pkg.isBlank()) return "error: app_notifications requires package"
                Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", pkg)
            }
            // Android 10+ 하단 패널(한 화면에서 토글 가능)
            "internet_panel" -> if (api29) Intent("android.settings.panel.action.INTERNET_CONNECTIVITY") else Intent("android.settings.WIFI_SETTINGS")
            "wifi_panel" -> if (api29) Intent("android.settings.panel.action.WIFI") else Intent("android.settings.WIFI_SETTINGS")
            "volume_panel" -> if (api29) Intent("android.settings.panel.action.VOLUME") else Intent("android.settings.SOUND_SETTINGS")
            "nfc_panel" -> if (api29) Intent("android.settings.panel.action.NFC") else Intent("android.settings.NFC_SETTINGS")
            else -> Intent("android.settings.SETTINGS")
        }
        startUi(i)
        return "ok: opened settings page '$page'"
    }

    // ------------------------------------------------------------------ generic intent & helpers

    private suspend fun intent(c: JSONObject): String {
        val i = Intent(c.optString("intent_action").ifBlank { Intent.ACTION_VIEW })
        val data = c.optString("data")
        val type = c.optString("type")
        when {
            data.isNotBlank() && type.isNotBlank() -> i.setDataAndType(Uri.parse(data), type)
            data.isNotBlank() -> i.data = Uri.parse(data)
            type.isNotBlank() -> i.type = type
        }
        val pkg = c.optString("package")
        val cls = c.optString("class")
        if (pkg.isNotBlank()) {
            if (cls.isNotBlank()) i.setClassName(pkg, cls) else i.setPackage(pkg)
        }
        c.optJSONArray("categories")?.let { arr ->
            for (k in 0 until arr.length()) i.addCategory(arr.optString(k))
        }
        c.optJSONObject("extras")?.let { ex ->
            val keys = ex.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = ex.get(k)) {
                    is Boolean -> i.putExtra(k, v)
                    is Int -> i.putExtra(k, v)
                    is Long -> i.putExtra(k, v)
                    is Double -> i.putExtra(k, v)
                    else -> i.putExtra(k, v.toString())
                }
            }
        }
        if (c.optString("target").lowercase() == "broadcast") {
            actCtx.sendBroadcast(i)
            return "ok: broadcast sent"
        }
        startUi(i)
        return "ok: intent started"
    }

    private suspend fun dial(c: JSONObject): String {
        val n = c.optString("number").trim()
        if (n.isBlank()) return "error: dial requires number"
        startUi(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
        return "ok: dialer opened with $n (사용자가 직접 통화 버튼을 눌러야 함)"
    }

    private suspend fun sms(c: JSONObject): String {
        val n = c.optString("number").trim()
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n)))
        i.putExtra("sms_body", c.optString("body"))
        startUi(i)
        return "ok: SMS composer opened (전송 버튼은 사용자 확인 후에만 누를 것)"
    }

    private suspend fun email(c: JSONObject): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.optString("to").trim()))
        i.putExtra(Intent.EXTRA_SUBJECT, c.optString("subject"))
        i.putExtra(Intent.EXTRA_TEXT, c.optString("body"))
        startUi(i)
        return "ok: email composer opened (전송 버튼은 사용자 확인 후에만 누를 것)"
    }

    private suspend fun search(c: JSONObject): String {
        val q = c.optString("query").trim()
        if (q.isBlank()) return "error: search requires query"
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", q)
        startUi(i)
        return "ok: web search '$q'"
    }

    private suspend fun map(c: JSONObject): String {
        val q = c.optString("query").trim()
        if (q.isBlank()) return "error: map requires query"
        startUi(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
        return "ok: map '$q'"
    }

    private suspend fun alarm(c: JSONObject): String {
        if (!c.has("hour")) return "error: alarm requires hour (0-23) and optional minute"
        val h = c.getInt("hour").coerceIn(0, 23)
        val m = c.optInt("minute", 0).coerceIn(0, 59)
        val i = Intent("android.intent.action.SET_ALARM")
            .putExtra("android.intent.extra.alarm.HOUR", h)
            .putExtra("android.intent.extra.alarm.MINUTES", m)
            .putExtra("android.intent.extra.alarm.SKIP_UI", c.optBoolean("skip_ui", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = c.optString("label")
        if (label.isNotBlank()) i.putExtra("android.intent.extra.alarm.MESSAGE", label)
        actCtx.startActivity(i)
        delay(300)
        return "ok: alarm requested %02d:%02d".format(h, m)
    }

    private suspend fun timer(c: JSONObject): String {
        val secs = c.optInt("hours", 0) * 3600 + c.optInt("minutes", 0) * 60 + c.optInt("seconds", 0)
        if (secs <= 0) return "error: timer requires hours/minutes/seconds"
        val i = Intent("android.intent.action.SET_TIMER")
            .putExtra("android.intent.extra.alarm.LENGTH", secs)
            .putExtra("android.intent.extra.alarm.SKIP_UI", c.optBoolean("skip_ui", true))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val label = c.optString("label")
        if (label.isNotBlank()) i.putExtra("android.intent.extra.alarm.MESSAGE", label)
        actCtx.startActivity(i)
        delay(300)
        return "ok: timer requested ${secs}s"
    }
}
