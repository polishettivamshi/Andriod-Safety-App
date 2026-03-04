package com.vamshi.safetyapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : ComponentActivity() {

    private val emergencyNumbers = mutableStateListOf<String>()
    private var isProtectionEnabled by mutableStateOf(false)

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVoiceService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()
        requestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFD32F2F), 
                    background = Color.White,
                    surface = Color(0xFFF5F5F5),
                    onSurface = Color(0xFF212121)
                )
            ) {
                VigilantApp()
            }
        }
    }

    private fun loadState() {
        val sharedPrefs = getSharedPreferences("VigilantPrefs", Context.MODE_PRIVATE)
        val savedSet = sharedPrefs.getStringSet("emergency_numbers", emptySet()) ?: emptySet()
        emergencyNumbers.clear()
        emergencyNumbers.addAll(savedSet)
        isProtectionEnabled = sharedPrefs.getBoolean("protection_active", false)
    }

    private fun saveState(isActive: Boolean) {
        val sharedPrefs = getSharedPreferences("VigilantPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putStringSet("emergency_numbers", emergencyNumbers.toSet())
            .putBoolean("protection_active", isActive).apply()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS, 
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE, 
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            requestBackgroundLocationPermission()
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Background Location Access")
                    .setMessage("This app needs background location access to track your location during an emergency even when the app is closed.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun startVoiceService() {
        if (!isLocationEnabled()) {
            promptEnableLocation()
            return
        }

        val serviceIntent = Intent(this, VoiceTriggerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isProtectionEnabled = true
        saveState(true)
    }

    private fun promptEnableLocation() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        LocationServices.getSettingsClient(this).checkLocationSettings(builder.build())
            .addOnSuccessListener { startVoiceService() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        locationSettingsLauncher.launch(IntentSenderRequest.Builder(exception.resolution.intentSender).build())
                    } catch (e: Exception) { }
                } else {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceTriggerService::class.java))
        isProtectionEnabled = false
        saveState(false)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VigilantApp() {
        var newNumber by remember { mutableStateOf("") }

        Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color.White) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(48.dp))
                Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = if (isProtectionEnabled) Color(0xFF388E3C) else Color(0xFFD32F2F), modifier = Modifier.size(64.dp)
                )
                Text("Safety App", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                
                if (isProtectionEnabled) {
                    Surface(modifier = Modifier.padding(top = 16.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFFE8F5E9)) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF388E3C)))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PROTECTION ACTIVE", style = MaterialTheme.typography.labelMedium, color = Color(0xFF388E3C), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { if (isProtectionEnabled) stopVoiceService() else if (emergencyNumbers.isNotEmpty()) startVoiceService() else Toast.makeText(this@MainActivity, "Add a contact first", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isProtectionEnabled) Color.DarkGray else Color(0xFFD32F2F))
                ) { Text(if (isProtectionEnabled) "STOP PROTECTION" else "START PROTECTION", fontWeight = FontWeight.Bold) }

                Spacer(modifier = Modifier.height(24.dp))

                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)), shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("EMERGENCY CONTACTS", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        emergencyNumbers.forEachIndexed { index, number ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(number, fontWeight = FontWeight.Medium)
                                IconButton(onClick = { emergencyNumbers.removeAt(index); saveState(isProtectionEnabled) }) { Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                            }
                        }
                        if (emergencyNumbers.size < 3) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextField(value = newNumber, onValueChange = { if (it.length <= 15) newNumber = it }, placeholder = { Text("Enter Number") }, modifier = Modifier.weight(1f),
                                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
                                IconButton(onClick = { if (newNumber.isNotEmpty() && emergencyNumbers.size < 3) { emergencyNumbers.add(newNumber); saveState(isProtectionEnabled); newNumber = "" } }) { Icon(Icons.Default.Add, null, tint = Color(0xFFD32F2F)) }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isProtectionEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && isLocationEnabled()) startVoiceService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestBackgroundLocationPermission()
            }
        }
    }
}