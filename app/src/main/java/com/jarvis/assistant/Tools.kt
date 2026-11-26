package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/** Everything Jarvis can do on the phone. */
class Tools(private val ctx: Context) {

    /** Set by call/SMS so the user must say "yes" first. The service runs it on confirmation. */
    @Volatile
    var pending: (() -> String)? = null

    // ---------------------------------------------------------------- tool definitions
    private fun def(name: String, desc: String, vararg p: String): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (s in p) {
            val parts = s.split(":", limit = 3)
            var n = parts[0]
            val optional = n.endsWith("?")
            if (optional) n = n.dropLast(1)
            props.put(n, JSONObject().put("type", parts[1]).put("description", parts[2]))
            if (!optional) req.put(n)
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", props).put("required", req)
            )
    }

    val definitions: JSONArray = JSONArray().apply {
        put(def("open_app", "Open an installed app by its name.", "app_name:string:App name, e.g. WhatsApp"))
        put(def("open_url", "Open a web page in the browser.", "url:string:Full URL"))
        put(def("play_music", "Search for a song, artist or video on YouTube Music.", "query:string:What to play"))
        put(def("navigate", "Start Google Maps navigation.", "destination:string:Place or address"))
        put(def("call", "Phone call to a contact or number. The user is asked to confirm first.", "contact_or_number:string:Contact name or number"))
        put(def("send_sms", "Send an SMS. The user is asked to confirm first.", "contact_or_number:string:Contact name or number", "message:string:Message text"))
        put(def("send_whatsapp", "Open a WhatsApp chat with a prefilled message (the user taps send).", "contact_or_number:string:Contact name or number", "message?:string:Message text"))
        put(def("set_alarm", "Set an alarm, 24-hour time.", "hour:integer:0-23", "minute:integer:0-59", "label?:string:Label"))
        put(def("set_timer", "Start a countdown timer.", "seconds:integer:Duration in seconds", "label?:string:Label"))
        put(def("flashlight", "Turn the flashlight on or off.", "on:boolean:true for on"))
        put(def("set_volume", "Set media volume.", "percent:integer:0-100"))
        put(def("battery_status", "Get battery level and charging state."))
        put(def("open_settings", "Open a settings page: wifi, bluetooth, display, sound, battery, location, airplane, apps, storage, nfc.", "page:string:Settings page"))
        put(def("phone_action", "Press a system button: home, back, recents, notifications, quick_settings, lock_screen, screenshot, power_menu.", "action:string:Which action"))
        put(def("tap_text", "Tap a button or item on the current screen by its visible text.", "text:string:Visible text to tap"))
        put(def("type_text", "Type text into the currently focused input field.", "text:string:Text to type"))
        put(def("scroll", "Scroll the current screen.", "direction:string:up or down"))
        put(def("read_screen", "Read out the visible text on the current screen."))
        put(def("remember", "Save a fact about the user for future conversations.", "note:string:What to remember"))
        put(def("forget", "Delete saved notes that contain a keyword.", "keyword:string:Keyword"))
    }

    // ---------------------------------------------------------------- dispatcher
    fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(a.optString("app_name"))
            "open_url" -> if (go(Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))))) "Opened." else "Could not open that link."
            "play_music" -> {
                val q = Uri.encode(a.optString("query"))
                if (go(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q"))))
                    "Opened the search. The user may need to tap play." else "Could not open music."
            }
            "navigate" -> {
                val d = Uri.encode(a.optString("destination"))
                if (go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$d")))) "Navigation started." else "Could not start navigation."
            }
            "call" -> call(a.optString("contact_or_number"))
            "send_sms" -> sms(a.optString("contact_or_number"), a.optString("message"))
            "send_whatsapp" -> whatsapp(a.optString("contact_or_number"), a.optString("message"))
            "set_alarm" -> setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label", "Jarvis alarm"))
            "set_timer" -> setTimer(a.optInt("seconds"), a.optString("label", "Jarvis timer"))
            "flashlight" -> flashlight(a.optBoolean("on"))
            "set_volume" -> setVolume(a.optInt("percent"))
            "battery_status" -> battery()
            "open_settings" -> openSettings(a.optString("page"))
            "phone_action" -> withA11y { it.globalAction(a.optString("action")) }
            "tap_text" -> withA11y { it.tapText(a.optString("text")) }
            "type_text" -> withA11y { it.typeText(a.optString("text")) }
            "scroll" -> withA11y { it.scroll(a.optString("direction").lowercase() != "up") }
            "read_screen" -> JarvisAccessibility.instance?.readScreen() ?: NEEDS_A11Y
            "remember" -> {
                Prefs.setMemories(ctx, Prefs.memories(ctx) + a.optString("note"))
                "Remembered."
            }
            "forget" -> {
                val kw = a.optString("keyword").lowercase()
                val before = Prefs.memories(ctx)
                val after = before.filterNot { it.lowercase().contains(kw) }
                Prefs.setMemories(ctx, after)
                "Removed ${before.size - after.size} notes."
            }
            else -> "Unknown tool $name."
        }
    } catch (e: Exception) {
        "Tool $name failed: ${e.message}"
    }

    private val NEEDS_A11Y =
        "Accessibility access is not enabled. Ask the user to turn on Jarvis in Accessibility settings."

    private fun withA11y(block: (JarvisAccessibility) -> Boolean): String {
        val svc = JarvisAccessibility.instance ?: return NEEDS_A11Y
        return if (block(svc)) "Done." else "That did not work on the current screen."
    }

    private fun go(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- implementations
    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.trim().lowercase()
        val labelOf = { i: android.content.pm.ResolveInfo -> i.loadLabel(pm).toString().lowercase() }
        val match = apps.firstOrNull { labelOf(it) == q }
            ?: apps.firstOrNull { labelOf(it).contains(q) }
            ?: apps.firstOrNull { q.contains(labelOf(it)) && labelOf(it).length > 2 }
            ?: return "I could not find an app called $name."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I could not launch $name."
        return if (go(intent)) "Opened $name." else "Android blocked opening $name. Check the overlay permission."
    }

    private fun findNumber(target: String): Pair<String, String>? {
        val t = target.trim()
        if (Regex("[+\\d\\s\\-()]{5,}").matches(t)) {
            return Pair(t.filter { it.isDigit() || it == '+' }, t)
        }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val list = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) list.add(Pair(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        val q = t.lowercase()
        val words = q.split(" ").filter { it.length > 2 }
        val hit = list.firstOrNull { it.first.lowercase() == q }
            ?: list.firstOrNull { it.first.lowercase().contains(q) }
            ?: list.firstOrNull { c -> words.any { w -> c.first.lowercase().contains(w) } }
            ?: return null
        return Pair(hit.second.filter { it.isDigit() || it == '+' }, hit.first)
    }

    private fun call(target: String): String {
        val (number, name) = findNumber(target) ?: return "I could not find a contact or number for $target."
        pending = {
            val ok = go(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
            if (ok) "Calling $name." else "I could not place the call. Check the phone permission."
        }
        return "NOT DONE YET. Ask the user: shall I call $name? The call happens only after they say yes."
    }

    private fun sms(target: String, message: String): String {
        val (number, name) = findNumber(target) ?: return "I could not find a contact or number for $target."
        pending = {
            val mgr = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            mgr.sendMultipartTextMessage(number, null, mgr.divideMessage(message), null, null)
            "Message sent to $name."
        }
        return "NOT DONE YET. Ask the user to confirm sending $name this message: $message. It is sent only after they say yes."
    }

    private fun whatsapp(target: String, message: String): String {
        val (number, name) = findNumber(target) ?: return "I could not find a contact or number for $target."
        var n = number.removePrefix("+")
        if (n.length == 10) n = "91$n"
        val uri = Uri.parse("https://wa.me/$n?text=" + Uri.encode(message))
        return if (go(Intent(Intent.ACTION_VIEW, uri)))
            "WhatsApp chat with $name is open with the message ready. The user must tap send." else "Could not open WhatsApp."
    }

    private fun setAlarm(h: Int, m: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (go(i)) "Alarm set for %02d:%02d.".format(h, m) else "No clock app could set the alarm."
    }

    private fun setTimer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (go(i)) "Timer set for $seconds seconds." else "No clock app could set the timer."
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun setVolume(percent: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (percent.coerceIn(0, 100) / 100.0 * max).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $percent percent."
    }

    private fun battery(): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Could not read the battery."
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at ${level * 100 / scale} percent" + if (charging) ", charging." else ", not charging."
    }

    private fun openSettings(page: String): String {
        val action = when (page.lowercase().trim()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (go(Intent(action))) "Opened $page settings." else "Could not open settings."
    }
}
