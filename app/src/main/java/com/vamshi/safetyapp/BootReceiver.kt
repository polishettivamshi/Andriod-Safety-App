package com.vamshi.safetyapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = context.getSharedPreferences("VigilantPrefs", Context.MODE_PRIVATE)
            val isProtectionEnabled = sharedPrefs.getBoolean("protection_active", false)
            
            if (isProtectionEnabled) {
                val serviceIntent = Intent(context, VoiceTriggerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}