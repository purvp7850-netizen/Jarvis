package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/** Always-on foreground service: listens for "Jarvis", thinks with Claude, speaks, acts. */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var running = false
        private const val CHANNEL = "jarvis"
        private const val ACTIVE_MS = 25_000L
        private val WAKE = Regex(
            "\\b(jarvis|jarves|jervis|garvis|jarvish)\\b[,.!?]?|जार्विस|जारविस",
            RegexOption.IGNORE_CASE
        )
        private val STOP = listOf("stop listening", "go to sleep", "that's all", "that will be all", "thank you jarvis")
        private val QUIT = listOf("shut down", "exit jarvis", "power off jarvis", "goodbye jarvis")
        private val YES = setOf("yes", "yeah", "yep", "sure", "confirm", "ok", "okay", "haan", "ha", "हाँ", "हां")
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var tts: TextToSpeech
    private lateinit var tools: Tools
    private lateinit var brain: Brain
    private lateinit var userName: String
    private lateinit var lang: String
    private var recognizer: SpeechRecognizer? = null

    private var speaking = false
    private var quitAfter = false
    private var sleepAfter = false
    private var activeUntil = 0L
    private var utt = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        userName = Prefs.get(this, "name", "sir")
        lang = Prefs.get(this, "lang", "en-IN")
        tools = Tools(this)
        brain = Brain(this, tools)
        startInForeground()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        if (this::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis is online")
            .setContentText("Say \"Jarvis\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- speech out
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            stopSelf()
            return
        }
        tts.setLanguage(Locale.forLanguageTag(lang))
        tts.setPitch(0.85f)
        tts.setSpeechRate(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = afterSpeech()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = afterSpeech()
        })
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is not available on this phone. Install the Google app.", quit = true)
            return
        }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = if (h < 12) "morning" else if (h < 18) "afternoon" else "evening"
        say("Good $part, $userName. All systems online.")
    }

    private fun say(text: String, quit: Boolean = false, sleep: Boolean = false) {
        main.post {
            speaking = true
            quitAfter = quit
            sleepAfter = sleep
            recognizer?.cancel()
            val clean = text.replace(Regex("[*_`#>]"), "")
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u${utt++}")
        }
    }

    private fun afterSpeech() {
        main.post {
            speaking = false
            if (quitAfter) {
                stopSelf()
                return@post
            }
            activeUntil = if (sleepAfter) 0L else System.currentTimeMillis() + ACTIVE_MS
            listen()
        }
    }

    // ---------------------------------------------------------------- speech in
    private fun listen() {
        if (speaking) return
        try {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            r.startListening(i)
        } catch (e: Exception) {
            main.postDelayed({ listen() }, 2000)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (speaking) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    say("I need microphone permission. Please open the Jarvis app.", quit = true)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ listen() }, 1500)
                else -> main.postDelayed({ listen() }, 400)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) listen() else handle(text)
        }
    }

    // ---------------------------------------------------------------- brain
    private fun isYes(t: String): Boolean {
        val low = t.lowercase()
        val tokens = low.split(Regex("[^\\p{L}]+"))
        return tokens.any { it in YES } || low.contains("go ahead") || low.contains("do it") || low.contains("kar do")
    }

    private fun handle(text: String) {
        val pending = tools.pending
        if (pending != null) {
            tools.pending = null
            if (isYes(text)) {
                io.execute {
                    val r = try { pending.invoke() } catch (e: Exception) { "That failed: ${e.message}" }
                    say(r)
                }
            } else {
                say("Cancelled.")
            }
            return
        }

        val m = WAKE.find(text)
        val woke = m != null
        val cmd = if (m != null) text.removeRange(m.range).trim(' ', ',', '.', '!', '?') else text
        if (!woke && System.currentTimeMillis() > activeUntil) {
            listen()
            return
        }
        val low = cmd.lowercase()
        when {
            cmd.isBlank() -> say("Yes, $userName?")
            QUIT.any { low.contains(it) } -> say("Powering down. Goodbye.", quit = true)
            STOP.any { low.contains(it) } -> say("Standing by.", sleep = true)
            else -> io.execute {
                val reply = try { brain.think(cmd) } catch (e: Exception) { "Something went wrong on my end." }
                say(reply)
            }
        }
    }
}
