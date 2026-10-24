package com.misrutas.ev.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "rutas.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        /** Se incrementa con cada cambio para que la interfaz recargue. */
        val version = MutableStateFlow(0L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                status TEXT NOT NULL,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER NOT NULL,
                distance_km REAL NOT NULL DEFAULT 0,
                energy_kwh REAL NOT NULL DEFAULT 0,
                start_soc INTEGER NOT NULL DEFAULT 0,
                end_soc INTEGER NOT NULL DEFAULT 0,
                max_speed REAL NOT NULL DEFAULT 0,
                avg_temp REAL,
                start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,
                start_addr TEXT, end_addr TEXT, note TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE samples(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                odometer_km REAL NOT NULL,
                soc INTEGER NOT NULL,
                range_km REAL, speed_kmh REAL, shift TEXT, lat REAL, lon REAL,
                power_kw REAL, outside_temp REAL, charging_state TEXT)"""
        )
        db.execSQL("CREATE INDEX samples_trip ON samples(trip_id, ts)")
        createBatteryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBatteryTable(db)
    }

    private fun createBatteryTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE battery(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                odometer_km REAL NOT NULL,
                soc INTEGER NOT NULL,
                full_range_km REAL,
                energy_added_kwh REAL,
                capacity_kwh REAL)"""
        )
    }

    fun addBatteryObs(o: BatteryObs) {
        val cv = ContentValues().apply {
            put("ts", o.ts)
            put("kind", o.kind)
            put("odometer_km", o.odometerKm)
            put("soc", o.soc)
            put("full_range_km", o.fullRangeKm)
            put("energy_added_kwh", o.energyAddedKwh)
            put("capacity_kwh", o.capacityKwh)
        }
        writableDatabase.insert("battery", null, cv)
        changed()
    }

    fun batteryObs(): List<BatteryObs> =
        readableDatabase.rawQuery("SELECT * FROM battery ORDER BY ts", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    BatteryObs(
                        id = c.long("id"),
                        ts = c.long("ts"),
                        kind = c.stringOrNull("kind") ?: BatteryObs.KIND_RANGE,
                        odometerKm = c.double("odometer_km"),
                        soc = c.long("soc").toInt(),
                        fullRangeKm = c.doubleOrNull("full_range_km"),
                        energyAddedKwh = c.doubleOrNull("energy_added_kwh"),
                        capacityKwh = c.doubleOrNull("capacity_kwh"),
                    )
                )
            }
        }

    fun deleteBatteryObs(id: Long) {
        writableDatabase.delete("battery", "id = ?", arrayOf(id.toString()))
        changed()
    }

    private fun changed() = version.update { it + 1 }

    fun createTrip(s: Snapshot): Long {
        val cv = ContentValues().apply {
            put("status", Trip.STATUS_RECORDING)
            put("start_ts", s.ts)
            put("end_ts", s.ts)
            put("start_soc", s.soc)
            put("end_soc", s.soc)
            put("start_lat", s.lat)
            put("start_lon", s.lon)
        }
        return writableDatabase.insert("trips", null, cv).also { changed() }
    }

    fun addSample(tripId: Long, s: Snapshot) {
        val cv = ContentValues().apply {
            put("trip_id", tripId)
            put("ts", s.ts)
            put("odometer_km", s.odometerKm)
            put("soc", s.soc)
            put("range_km", s.ratedRangeKm)
            put("speed_kmh", s.speedKmh)
            put("shift", s.shift)
            put("lat", s.lat)
            put("lon", s.lon)
            put("power_kw", s.powerKw)
            put("outside_temp", s.outsideTemp)
            put("charging_state", s.chargingState)
        }
        writableDatabase.insert("samples", null, cv)
        // Mantiene la ruta en curso visible con su hora de fin provisional.
        writableDatabase.execSQL("UPDATE trips SET end_ts = ?, end_soc = ? WHERE id = ?", arrayOf<Any>(s.ts, s.soc, tripId))
        changed()
    }

    fun samples(tripId: Long): List<Snapshot> =
        readableDatabase.rawQuery("SELECT * FROM samples WHERE trip_id = ? ORDER BY ts", arrayOf(tripId.toString()))
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Snapshot(
                            ts = c.long("ts"),
                            odometerKm = c.double("odometer_km"),
                            soc = c.long("soc").toInt(),
                            ratedRangeKm = c.doubleOrNull("range_km"),
                            speedKmh = c.doubleOrNull("speed_kmh"),
                            shift = c.stringOrNull("shift"),
                            lat = c.doubleOrNull("lat"),
                            lon = c.doubleOrNull("lon"),
                            powerKw = c.doubleOrNull("power_kw"),
                            outsideTemp = c.doubleOrNull("outside_temp"),
                            chargingState = c.stringOrNull("charging_state"),
                        )
                    )
                }
            }

    fun openTripId(): Long? =
        readableDatabase.rawQuery(
            "SELECT id FROM trips WHERE status = ? ORDER BY id DESC LIMIT 1", arrayOf(Trip.STATUS_RECORDING)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun lastSampleTs(tripId: Long): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM samples WHERE trip_id = ?", arrayOf(tripId.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun saveFinished(t: Trip) {
        val cv = ContentValues().apply {
            put("status", t.status)
            put("start_ts", t.startTs)
            put("end_ts", t.endTs)
            put("distance_km", t.distanceKm)
            put("energy_kwh", t.energyKwh)
            put("start_soc", t.startSoc)
            put("end_soc", t.endSoc)
            put("max_speed", t.maxSpeed)
            put("avg_temp", t.avgTemp)
            put("start_lat", t.startLat)
            put("start_lon", t.startLon)
            put("end_lat", t.endLat)
            put("end_lon", t.endLon)
            put("start_addr", t.startAddr)
            put("end_addr", t.endAddr)
        }
        writableDatabase.update("trips", cv, "id = ?", arrayOf(t.id.toString()))
        changed()
    }

    fun updateNote(id: Long, note: String?) {
        val cv = ContentValues().apply { put("note", note) }
        writableDatabase.update("trips", cv, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun deleteTrip(id: Long) {
        writableDatabase.delete("samples", "trip_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun deleteAll() {
        writableDatabase.delete("samples", null, null)
        writableDatabase.delete("trips", null, null)
        changed()
    }

    fun trips(): List<Trip> =
        readableDatabase.rawQuery("SELECT * FROM trips ORDER BY start_ts DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toTrip()) }
        }

    fun trip(id: Long): Trip? =
        readableDatabase.rawQuery("SELECT * FROM trips WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toTrip() else null }

    private fun Cursor.toTrip() = Trip(
        id = long("id"),
        status = stringOrNull("status") ?: Trip.STATUS_DONE,
        startTs = long("start_ts"),
        endTs = long("end_ts"),
        distanceKm = double("distance_km"),
        energyKwh = double("energy_kwh"),
        startSoc = long("start_soc").toInt(),
        endSoc = long("end_soc").toInt(),
        maxSpeed = double("max_speed"),
        avgTemp = doubleOrNull("avg_temp"),
        startLat = doubleOrNull("start_lat"),
        startLon = doubleOrNull("start_lon"),
        endLat = doubleOrNull("end_lat"),
        endLon = doubleOrNull("end_lon"),
        startAddr = stringOrNull("start_addr"),
        endAddr = stringOrNull("end_addr"),
        note = stringOrNull("note"),
    )

    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.double(col: String) = getDouble(getColumnIndexOrThrow(col))
    private fun Cursor.doubleOrNull(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.stringOrNull(col: String) =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
}
