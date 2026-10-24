package com.misrutas.ev.trip

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import com.misrutas.ev.App
import com.misrutas.ev.R
import com.misrutas.ev.api.TeslaClient
import com.misrutas.ev.api.VehicleUnavailable
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot
import com.misrutas.ev.ui.MainActivity
import com.misrutas.ev.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Servicio en primer plano que consulta el coche mientras el móvil está conectado a su Bluetooth
 * (o mientras el registro manual esté activo) y alimenta al [TripRecorder].
 */
class TripService : Service() {

    companion object {
        const val ACTION_BT_CONNECTED = "com.misrutas.ev.BT_CONNECTED"
        const val ACTION_BT_GONE = "com.misrutas.ev.BT_GONE"
        const val ACTION_MANUAL = "com.misrutas.ev.MANUAL"
        const val ACTION_STOP = "com.misrutas.ev.STOP"
        private const val NOTIF_ID = 1
        private const val NOTIF_ALERT_ID = 2

        val running = MutableStateFlow(false)
        val status = MutableStateFlow("")

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, TripService::class.java).setAction(action)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                cannotStartNotice(ctx)
            }
        }

        /** Android no deja arrancar el servicio en segundo plano: se avisa para que el usuario lo inicie con un toque. */
        private fun cannotStartNotice(ctx: Context) {
            val open = PendingIntent.getActivity(
                ctx, 3,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_MANUAL, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, App.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle("Coche conectado")
                .setContentText("Toca para empezar a registrar la ruta. Quita las restricciones de batería para que sea automático.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, n) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    @Volatile private var btGoneAt: Long? = null
    @Volatile private var manual = false
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, notification(status.value.ifBlank { "Conectando con el coche…" }),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_BT_CONNECTED -> btGoneAt = null
            ACTION_MANUAL -> {
                manual = true
                btGoneAt = null
            }
            ACTION_BT_GONE -> btGoneAt = System.currentTimeMillis()
            ACTION_STOP -> stopRequested = true
        }
        running.value = true
        if (loopJob == null) loopJob = scope.launch { runLoop() }
        wake.trySend(Unit)
        return START_NOT_STICKY
    }

    private suspend fun runLoop() {
        val prefs = Prefs(this)
        val client = TeslaClient.get(this)
        val rec = TripRecorder(this)
        val battery = BatteryLog(this)
        rec.resume()
        val startedAt = System.currentTimeMillis()
        var lastOk = startedAt
        var lastActivity = startedAt
        var last: Snapshot? = null

        while (!stopRequested) {
            var unavailable = false
            try {
                val s = client.vehicleData()
                last = s
                lastOk = System.currentTimeMillis()
                rec.onSnapshot(s)
                battery.observe(s)
                if (s.isDriving || rec.openTripId != null) lastActivity = lastOk
                status.value = describe(rec, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VehicleUnavailable) {
                unavailable = true
                status.value = "Coche dormido o sin conexión; reintentando…"
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            }
            updateNotification(status.value)

            val now = System.currentTimeMillis()
            val gone = btGoneAt
            // Te has bajado del coche: se cierra la ruta en cuanto el coche esté aparcado.
            if (gone != null && (rec.openTripId == null || unavailable || last?.isDriving != true)) break
            // Conectado pero sin moverse: no tiene sentido seguir gastando peticiones.
            if (rec.openTripId == null && now - lastActivity > (if (manual) 20 else 30) * 60_000L) break
            if (now - lastOk > 15 * 60_000L) break

            val waitMs = when {
                gone != null -> 20_000L
                rec.openTripId != null || !rec.sessionHadTrip -> prefs.pollSeconds * 1000L
                else -> maxOf(prefs.pollSeconds, 120) * 1000L
            }
            withTimeoutOrNull(waitMs) { wake.receive() }
        }

        rec.finish()
        status.value = ""
        withContext(Dispatchers.Main) {
            loopJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun describe(rec: TripRecorder, s: Snapshot): String {
        val startOdo = rec.tripStartOdo
        return if (rec.openTripId != null && startOdo != null) {
            val km = Fmt.dec(s.odometerKm - startOdo)
            val speed = s.speedKmh?.let { " · ${it.toInt()} km/h" }.orEmpty()
            "Ruta en curso · $km km$speed · ${s.soc} %"
        } else {
            "Coche aparcado · batería ${s.soc} % · esperando a que arranques"
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, App.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("Registrando rutas")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) }
    }

    override fun onDestroy() {
        scope.cancel()
        running.value = false
        super.onDestroy()
    }
}
