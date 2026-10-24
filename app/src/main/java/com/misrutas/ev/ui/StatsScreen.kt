package com.misrutas.ev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Trip
import com.misrutas.ev.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

enum class Period(val label: String) { D30("30 días"), MONTH("Este mes"), YEAR("Este año"), ALL("Todo") }

private fun Period.includes(d: LocalDate, today: LocalDate) = when (this) {
    Period.D30 -> !d.isBefore(today.minusDays(29))
    Period.MONTH -> YearMonth.from(d) == YearMonth.from(today)
    Period.YEAR -> d.year == today.year
    Period.ALL -> true
}

/** Consumo medio ponderado por distancia (kWh/100 km) de un grupo de rutas. */
private fun List<Trip>.kwhPer100(): Double? {
    val km = sumOf { it.distanceKm }
    return if (km >= 0.5) sumOf { it.energyKwh } / km * 100 else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val price = remember { Prefs(ctx).pricePerKwh.toDouble() }
    val version by Db.version.collectAsState()
    var all by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var period by rememberSaveable { mutableStateOf(Period.D30) }
    LaunchedEffect(version) {
        all = withContext(Dispatchers.IO) { db.trips().filter { !it.recording }.sortedBy { it.startTs } }
    }
    val today = LocalDate.now()
    val trips = all.filter { period.includes(Fmt.localDate(it.startTs), today) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Estadísticas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) })
            }
        }

        if (trips.isEmpty()) {
            Text("No hay rutas en este periodo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val km = trips.sumOf { it.distanceKm }
            val hours = trips.sumOf { it.hours }
            val kwh = trips.sumOf { it.energyKwh }
            SectionCard("Resumen") {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Distancia", Fmt.km(km), Modifier.weight(1f))
                    Metric("Rutas", trips.size.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Tiempo al volante", Fmt.duration((hours * 3_600_000).toLong()), Modifier.weight(1f))
                    Metric("Velocidad media", "${Fmt.dec(if (hours > 0) km / hours else 0.0)} km/h", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Consumo medio", trips.kwhPer100()?.let { "${Fmt.dec(it)} kWh/100 km" } ?: "—", Modifier.weight(1f))
                    Metric("Energía", "${Fmt.dec(kwh)} kWh", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Coste estimado", Fmt.eur(kwh.coerceAtLeast(0.0) * price), Modifier.weight(1f))
                    Metric("Ruta media", Fmt.km(km / trips.size), Modifier.weight(1f))
                }
            }

            val rated = trips.filter { it.distanceKm >= 5 && it.kwhPer100 != null }
            if (rated.isNotEmpty()) {
                SectionCard("Récords (rutas de 5 km o más)") {
                    val longest = trips.maxBy { it.distanceKm }
                    val best = rated.minBy { it.kwhPer100!! }
                    val worst = rated.maxBy { it.kwhPer100!! }
                    Record("Más larga", "${Fmt.km(longest.distanceKm)} · ${Fmt.dateTime(longest.startTs)}")
                    Record("Más eficiente", "${Fmt.dec(best.kwhPer100!!)} kWh/100 km · ${Fmt.dateTime(best.startTs)}")
                    Record("Menos eficiente", "${Fmt.dec(worst.kwhPer100!!)} kWh/100 km · ${Fmt.dateTime(worst.startTs)}")
                }
            }

            val perTrip = trips.filter { it.distanceKm >= 1 && it.kwhPer100 != null }
            if (perTrip.size >= 2) {
                SectionCard("Consumo de cada ruta (kWh/100 km)") {
                    val t0 = perTrip.first().startTs
                    XYChart(
                        perTrip.map { (it.startTs - t0) / 86_400_000.0 to it.kwhPer100!! },
                        xFmt = { Fmt.dateTime(t0 + (it * 86_400_000).toLong()).substringBefore(" ·") },
                        yFmt = { Fmt.dec(it, 0) },
                        trend = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            val withTemp = perTrip.filter { it.avgTemp != null }
            if (withTemp.size >= 3) {
                SectionCard("Consumo según temperatura exterior") {
                    XYChart(
                        withTemp.map { it.avgTemp!! to it.kwhPer100!! },
                        xFmt = { "${Fmt.dec(it, 0)} °C" },
                        yFmt = { Fmt.dec(it, 0) },
                        trend = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            val bands = listOf(0.0 to 30.0, 30.0 to 60.0, 60.0 to 90.0, 90.0 to 110.0, 110.0 to 999.0)
            val bandValues = bands.map { (lo, hi) -> trips.filter { it.avgSpeed >= lo && it.avgSpeed < hi }.kwhPer100() ?: 0.0 }
            if (bandValues.count { it > 0 } >= 2) {
                SectionCard("Consumo según velocidad media (kWh/100 km)") {
                    BarChart(
                        labels = listOf("<30", "30-60", "60-90", "90-110", ">110"),
                        values = bandValues,
                        valueFmt = { Fmt.dec(it) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // Evolución mensual: siempre los últimos 12 meses, independientemente del filtro.
        if (all.isNotEmpty()) {
            val months = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
            val byMonth = all.groupBy { YearMonth.from(Fmt.localDate(it.startTs)) }
            SectionCard("Kilómetros por mes") {
                BarChart(
                    labels = months.map { Fmt.month(it.atDay(1)) },
                    values = months.map { m -> byMonth[m]?.sumOf { it.distanceKm } ?: 0.0 },
                    valueFmt = { Fmt.dec(it, 0) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            SectionCard("Consumo medio por mes (kWh/100 km)") {
                BarChart(
                    labels = months.map { Fmt.month(it.atDay(1)) },
                    values = months.map { m -> byMonth[m]?.kwhPer100() ?: 0.0 },
                    valueFmt = { Fmt.dec(it, 0) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Record(label: String, value: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
