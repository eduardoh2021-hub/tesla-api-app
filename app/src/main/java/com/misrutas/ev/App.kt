package com.misrutas.ev

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.misrutas.ev.trip.Backup

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Registro de rutas", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT)
        )
        Backup.schedule(this)
    }

    companion object {
        const val CH_RECORDING = "recording"
        const val CH_ALERTS = "alerts"
    }
}
