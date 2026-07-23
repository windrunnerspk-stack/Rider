package com.diego.rider

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
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RiderService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_LOG = "com.diego.rider.LOG"
        const val ACTION_STATE = "com.diego.rider.STATE"
        const val ACTION_COMMAND = "com.diego.rider.COMMAND"
        private val WAKE = Regex("\\b(rider|ryder|raider|rayder|ráider)\\b", RegexOption.IGNORE_CASE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var state = "sleeping"          // sleeping | awake | processing
    private var awakeUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var brain: Brain
    private val timers = mutableListOf<Pair<Long, String>>() // fin, etiqueta
    private var chronoStart = 0L
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /* ---------------- ciclo de vida ---------------- */

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        brain = Brain(this)
        tts = TextToSpeech(this, this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rider:core")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_COMMAND) {
            val t = intent.getStringExtra("text") ?: ""
            if (t.isNotEmpty()) handler.post { runCommand(t) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
        wakeLock?.release()
        executor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------------- notificación / primer plano ---------------- */

    private fun startAsForeground() {
        val ch = NotificationChannel("rider", "RIDER", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rider")
            .setContentTitle("RIDER en línea")
            .setContentText("Di «Rider» para activarme")
            .setSmallIcon(R.drawable.ic_core)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    /* ---------------- TTS ---------------- */

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val loc = Locale("es", "CO")
            val r = tts!!.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                tts!!.language = Locale("es", "ES")
            tts!!.setSpeechRate(1.05f)
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true; stopListening() }
                override fun onDone(id: String?) { handler.post { speaking = false; startListening() } }
                @Deprecated("x") override fun onError(id: String?) { handler.post { speaking = false; startListening() } }
            })
            ttsReady = true
            setState("sleeping", "EN ESPERA · DI «RIDER»")
            say("Sistema Rider en línea. A tus órdenes, Diego.")
        }
    }

    private fun say(text: String) {
        log("RIDER: $text")
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r${System.currentTimeMillis()}")
    }

    /* ---------------- reconocimiento continuo ---------------- */

    private fun startListening() {
        if (speaking) return
        handler.post {
            try {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    recognizer!!.setRecognitionListener(listener)
                }
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                recognizer!!.startListening(i)
            } catch (_: Exception) { restartListening(800) }
        }
    }

    private fun stopListening() {
        handler.post { try { recognizer?.cancel() } catch (_: Exception) {} }
    }

    private fun restartListening(delay: Long) {
        handler.postDelayed({ if (!speaking) startListening() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            val txt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            if (txt.isNotBlank()) handleSpeech(txt.trim())
            restartListening(150)
        }
        override fun onPartialResults(b: Bundle?) {
            if (state != "sleeping") return
            val txt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            if (WAKE.containsMatchIn(txt)) { /* se confirma en onResults */ }
        }
        override fun onError(e: Int) {
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
            }
            restartListening(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 100 else 900)
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(p: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun handleSpeech(text: String) {
        if (speaking || state == "processing") return
        if (state == "sleeping") {
            if (WAKE.containsMatchIn(text)) {
                val after = WAKE.split(text).getOrNull(1)?.trim() ?: ""
                if (after.length > 3) { setAwake(false); runCommand(after) }
                else { setAwake(true) }
            }
            return
        }
        if (state == "awake") {
            if (System.currentTimeMillis() > awakeUntil) { setState("sleeping", "EN ESPERA · DI «RIDER»"); return }
            val cmd = WAKE.replace(text, "").trim()
            if (cmd.isNotEmpty()) runCommand(cmd)
        }
    }

    private fun setAwake(greet: Boolean) {
        state = "awake"
        awakeUntil = System.currentTimeMillis() + 12000
        setState("awake", "TE ESCUCHO, DIEGO")
        if (greet) say("Dime, Diego.")
        handler.postDelayed({
            if (state == "awake" && System.currentTimeMillis() >= awakeUntil)
                setState("sleeping", "EN ESPERA · DI «RIDER»")
        }, 12500)
    }

    /* ---------------- comandos ---------------- */

    private val NUMS = mapOf("un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4,
        "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11,
        "doce" to 12, "quince" to 15, "veinte" to 20, "veinticinco" to 25, "treinta" to 30,
        "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "noventa" to 90)

    private fun parseNum(s: String): Int? {
        val t = s.trim().lowercase()
        return t.toIntOrNull() ?: NUMS[t]
    }

    private fun horaExacta(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE); val s = c.get(Calendar.SECOND)
        return "$h ${if (h == 1) "hora" else "horas"}, $m ${if (m == 1) "minuto" else "minutos"} y $s segundos"
    }

    private fun runCommand(text: String) {
        log("DIEGO: $text")
        state = "processing"
        setState("processing", "PROCESANDO…")
        val t = text.lowercase()

        fun done(reply: String) { state = "sleeping"; setState("sleeping", "EN ESPERA · DI «RIDER»"); say(reply) }

        // hora exacta
        if (Regex("(qué|que|dime la|dame la).*hora|^hora$").containsMatchIn(t)) {
            done("Son exactamente las ${horaExacta()}."); return
        }
        // fecha
        if (Regex("(qué|que).*(día|fecha)|fecha de hoy").containsMatchIn(t)) {
            val f = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es")).format(Date())
            done("Hoy es $f."); return
        }
        // temporizador
        val m = Regex("(?:temporizador|timer|cuenta regresiva|alarma)\\s*(?:de|en|para)?\\s*([\\wáéíóú ]+?)\\s*(segundos?|minutos?|horas?)").find(t)
            ?: Regex("(?:en|dentro de)\\s+([\\wáéíóú ]+?)\\s*(segundos?|minutos?|horas?)[, ]*(?:avísame|avisame|recuérdame|recuerdame)").find(t)
        if (m != null) {
            val n = parseNum(m.groupValues[1])
            if (n != null) {
                val unit = when { m.groupValues[2].startsWith("seg") -> 1; m.groupValues[2].startsWith("min") -> 60; else -> 3600 }
                startTimer(n * unit, "${n} ${m.groupValues[2]}")
                state = "sleeping"; setState("sleeping", "EN ESPERA · DI «RIDER»")
                return
            }
        }
        // cronómetro
        if (t.contains("cronómetro") || t.contains("cronometro")) {
            if (Regex("detén|deten|para|detener|parar").containsMatchIn(t)) {
                if (chronoStart == 0L) { done("No hay ningún cronómetro en marcha."); return }
                val ms = System.currentTimeMillis() - chronoStart; chronoStart = 0
                val s = ms / 1000; val mm = s / 60; val ss = s % 60
                done("Cronómetro detenido en ${if (mm > 0) "$mm ${if (mm == 1L) "minuto" else "minutos"} y " else ""}$ss segundos.")
            } else { chronoStart = System.currentTimeMillis(); done("Cronómetro iniciado.") }
            return
        }
        // tareas
        val prefs = getSharedPreferences("rider", MODE_PRIVATE)
        val addM = Regex("(?:agrega|añade|anota|apunta|crea)\\s+(?:una\\s+)?(?:tarea|nota|pendiente)?\\s*(?:de|que|:)?\\s*(.+)").find(t)
            ?: Regex("recu[eé]rdame\\s+(?:que\\s+)?(.+)").find(t)
        if (addM != null && !t.contains("temporizador") && !t.contains("timer")) {
            val task = addM.groupValues[1].trim()
            val list = prefs.getString("tasks", "")!!.split("\u0001").filter { it.isNotEmpty() }.toMutableList()
            list.add(task)
            prefs.edit().putString("tasks", list.joinToString("\u0001")).apply()
            done("Anotado: $task."); return
        }
        if (Regex("(qué|que|cuáles|cuales|lee|léeme|leeme|dime|muestra).*(tareas|pendientes)").containsMatchIn(t)) {
            val list = prefs.getString("tasks", "")!!.split("\u0001").filter { it.isNotEmpty() }
            if (list.isEmpty()) { done("No tienes tareas pendientes, Diego."); return }
            done("Tienes ${list.size} pendientes. " + list.mapIndexed { i, x -> "${i + 1}: $x" }.joinToString(". ") + ".")
            return
        }
        val delM = Regex("(?:completa|termina|elimina|borra|quita)\\s+(?:la\\s+)?tarea\\s+(?:de\\s+|número\\s+)?(.+)").find(t)
        if (delM != null) {
            val q = delM.groupValues[1].trim()
            val list = prefs.getString("tasks", "")!!.split("\u0001").filter { it.isNotEmpty() }.toMutableList()
            val idx = q.toIntOrNull()?.minus(1) ?: list.indexOfFirst { it.lowercase().contains(q) }
            if (idx < 0 || idx >= list.size) { done("No encontré esa tarea."); return }
            val name = list.removeAt(idx)
            prefs.edit().putString("tasks", list.joinToString("\u0001")).apply()
            done("Listo, eliminada: $name."); return
        }
        // dormir
        if (Regex("^(duerme|descansa|silencio|nada|cancela)$").matches(t.trim())) {
            state = "sleeping"; setState("sleeping", "EN ESPERA · DI «RIDER»"); return
        }
        // cerebro
        brain.ask(text) { reply ->
            handler.post { done(reply) }
        }
    }

    /* ---------------- temporizadores ---------------- */

    private fun startTimer(secs: Int, label: String) {
        val end = System.currentTimeMillis() + secs * 1000L
        timers.add(end to label)
        val endStr = clockFmt.format(Date(end))
        say("Temporizador de $label iniciado. Terminará a las $endStr.")
        handler.postDelayed({
            timers.removeAll { it.first == end }
            vibrate()
            say("Diego, tu temporizador de $label ha terminado. Son exactamente las ${horaExacta()}.")
        }, secs * 1000L)
    }

    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 500), -1))
        } catch (_: Exception) {}
    }

    /* ---------------- utilidades ---------------- */

    private fun log(line: String) {
        sendBroadcast(Intent(ACTION_LOG).putExtra("line", line).setPackage(packageName))
    }

    private fun setState(s: String, label: String) {
        state = s
        sendBroadcast(Intent(ACTION_STATE).putExtra("state", label).setPackage(packageName))
    }
}
