package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One-screen setup: API key, permissions, start/stop. */
class MainActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var nameField: EditText
    private lateinit var langField: EditText
    private lateinit var status: TextView

    private val bg = Color.parseColor("#0B1020")
    private val cyan = Color.parseColor("#00E5FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(bg)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#7FDBFF"))
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }

        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#12304A"))
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 30f
            setTextColor(cyan)
        })
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        root.addView(label("Anthropic API key (console.anthropic.com)"))
        keyField = field("sk-ant-...", Prefs.get(this, "api_key", ""), secret = true)
        root.addView(keyField)
        root.addView(label("What should Jarvis call you?"))
        nameField = field("sir", Prefs.get(this, "name", "sir"))
        root.addView(nameField)
        root.addView(label("Language (en-IN, en-US, en-GB, hi-IN)"))
        langField = field("en-IN", Prefs.get(this, "lang", "en-IN"))
        root.addView(langField)

        root.addView(label("Setup - do these once"))
        root.addView(button("1. Allow microphone, contacts, calls, SMS") { requestPerms() })
        root.addView(button("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Turn on Jarvis accessibility (tap/scroll/type)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("4. Stop battery optimisation for Jarvis") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })

        root.addView(label("Run"))
        root.addView(button("START JARVIS") {
            Prefs.put(this, "api_key", keyField.text.toString().trim())
            Prefs.put(this, "name", nameField.text.toString().trim())
            Prefs.put(this, "lang", langField.text.toString().trim())
            startForegroundService(Intent(this, JarvisService::class.java))
            status.postDelayed({ refresh() }, 800)
        })
        root.addView(button("STOP JARVIS") {
            stopService(Intent(this, JarvisService::class.java))
            status.postDelayed({ refresh() }, 500)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestPerms() {
        val list = arrayListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val a11y = enabled.contains(packageName)
        val battery = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        fun mark(ok: Boolean) = if (ok) "OK " else "-- "
        status.text = (if (JarvisService.running) "Status: ONLINE\n" else "Status: offline\n") +
            mark(mic) + "Microphone permission\n" +
            mark(overlay) + "Display over other apps\n" +
            mark(a11y) + "Accessibility\n" +
            mark(battery) + "Battery optimisation off"
        status.visibility = View.VISIBLE
    }
}
