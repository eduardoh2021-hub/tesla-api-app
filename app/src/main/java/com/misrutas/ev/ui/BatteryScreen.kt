package com.misrutas.ev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.misrutas.ev.data.BatteryObs
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.trip.BatteryHealth
import com.misrutas.ev.trip.BatteryLog
import com.misrutas.ev.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val prefs = remember { Prefs(ctx) }
    val version by Db.version.collectAsState()
    var obs by remember { mutableStateOf<List<BatteryObs>>(emptyList()) }
    LaunchedEffect(version) { obs = withContext(Dispatchers.IO) { db.batteryObs() } }

    val est = BatteryHealth.estimate(prefs, obs)
    val ranges = obs.filter { it.kind == BatteryObs.KIND_RANGE && it.fullRangeKm != null }
    val charges = obs.filter { it.kind == BatteryObs.KIND_CHARGE && it.capacityKwh != null }
    val capNew = prefs.batteryKwh.toDouble()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Batería", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Capacidad útil de fábrica: ${Fmt.dec(capNew)} kWh (cámbiala en Ajustes › Cálculos)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (est == null) {
            SectionCard("Aún no hay datos suficientes") {
                Text(
                    "La estimación se crea cada vez que el coche se lee con la batería al ${BatteryLog.MIN_SOC_FOR_RANGE} % o más. " +
                        "Lo ideal es cargar al 100 % y salir poco después: con una LFP Tesla recomienda cargar al 100 % " +
                        "al menos una vez por semana, y eso además mantiene calibrado el BMS.",
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            SectionCard("Salud estimada") {
                Text(
                    "${Fmt.dec(est.healthPct)} %",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                LinearProgressIndicator(
                    progress = { (est.healthPct / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(vertical = 2.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Metric("Degradación", "${Fmt.dec((100 - est.healthPct).coerceAtLeast(0.0))} %", Modifier.weight(1f))
                    Metric("Capacidad útil hoy", "${Fmt.dec(est.capacityNowKwh)} kWh", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Autonomía al 100 % hoy", Fmt.km(est.currentFullRangeKm), Modifier.weight(1f))
                    Metric(
                        if (est.baselineIsManual) "De fábrica (manual)" else "Referencia (máx. vista)",
                        Fmt.km(est.baselineFullRangeKm),
                        Modifier.weight(1f),
                    )
                }
                est.trendPer10kKm?.let {
                    Text(
                        "Tendencia: ${if (it >= 0) "+" else ""}${Fmt.dec(it, 2)} puntos de salud cada 10.000 km",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            SectionCard("Según las cargas") {
                val cap = est.chargeCapacityKwh
                if (cap == null) {
                    Text(
                        "Todavía no se ha medido ninguna carga. Se calcula al detectar una carga de al menos " +
                            "${BatteryLog.MIN_SOC_GAIN} puntos: kWh añadidos ÷ % ganado.",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Metric("Capacidad (mediana)", "${Fmt.dec(cap)} kWh", Modifier.weight(1f))
                        Metric("Frente a fábrica", "${Fmt.dec(cap / capNew * 100)} %", Modifier.weight(1f))
                    }
                    Text(
                        "${est.chargeCount} cargas medidas. Incluye algo de pérdidas de carga, así que suele salir un poco por encima de la real.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (ranges.size >= 2) {
            val base = est?.baselineFullRangeKm ?: ranges.maxOf { it.fullRangeKm!! }
            SectionCard("Salud según kilómetros (%)") {
                XYChart(
                    ranges.map { it.odometerKm to it.fullRangeKm!! / base * 100 },
                    xFmt = { "${Fmt.dec(it / 1000, 0)} mil km" },
                    yFmt = { Fmt.dec(it, 0) },
                    trend = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (charges.size >= 2) {
            val t0 = charges.first().ts
            SectionCard("Capacidad medida en cada carga (kWh)") {
                XYChart(
                    charges.map { (it.ts - t0) / 86_400_000.0 to it.capacityKwh!! },
                    xFmt = { Fmt.dateTime(t0 + (it * 86_400_000).toLong()).substringBefore(" ·") },
                    yFmt = { Fmt.dec(it, 0) },
                    trend = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (obs.isNotEmpty()) {
            SectionCard("Últimas observaciones") {
                obs.takeLast(10).reversed().forEach { o ->
                    val text = if (o.kind == BatteryObs.KIND_CHARGE) {
                        "Carga: +${Fmt.dec(o.energyAddedKwh ?: 0.0)} kWh hasta ${o.soc} % → ${Fmt.dec(o.capacityKwh ?: 0.0)} kWh"
                    } else {
                        "Lectura al ${o.soc} %: ${Fmt.km(o.fullRangeKm ?: 0.0)} al 100 %"
                    }
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Fmt.dateTime(o.ts)} · ${Fmt.km(o.odometerKm)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("Cómo se calcula") {
            Text(
                "• Salud = autonomía al 100 % que calcula hoy el BMS ÷ autonomía al 100 % con la batería nueva. " +
                    "Como el coche es casi nuevo, la referencia es la mayor autonomía vista; puedes fijar la de fábrica en Ajustes.\n" +
                    "• Capacidad útil hoy = capacidad de fábrica × salud.\n" +
                    "• Las cargas dan una segunda estimación independiente con los kWh que entran.\n" +
                    "• La cifra interna exacta del BMS solo está disponible en el modo servicio o por Fleet Telemetry, no en la API de lectura.\n" +
                    "• En LFP el % que muestra el coche se recalibra al cargar al 100 %: las lecturas justo después son las más fiables.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
