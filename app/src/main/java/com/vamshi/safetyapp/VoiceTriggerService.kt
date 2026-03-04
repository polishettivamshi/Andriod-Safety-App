package com.vamshi.safetyapp

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.util.*
import kotlin.math.sqrt

class VoiceTriggerService : Service(), SensorEventListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private var lastShakeTime: Long = 0
    private var lastEmergencyTime: Long = 0
    private val SHAKE_THRESHOLD = 3.2f // Optimized for intentional shakes
    private val handler = Handler(Looper.getMainLooper())
    private var isCurrentlyListening = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        createNotificationChannel()
        startForeground(1, createNotification())
        initSpeechRecognizer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "voice_trigger_channel",
                "Vigilant Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, "voice_trigger_channel")
            .setContentTitle("Vigilant Active")
            .setContentText("Listening for 'Help' and Shakes...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun initSpeechRecognizer() {
        if (speechRecognizer != null) {
            speechRecognizer?.destroy()
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this@VoiceTriggerService.packageName)
            
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isCurrentlyListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isCurrentlyListening = false }
            override fun onError(error: Int) {
                isCurrentlyListening = false
                handler.postDelayed({ startListening() }, if (error == 7 || error == 6) 500 else 2000)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                processMatches(matches)
                startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                processMatches(matches)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        if (isCurrentlyListening) return
        handler.post {
            try { speechRecognizer?.startListening(recognizerIntent) } catch (e: Exception) {}
        }
    }

    private fun processMatches(matches: ArrayList<String>?) {
        val now = System.currentTimeMillis()
        if (now - lastEmergencyTime < 10000) return 

        matches?.forEach { match ->
            if (match.lowercase().contains("help")) {
                Log.d("VoiceTrigger", "MATCH FOUND: $match")
                lastEmergencyTime = now
                vibratePhone()
                handler.post { Toast.makeText(applicationContext, "HELP detected!", Toast.LENGTH_SHORT).show() }
                triggerEmergency()
                return
            }
        }
    }

    private fun triggerEmergency() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.e("VoiceTrigger", "Location permission missing in Service check")
            sendSosWithLocation(null)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { currentLoc ->
                    sendSosWithLocation(currentLoc ?: lastLoc)
                }
                .addOnFailureListener { e -> 
                    Log.e("VoiceTrigger", "Current location fetch failed: ${e.message}")
                    sendSosWithLocation(lastLoc) 
                }
        }.addOnFailureListener { e ->
            Log.e("VoiceTrigger", "Last location fetch failed: ${e.message}")
            sendSosWithLocation(null)
        }
    }

    private fun sendSosWithLocation(location: Location?) {
        val message = if (location != null) {
            "EMERGENCY! I need help.\nLocation: https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "EMERGENCY! I need help. Location unavailable (Please check GPS & Permissions)."
        }
        sendSMS(message)
    }

    private fun sendSMS(message: String) {
        val sharedPrefs = getSharedPreferences("VigilantPrefs", Context.MODE_PRIVATE)
        val numbers = sharedPrefs.getStringSet("emergency_numbers", emptySet()) ?: emptySet()
        
        Log.d("VoiceTrigger", "Attempting to send SMS to ${numbers.size} contacts")
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e("VoiceTrigger", "SMS permission missing in Service")
            return
        }

        if (numbers.isEmpty()) {
            Log.e("VoiceTrigger", "No contacts saved")
            return
        }

        numbers.forEach { number ->
            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                
                smsManager.sendTextMessage(number, null, message, null, null)
                Log.d("VoiceTrigger", "SMS Success: sent to $number")
            } catch (e: Exception) {
                Log.e("VoiceTrigger", "SMS Failed for $number: ${e.message}")
            }
        }
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
            
            if (gForce > 1.5f) Log.d("ShakeDebug", "G-Force: $gForce")

            if (gForce > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 7000) {
                    lastShakeTime = now
                    Log.d("ShakeDebug", "SHAKE DETECTED!")
                    vibratePhone()
                    handler.post { Toast.makeText(applicationContext, "Shake detected!", Toast.LENGTH_SHORT).show() }
                    triggerEmergency()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        speechRecognizer?.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}