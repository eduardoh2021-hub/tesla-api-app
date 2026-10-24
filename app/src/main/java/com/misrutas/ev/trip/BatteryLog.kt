package com.misrutas.ev.trip

import android.content.Context
import com.misrutas.ev.data.BatteryObs
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot

/**
 * Registra observaciones de la batería durante una sesión de lectura:
 * - una autonomía al 100 % por sesión cuando la carga es ≥ 90 % (lo ideal: justo tras cargar al 100 %);
 * - una estimación de capacidad cada vez que se detecta una carga nueva (kWh añadidos / % ganado).
 */
class BatteryLog(ctx: Context) {
    private val db = Db.get(ctx)
    private val prefs = Prefs(ctx)
    private var rangeLogged = false

    fun observe(s: Snapshot) {
        val full = s.fullRangeKm
        if (!rangeLogged && full != null && s.soc >= MIN_SOC_FOR_RANGE) {
            db.addBatteryObs(BatteryObs(0, s.ts, BatteryObs.KIND_RANGE, s.odometerKm, s.soc, full, null, null))
            rangeLogged = true
        }

        val added = s.chargeEnergyAdded
        val lastSoc = prefs.lastSeenSoc
        val lastAdded = prefs.lastSeenEnergyAdded.toDouble()
        val gained = s.soc - lastSoc
        // charge_energy_added cambia al empezar una carga nueva; si además ha subido bastante el %, es una carga medible.
        if (added != null && lastSoc >= 0 && added >= 5 && kotlin.math.abs(added - lastAdded) > 0.05 && gained >= MIN_SOC_GAIN) {
            val capacity = added / (gained / 100.0)
            if (capacity in 30.0..110.0) {
                db.addBatteryObs(BatteryObs(0, s.ts, BatteryObs.KIND_CHARGE, s.odometerKm, s.soc, full, added, capacity))
            }
        }
        prefs.lastSeenSoc = s.soc
        if (added != null) prefs.lastSeenEnergyAdded = added.toFloat()
    }

    companion object {
        const val MIN_SOC_FOR_RANGE = 90
        const val MIN_SOC_GAIN = 25
    }
}

/** Cálculos de salud de la batería a partir de las observaciones guardadas. */
object BatteryHealth {
    data class Estimate(
        val baselineFullRangeKm: Double,
        val baselineIsManual: Boolean,
        val currentFullRangeKm: Double,
        val healthPct: Double,
        val capacityNowKwh: Double,
        val chargeCapacityKwh: Double?,
        val chargeCount: Int,
        /** Variación de salud cada 10.000 km según la recta de tendencia (negativa = degradación). */
        val trendPer10kKm: Double?,
    )

    /** Autonomía al 100 % de la batería nueva: la configurada o, si no, la mayor observada. */
    fun baseline(prefs: Prefs, obs: List<BatteryObs>): Double? =
        prefs.newFullRangeKm.toDouble().takeIf { it > 0 }
            ?: obs.filter { it.kind == BatteryObs.KIND_RANGE }.mapNotNull { it.fullRangeKm }.maxOrNull()

    fun estimate(prefs: Prefs, obs: List<BatteryObs>): Estimate? {
        val ranges = obs.filter { it.kind == BatteryObs.KIND_RANGE && it.fullRangeKm != null }
        val base = baseline(prefs, obs) ?: return null
        if (ranges.isEmpty()) return null
        // Mediana de las 5 últimas para no reaccionar a una lectura suelta.
        val current = ranges.takeLast(5).map { it.fullRangeKm!! }.sorted().let { it[it.size / 2] }
        val health = current / base * 100
        val charges = obs.filter { it.kind == BatteryObs.KIND_CHARGE }.mapNotNull { it.capacityKwh }
        val chargeCap = charges.takeLast(7).sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

        val trend = if (ranges.size >= 4 && ranges.last().odometerKm - ranges.first().odometerKm > 1000) {
            val xs = ranges.map { it.odometerKm }
            val ys = ranges.map { it.fullRangeKm!! / base * 100 }
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx > 0) xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx * 10_000 else null
        } else null

        return Estimate(
            baselineFullRangeKm = base,
            baselineIsManual = prefs.newFullRangeKm > 0,
            currentFullRangeKm = current,
            healthPct = health,
            capacityNowKwh = prefs.batteryKwh * health / 100,
            chargeCapacityKwh = chargeCap,
            chargeCount = charges.size,
            trendPer10kKm = trend,
        )
    }
}
