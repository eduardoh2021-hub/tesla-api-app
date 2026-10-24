package com.misrutas.ev.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot
import com.misrutas.ev.data.Trip
import com.misrutas.ev.trip.TripRecorder
import com.misrutas.ev.trip.TripService
import com.misrutas.ev.util.Fmt
import com.misrutas.ev.util.MapsLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripDetailScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val version by Db.version.collectAsState()
    val running by TripService.running.collectAsState()
    var trip by remember { mutableStateOf<Trip?>(null) }
    var samples by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var editNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id, version) {
        withContext(Dispatchers.IO) {
            trip = db.trip(id)
            samples = db.samples(id)
        }
    }

    val t = trip
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
            Text(
                t?.let { Fmt.dateTime(it.startTs) } ?: "",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (t == null) return@Column

        val route = listOfNotNull(t.startAddr, t.endAddr).joinToString("  →  ")
        if (route.isNotBlank()) Text(route, style = MaterialTheme.typography.bodyLarge)
        if (!t.note.isNullOrBlank()) Text(t.note, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (t.recording) {
            SectionCard("Ruta en curso", Modifier.padding(top = 12.dp)) {
                Text("Las cifras se calculan al terminar el trayecto.")
                if (!running) {
                    Button(
                        onClick = { scope.launch(Dispatchers.IO) { TripRecorder.finalizeTrip(ctx, id) } },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Cerrar esta ruta ahora") }
                }
            }
        } else {
            SectionCard("Resumen", Modifier.padding(top = 12.dp)) {
                val price = prefs.pricePerKwh.toDouble()
                if (!t.detailed) {
                    Text(
                        "Detectada por el modo respaldo entre ${Fmt.dateTime(t.startTs)} y ${Fmt.dateTime(t.endTs)}. " +
                            "Puede agrupar varios trayectos; el consumo incluye lo que gastó el coche aparcado en ese tiempo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                MetricRow(
                    "Distancia" to Fmt.km(t.distanceKm),
                    "Duración" to if (t.detailed) Fmt.duration(t.endTs - t.startTs) else "—",
                )
                if (t.detailed) {
                    MetricRow(
                        "Velocidad media" to "${Fmt.dec(t.avgSpeed)} km/h",
                        "Velocidad máxima" to "${t.maxSpeed.toInt()} km/h",
                    )
                }
                MetricRow(
                    "Consumo" to (t.kwhPer100?.let { "${Fmt.dec(it)} kWh/100 km" } ?: "—"),
                    "Energía" to Fmt.kwh(t.energyKwh),
                )
                MetricRow(
                    "Batería" to "${t.startSoc} % → ${t.endSoc} %",
                    "Coste estimado" to Fmt.eur(t.energyKwh.coerceAtLeast(0.0) * price),
                )
                MetricRow(
                    "Temp. exterior" to (t.avgTemp?.let { "${Fmt.dec(it)} °C" } ?: "—"),
                    "Hora" to if (t.detailed) "${Fmt.time(t.startTs)} – ${Fmt.time(t.endTs)}" else "—",
                )
            }
        }

        if (samples.count { it.lat != null } >= 2) {
            SectionCard("Recorrido", Modifier.padding(top = 12.dp)) {
                Text("Trazado aproximado con una posición por lectura.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RouteTrace(samples, Modifier.padding(top = 8.dp))
            }
        }
        val speedPts = samples.filter { it.speedKmh != null }
            .map { ((it.ts - samples.first().ts) / 60_000.0) to it.speedKmh!! }
        if (speedPts.size >= 2) {
            SectionCard("Velocidad", Modifier.padding(top = 12.dp)) {
                XYChart(
                    speedPts,
                    xFmt = { "${it.toInt()} min" },
                    yFmt = { "${it.toInt()}" },
                    connect = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.startLat != null && t.startLon != null && t.endLat != null && t.endLon != null) {
                OutlinedButton(onClick = {
                    // Solo los puntos en marcha: los aparcados repiten origen o destino.
                    MapsLink.open(ctx, samples.filter { it.isDriving }, t.startLat, t.startLon, t.endLat, t.endLon)
                }) { Text("Ver mapa") }
            }
            OutlinedButton(onClick = { editNote = true }) { Text("Nota") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Eliminar") }
        }
    }

    if (editNote && t != null) {
        var text by remember { mutableStateOf(t.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editNote = false },
            title = { Text("Nota de la ruta") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("p. ej. Trabajo, viaje a Madrid…") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { db.updateNote(id, text.ifBlank { null }) }
                    editNote = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar esta ruta?") },
            text = { Text("Se borrarán la ruta y sus lecturas. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { db.deleteTrip(id) }
                    onBack()
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MetricRow(a: Pair<String, String>, b: Pair<String, String>) {
    Row(Modifier.fillMaxWidth()) {
        Metric(a.first, a.second, Modifier.weight(1f))
        Metric(b.first, b.second, Modifier.weight(1f))
    }
}
