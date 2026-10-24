package com.misrutas.ev.data

/** Una lectura del coche obtenida de la Fleet API (unidades ya convertidas a km). */
data class Snapshot(
    val ts: Long,
    val odometerKm: Double,
    val soc: Int,
    val ratedRangeKm: Double?,
    val speedKmh: Double?,
    val shift: String?,
    val lat: Double?,
    val lon: Double?,
    val powerKw: Double?,
    val outsideTemp: Double?,
    val chargingState: String?,
    /** kWh añadidos en la última sesión de carga (charge_state.charge_energy_added). */
    val chargeEnergyAdded: Double? = null,
) {
    val isDriving: Boolean
        get() = shift == "D" || shift == "R" || shift == "N" || (speedKmh ?: 0.0) > 2.0

    /** Autonomía homologada que tendría la batería al 100 %, según el BMS del coche. */
    val fullRangeKm: Double?
        get() = ratedRangeKm?.takeIf { soc > 0 }?.let { it / soc * 100 }
}

/**
 * Observación del estado de la batería: [KIND_RANGE] cuando se lee el coche con ≥ 90 % de carga
 * (autonomía al 100 % precisa) y [KIND_CHARGE] cuando se detecta una carga (kWh añadidos / % ganado).
 */
data class BatteryObs(
    val id: Long,
    val ts: Long,
    val kind: String,
    val odometerKm: Double,
    val soc: Int,
    val fullRangeKm: Double?,
    val energyAddedKwh: Double?,
    val capacityKwh: Double?,
) {
    companion object {
        const val KIND_RANGE = "range"
        const val KIND_CHARGE = "charge"
    }
}

data class Trip(
    val id: Long,
    val status: String,
    val startTs: Long,
    val endTs: Long,
    val distanceKm: Double,
    val energyKwh: Double,
    val startSoc: Int,
    val endSoc: Int,
    val maxSpeed: Double,
    val avgTemp: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val startAddr: String?,
    val endAddr: String?,
    val note: String?,
    val source: String = SOURCE_LIVE,
) {
    val recording get() = status == STATUS_RECORDING
    /** false para las rutas del modo respaldo: se conocen km y consumo, pero no la hora ni la velocidad. */
    val detailed get() = source != SOURCE_BACKUP
    val hours get() = if (detailed) (endTs - startTs) / 3_600_000.0 else 0.0
    val avgSpeed get() = if (hours > 0.005) distanceKm / hours else 0.0
    val kwhPer100: Double? get() = if (distanceKm >= 0.5) energyKwh / distanceKm * 100 else null

    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_DONE = "done"
        const val SOURCE_LIVE = "live"
        const val SOURCE_BACKUP = "backup"
    }
}

data class VehicleInfo(val vin: String, val name: String, val state: String)
