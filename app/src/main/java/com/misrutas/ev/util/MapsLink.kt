package com.misrutas.ev.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.misrutas.ev.data.Snapshot
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Abre la ruta en Google Maps pasando como paradas intermedias los puntos reales registrados,
 * para que Maps reconstruya el camino seguido y no el que él propondría.
 */
object MapsLink {
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    /** Límite de la URL de indicaciones: 9 paradas en la app de Maps, 3 en el navegador del móvil. */
    private const val MAX_WAYPOINTS_APP = 9
    private const val MAX_WAYPOINTS_BROWSER = 3
    /** Puntos que se separan menos de esto de la línea entre sus vecinos no aportan nada al trazado. */
    private const val MIN_DEVIATION_M = 60.0

    fun open(ctx: Context, samples: List<Snapshot>, startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        val hasApp = runCatching { ctx.packageManager.getPackageInfo(MAPS_PACKAGE, 0) }.isSuccess
        val points = samples.mapNotNull { s -> if (s.lat != null && s.lon != null) s.lat to s.lon else null }
        val route = buildList {
            add(startLat to startLon)
            addAll(points)
            add(endLat to endLon)
        }
        val waypoints = keyPoints(route, if (hasApp) MAX_WAYPOINTS_APP else MAX_WAYPOINTS_BROWSER)
        val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", fmt(startLat, startLon))
            .appendQueryParameter("destination", fmt(endLat, endLon))
            .appendQueryParameter("travelmode", "driving")
            .apply {
                if (waypoints.isNotEmpty()) {
                    appendQueryParameter("waypoints", waypoints.joinToString("|") { fmt(it.first, it.second) })
                }
            }
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (hasApp) intent.setPackage(MAPS_PACKAGE)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    /**
     * Elige hasta [max] puntos intermedios que mejor describen el recorrido: empieza con la recta origen-destino
     * y va añadiendo el punto más alejado del trazado actual (Douglas-Peucker voraz), en el orden en que se pasó.
     */
    fun keyPoints(route: List<Pair<Double, Double>>, max: Int): List<Pair<Double, Double>> {
        if (route.size < 3 || max <= 0) return emptyList()
        val latRef = route.map { it.first }.average()
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(latRef))
        val xy = route.map { (lat, lon) -> lon * mPerDegLon to lat * mPerDegLat }

        val chosen = sortedSetOf(0, route.lastIndex)
        repeat(max) {
            var best = -1
            var bestDist = MIN_DEVIATION_M
            val idx = chosen.toList()
            for (k in 0 until idx.size - 1) {
                val a = idx[k]
                val b = idx[k + 1]
                for (i in a + 1 until b) {
                    val d = distToSegment(xy[i], xy[a], xy[b])
                    if (d > bestDist) {
                        bestDist = d
                        best = i
                    }
                }
            }
            if (best < 0) return@repeat
            chosen.add(best)
        }
        return chosen.filter { it != 0 && it != route.lastIndex }.map { route[it] }
    }

    private fun distToSegment(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (px, py) = p
        val (ax, ay) = a
        val (bx, by) = b
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun fmt(lat: Double, lon: Double) = String.format(Locale.US, "%.5f,%.5f", lat, lon)
}
