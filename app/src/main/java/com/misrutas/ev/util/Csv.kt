package com.misrutas.ev.util

import android.content.Context
import android.net.Uri
import com.misrutas.ev.data.Db

/** Exporta todas las rutas a CSV con «;» y coma decimal, para abrirlo directamente en Excel en español. */
object Csv {
    fun exportTrips(ctx: Context, uri: Uri): Int {
        val trips = Db.get(ctx).trips().filter { !it.recording }.sortedBy { it.startTs }
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
            w.write("﻿")
            w.write("inicio;fin;origen;destino;km;minutos;velocidad_media_kmh;velocidad_max_kmh;energia_kwh;kwh_100km;bateria_inicio;bateria_fin;temp_exterior_c;nota\n")
            trips.forEach { t ->
                val cols = listOf(
                    Fmt.dateTime(t.startTs),
                    Fmt.dateTime(t.endTs),
                    t.startAddr.orEmpty(),
                    t.endAddr.orEmpty(),
                    Fmt.dec(t.distanceKm, 2),
                    ((t.endTs - t.startTs) / 60_000).toString(),
                    Fmt.dec(t.avgSpeed, 1),
                    Fmt.dec(t.maxSpeed, 0),
                    Fmt.dec(t.energyKwh, 3),
                    t.kwhPer100?.let { Fmt.dec(it, 1) }.orEmpty(),
                    t.startSoc.toString(),
                    t.endSoc.toString(),
                    t.avgTemp?.let { Fmt.dec(it, 1) }.orEmpty(),
                    t.note.orEmpty(),
                )
                w.write(cols.joinToString(";") { quote(it) })
                w.write("\n")
            }
        }
        return trips.size
    }

    private fun quote(s: String) =
        if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
