package com.misrutas.ev.trip

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot
import com.misrutas.ev.data.Trip
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Máquina de estados que convierte lecturas sucesivas del coche en rutas:
 * una ruta empieza con la primera lectura en marcha (D/R/N o velocidad > 0) y termina
 * cuando el coche lleva [STOP_GAP_MS] aparcado o cuando el servicio se detiene.
 */
class TripRecorder(private val ctx: Context) {
    private val db = Db.get(ctx)
    private var lastParked: Snapshot? = null
    private var parkedSince: Long? = null

    var openTripId: Long? = null
        private set
    var tripStartOdo: Double? = null
        private set
    var sessionHadTrip = false
        private set
    val isParkedNow get() = parkedSince != null

    /** Recupera una ruta que quedó abierta si el proceso se cerró; la cierra si es antigua. */
    suspend fun resume() {
        val id = db.openTripId() ?: return
        val last = db.lastSampleTs(id) ?: 0
        if (System.currentTimeMillis() - last > 30 * 60_000L) {
            finalizeTrip(ctx, id)
        } else {
            openTripId = id
            tripStartOdo = db.samples(id).firstOrNull()?.odometerKm
            sessionHadTrip = true
        }
    }

    suspend fun onSnapshot(s: Snapshot) {
        val id = openTripId
        if (id == null) {
            if (s.isDriving) {
                // La última lectura aparcada sirve de punto de partida (odómetro y batería antes de moverse).
                val base = lastParked?.takeIf {
                    s.ts - it.ts < 15 * 60_000L && s.odometerKm - it.odometerKm in 0.0..3.0
                }
                val newId = db.createTrip(base ?: s)
                base?.let { db.addSample(newId, it) }
                db.addSample(newId, s)
                openTripId = newId
                tripStartOdo = (base ?: s).odometerKm
                parkedSince = null
                sessionHadTrip = true
            } else {
                lastParked = s
            }
            return
        }
        db.addSample(id, s)
        if (s.isDriving) {
            parkedSince = null
        } else if (parkedSince == null) {
            parkedSince = s.ts
        }
        val p = parkedSince
        if (p != null && s.ts - p >= STOP_GAP_MS) {
            finish()
            lastParked = s
        }
    }

    suspend fun finish(): Boolean {
        val id = openTripId ?: return false
        openTripId = null
        parkedSince = null
        tripStartOdo = null
        return finalizeTrip(ctx, id)
    }

    companion object {
        const val STOP_GAP_MS = 5 * 60_000L

        /** Calcula distancia, tiempos, energía y direcciones a partir de las lecturas guardadas. */
        suspend fun finalizeTrip(ctx: Context, id: Long): Boolean {
            val db = Db.get(ctx)
            val trip = db.trip(id) ?: return false
            val samples = db.samples(id)
            if (samples.isEmpty()) {
                db.deleteTrip(id)
                return false
            }
            val firstDrive = samples.indexOfFirst { it.isDriving }.let { if (it < 0) 0 else it }
            val lastDrive = samples.indexOfLast { it.isDriving }.let { if (it < 0) samples.lastIndex else it }
            val moving = samples[firstDrive]
            val lastMoving = samples[lastDrive]
            val start = if (firstDrive > 0) samples[firstDrive - 1] else moving
            val end = if (lastDrive < samples.lastIndex) samples[lastDrive + 1] else lastMoving

            val distance = (end.odometerKm - start.odometerKm).coerceAtLeast(0.0)
            if (distance < 0.2) {
                db.deleteTrip(id)
                return false
            }

            // El inicio/fin real cae entre dos lecturas: se estima con la velocidad registrada.
            val startTs = if (start !== moving) {
                estimate(moving.ts, -(moving.odometerKm - start.odometerKm), moving.speedKmh, start.ts, moving.ts)
            } else moving.ts
            val endTs = if (end !== lastMoving) {
                estimate(lastMoving.ts, end.odometerKm - lastMoving.odometerKm, lastMoving.speedKmh, lastMoving.ts, end.ts)
            } else end.ts

            val driving = samples.subList(firstDrive, lastDrive + 1)
            val temps = samples.mapNotNull { it.outsideTemp }
            val prefs = Prefs(ctx)
            val newFullRange = BatteryHealth.baseline(prefs, db.batteryObs())
            val energy = energyKwh(start, end, prefs.batteryKwh.toDouble(), newFullRange)

            db.saveFinished(
                trip.copy(
                    status = Trip.STATUS_DONE,
                    startTs = startTs,
                    endTs = endTs.coerceAtLeast(startTs + 60_000),
                    distanceKm = distance,
                    energyKwh = energy,
                    startSoc = start.soc,
                    endSoc = end.soc,
                    maxSpeed = driving.maxOfOrNull { it.speedKmh ?: 0.0 } ?: 0.0,
                    avgTemp = if (temps.isEmpty()) null else temps.average(),
                    startLat = start.lat,
                    startLon = start.lon,
                    endLat = end.lat,
                    endLon = end.lon,
                    startAddr = trip.startAddr ?: geocode(ctx, start.lat, start.lon),
                    endAddr = trip.endAddr ?: geocode(ctx, end.lat, end.lon),
                )
            )
            return true
        }

        /** Momento de paso estimado: ts + distancia/velocidad, acotado al intervalo entre lecturas. */
        private fun estimate(ts: Long, deltaKm: Double, speed: Double?, min: Long, max: Long): Long {
            val v = speed ?: 0.0
            val t = if (v > 3) ts + (deltaKm / v * 3_600_000).toLong() else (min + max) / 2
            return t.coerceIn(min, max)
        }

        /**
         * Energía consumida a partir de la autonomía homologada (más precisa que el % de batería,
         * que es un entero). Cada km de autonomía vale capacidad de fábrica / autonomía de fábrica al 100 %,
         * así la degradación no infla el consumo; sin esa referencia se usa la autonomía al 100 % actual.
         */
        fun energyKwh(a: Snapshot, b: Snapshot, capacityKwh: Double, newFullRangeKm: Double? = null): Double {
            val ra = a.ratedRangeKm
            val rb = b.ratedRangeKm
            if (ra != null && rb != null && a.soc > 5) {
                val fullRangeKm = newFullRangeKm ?: (ra / a.soc * 100)
                return (ra - rb) * capacityKwh / fullRangeKm
            }
            return (a.soc - b.soc) / 100.0 * capacityKwh
        }

        private suspend fun geocode(ctx: Context, lat: Double?, lon: Double?): String? {
            if (lat == null || lon == null || !Geocoder.isPresent()) return null
            return withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont ->
                    try {
                        Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                cont.resume(addresses.firstOrNull()?.let(::format))
                            }

                            override fun onError(errorMessage: String?) {
                                cont.resume(null)
                            }
                        })
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }
            }
        }

        private fun format(a: Address): String? {
            val street = a.thoroughfare?.let { t -> a.subThoroughfare?.let { "$t $it" } ?: t }
            val parts = listOfNotNull(street, a.locality ?: a.subAdminArea)
            return parts.joinToString(", ").ifBlank { a.getAddressLine(0) }
        }
    }
}
