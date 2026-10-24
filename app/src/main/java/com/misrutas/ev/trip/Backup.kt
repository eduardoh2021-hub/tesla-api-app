package com.misrutas.ev.trip

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.misrutas.ev.api.TeslaClient
import com.misrutas.ev.api.VehicleUnavailable
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot
import com.misrutas.ev.util.Fmt
import java.util.concurrent.TimeUnit

/**
 * Modo respaldo: compara el cuentakilómetros con la última lectura conocida y, si el coche se ha movido
 * sin que la app lo registrara, guarda una «ruta no detallada» con los km y el consumo estimado.
 */
object Backup {
    private const val MIN_KM = 1.0
    private const val WORK_DAILY = "backup_daily"
    private const val WORK_NOW = "backup_now"
    const val NO_CONNECTION_MS = 24 * 3_600_000L

    /**
     * Devuelve los km añadidos como ruta no detallada (0 si no había hueco) y deja [s] como nueva referencia.
     * Se llama con la primera lectura de cada sesión y desde la comprobación diaria.
     */
    fun fillGap(ctx: Context, s: Snapshot): Double {
        val prefs = Prefs(ctx)
        val last = prefs.lastSeen
        prefs.lastSeen = s
        if (last == null || s.ts <= last.ts) return 0.0
        val km = s.odometerKm - last.odometerKm
        if (km < MIN_KM) return 0.0

        val capacity = prefs.batteryKwh.toDouble()
        val db = Db.get(ctx)
        // Energía gastada = autonomía perdida (en kWh) + lo que se haya cargado entre medias.
        val drained = TripRecorder.energyKwh(last, s, capacity, BatteryHealth.baseline(prefs, db.batteryObs()))
        val added = s.chargeEnergyAdded?.takeIf { a ->
            a > 0.05 && (last.chargeEnergyAdded == null || kotlin.math.abs(a - last.chargeEnergyAdded) > 0.05)
        } ?: 0.0
        val charged = added > 0 || s.soc > last.soc + 2
        val note = buildString {
            append("Ruta no detallada (modo respaldo)")
            if (charged) append(" · hubo carga entre medias, consumo aproximado")
        }
        db.insertBackupTrip(last, s, km, (drained + added).coerceAtLeast(0.0), note)
        return km
    }

    /** Guarda cada lectura en directo como referencia, para que la ruta registrada no cuente luego como hueco. */
    fun markSeen(ctx: Context, s: Snapshot) {
        Prefs(ctx).lastSeen = s
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs(ctx).backupMode) {
            wm.cancelUniqueWork(WORK_DAILY)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Comprobación inmediata desde Ajustes, sin esperar a que pasen 24 h sin conexión. */
    fun checkNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, req)
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_FORCE = "force"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!prefs.isConfigured || TripService.running.value) return Result.success()
        if (!force && (!prefs.backupMode || System.currentTimeMillis() - prefs.lastConnectTs < Backup.NO_CONNECTION_MS)) {
            return Result.success()
        }
        val result = try {
            val s = TeslaClient.get(ctx).vehicleDataCachedOrLive()
            val km = Backup.fillGap(ctx, s)
            if (km > 0) "Añadidos ${Fmt.km(km)} sin registrar" else "Sin kilómetros nuevos · ${Fmt.km(s.odometerKm)}"
        } catch (e: VehicleUnavailable) {
            "Coche sin conexión; se reintentará mañana"
        } catch (e: Exception) {
            if (runAttemptCount < 2) return Result.retry()
            "Error: ${e.message}"
        }
        prefs.lastBackupCheckTs = System.currentTimeMillis()
        prefs.lastBackupResult = result
        Db.version.value = Db.version.value + 1
        return Result.success()
    }
}
