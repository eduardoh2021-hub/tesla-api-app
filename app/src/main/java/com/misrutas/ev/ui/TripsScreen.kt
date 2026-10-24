package com.misrutas.ev.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Trip
import com.misrutas.ev.trip.TripService
import com.misrutas.ev.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TripsScreen(onOpen: (Long) -> Unit, onGoSettings: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val version by Db.version.collectAsState()
    var trips by remember { mutableStateOf<List<Trip>?>(null) }
    LaunchedEffect(version) { trips = withContext(Dispatchers.IO) { db.trips() } }
    val running by TripService.running.collectAsState()
    val status by TripService.status.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Mis rutas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            prefs.vehicleName?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!prefs.isConfigured) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Falta conectar con tu Tesla", style = MaterialTheme.typography.titleMedium)
                        Text("Sigue los pasos de Ajustes para enlazar la app con la Fleet API de Tesla.")
                        Button(onClick = onGoSettings, modifier = Modifier.padding(top = 8.dp)) { Text("Ir a Ajustes") }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (running) "Registro activo" else "Registro en espera",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            running -> status.ifBlank { "Conectando con el coche…" }
                            prefs.btAddress != null && prefs.autoMode ->
                                "Se activará al conectar el móvil al Bluetooth de «${prefs.btName ?: "tu coche"}»."
                            else -> "Configura el Bluetooth del coche en Ajustes o inicia el registro a mano."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        if (running) {
                            OutlinedButton(onClick = { TripService.send(ctx, TripService.ACTION_STOP) }) { Text("Detener") }
                        } else {
                            Button(
                                onClick = { TripService.send(ctx, TripService.ACTION_MANUAL) },
                                enabled = prefs.isConfigured,
                            ) { Text("Iniciar registro manual") }
                        }
                    }
                }
            }
        }
        val list = trips
        if (list != null && list.isEmpty()) {
            item {
                Text(
                    "Todavía no hay rutas. Aparecerán aquí al terminar cada trayecto.",
                    modifier = Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        list?.groupBy { Fmt.localDate(it.startTs) }?.forEach { (day, dayTrips) ->
            item(key = "d$day") {
                val km = dayTrips.sumOf { it.distanceKm }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.day(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(Fmt.km(km), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(dayTrips, key = { it.id }) { t -> TripRow(t, prefs.pricePerKwh.toDouble()) { onOpen(t.id) } }
        }
    }
}

@Composable
private fun TripRow(t: Trip, price: Double, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Fmt.time(t.startTs)} – ${if (t.recording) "en curso" else Fmt.time(t.endTs)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                t.kwhPer100?.let {
                    Text("${Fmt.dec(it)} kWh/100 km", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            val route = listOfNotNull(t.startAddr, t.endAddr).joinToString("  →  ")
            if (route.isNotBlank()) {
                Text(route, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (!t.note.isNullOrBlank()) {
                Text(t.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!t.recording) {
                Row(Modifier.padding(top = 6.dp)) {
                    Chip(Fmt.km(t.distanceKm))
                    Chip("${t.avgSpeed.toInt()} km/h")
                    Chip(Fmt.duration(t.endTs - t.startTs))
                    Chip(Fmt.eur(t.energyKwh.coerceAtLeast(0.0) * price))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(14.dp))
}
