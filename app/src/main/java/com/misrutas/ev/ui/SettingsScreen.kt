package com.misrutas.ev.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.misrutas.ev.api.KeyGen
import com.misrutas.ev.api.TeslaClient
import com.misrutas.ev.data.Db
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.VehicleInfo
import com.misrutas.ev.trip.BatteryLog
import com.misrutas.ev.util.Csv
import com.misrutas.ev.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(pendingAuth: Uri?, onAuthHandled: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val client = remember { TeslaClient.get(ctx) }
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var region by remember { mutableStateOf(prefs.region) }
    var clientId by remember { mutableStateOf(prefs.clientId) }
    var clientSecret by remember { mutableStateOf(prefs.clientSecret.orEmpty()) }
    var domain by remember { mutableStateOf(prefs.domain) }
    var pastedUrl by remember { mutableStateOf("") }
    var vehicles by remember { mutableStateOf<List<VehicleInfo>>(emptyList()) }
    var battery by remember { mutableStateOf(Fmt.dec(prefs.batteryKwh.toDouble())) }
    var price by remember { mutableStateOf(Fmt.dec(prefs.pricePerKwh.toDouble(), 3)) }
    var poll by remember { mutableStateOf(prefs.pollSeconds.toString()) }
    var fullRange by remember {
        mutableStateOf(prefs.newFullRangeKm.takeIf { it > 0 }?.let { Fmt.dec(it.toDouble(), 0) }.orEmpty())
    }
    var showDevices by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    fun launchTask(action: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message = try {
                action()
            } catch (e: Exception) {
                "⚠ ${e.message}"
            }
            busy = false
            refresh++
        }
    }

    fun saveApp() {
        prefs.region = region
        prefs.clientId = clientId
        prefs.clientSecret = clientSecret
        prefs.domain = domain
        domain = prefs.domain
    }

    suspend fun loadVehicles(): String {
        val vs = client.vehicles()
        vehicles = vs
        if (vs.size == 1) {
            prefs.vin = vs[0].vin
            prefs.vehicleName = vs[0].name
        }
        return if (vs.isEmpty()) "La cuenta no tiene vehículos" else "Vehículos cargados: ${vs.size}"
    }

    LaunchedEffect(pendingAuth) {
        val uri = pendingAuth ?: return@LaunchedEffect
        onAuthHandled()
        launchTask {
            client.handleRedirect(uri)
            "Sesión iniciada ✓  " + loadVehicles()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) launchTask { "Exportadas ${withContext(Dispatchers.IO) { Csv.exportTrips(ctx, uri) }} rutas" }
    }

    // Al volver de los ajustes del sistema se releen permisos y restricción de batería.
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val btGranted = refresh.let { ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED }
    val notifGranted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val batteryFree = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (busy) CircularProgressIndicator()
        message?.let {
            Text(it, color = if (it.startsWith("⚠")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }

        SectionCard("1 · App de desarrollador de Tesla") {
            Hint("Crea la app en developer.tesla.com y copia aquí sus datos. La guía del proyecto explica cada campo.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = region == "eu", onClick = { region = "eu" })
                Text("Europa", Modifier.clickable { region = "eu" })
                RadioButton(selected = region == "na", onClick = { region = "na" })
                Text("Norteamérica / Asia-Pacífico", Modifier.clickable { region = "na" })
            }
            Field("Client ID", clientId) { clientId = it }
            OutlinedTextField(
                clientSecret, { clientSecret = it }, label = { Text("Client Secret") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Field("Dominio (p. ej. usuario.github.io)", domain) { domain = it }
            Button(onClick = { saveApp(); message = "Datos guardados" }, Modifier.padding(top = 8.dp)) { Text("Guardar") }
            if (prefs.domain.isNotBlank()) {
                Hint("En developer.tesla.com usa:\n• Allowed Origin: https://${prefs.domain}\n• Allowed Redirect URI: ${client.redirectUri()}")
            }
        }

        SectionCard("2 · Clave pública") {
            Hint("Tesla exige publicar una clave pública en https://<dominio>/.well-known/appspecific/com.tesla.3p.public-key.pem")
            val pem = prefs.publicKeyPem
            if (pem != null) {
                SelectionContainer {
                    Text(pem, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(ctx, pem); message = "Clave copiada" }) { Text("Copiar") }
                    OutlinedButton(onClick = { share(ctx, pem) }) { Text("Compartir") }
                    OutlinedButton(onClick = {
                        launchTask {
                            val published = client.fetchPublishedKey()
                            if (KeyGen.samePem(published, pem)) "✓ La clave publicada coincide" else "⚠ La clave publicada no coincide con esta"
                        }
                    }) { Text("Comprobar") }
                }
            } else {
                Button(onClick = {
                    val (pub, priv) = KeyGen.generate()
                    prefs.publicKeyPem = pub
                    prefs.privateKeyPem = priv
                    refresh++
                }, Modifier.padding(top = 8.dp)) { Text("Generar clave") }
            }
        }

        SectionCard("3 · Registro en la región") {
            Hint("Se hace una sola vez, después de publicar la clave. Registra tu dominio en la Fleet API de la región elegida.")
            Button(
                onClick = { saveApp(); launchTask { client.registerPartner() } },
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && domain.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Registrar") }
        }

        SectionCard("4 · Iniciar sesión con tu cuenta Tesla") {
            Text(
                if (prefs.isLoggedIn) "Sesión iniciada ✓" else "Sin sesión",
                color = if (prefs.isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Hint("Concede los permisos «Información del vehículo» y «Ubicación del vehículo». Tus credenciales se introducen solo en la web de Tesla.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = {
                        saveApp()
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(client.authorizeUrl())))
                    },
                    enabled = clientId.isNotBlank() && domain.isNotBlank(),
                ) { Text(if (prefs.isLoggedIn) "Volver a iniciar" else "Iniciar sesión") }
                if (prefs.isLoggedIn) {
                    OutlinedButton(onClick = { client.logout(); refresh++ }) { Text("Cerrar sesión") }
                }
            }
            Hint("Si al volver no se abre la app, copia la dirección completa de la página de retorno y pégala aquí:")
            Field("URL de retorno o código", pastedUrl) { pastedUrl = it }
            OutlinedButton(
                onClick = {
                    val text = pastedUrl.trim()
                    val uri = if (text.contains("code=")) Uri.parse(text) else Uri.parse("misrutasev://auth?code=" + Uri.encode(text))
                    pastedUrl = ""
                    launchTask { client.handleRedirect(uri); "Sesión iniciada ✓  " + loadVehicles() }
                },
                enabled = pastedUrl.isNotBlank(),
            ) { Text("Usar") }
        }

        SectionCard("5 · Vehículo") {
            Text(prefs.vehicleName?.let { "$it · ${prefs.vin}" } ?: "Ningún vehículo elegido")
            vehicles.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                    prefs.vin = v.vin; prefs.vehicleName = v.name; refresh++
                }) {
                    RadioButton(selected = prefs.vin == v.vin, onClick = { prefs.vin = v.vin; prefs.vehicleName = v.name; refresh++ })
                    Text("${v.name} (${v.state})")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { launchTask { loadVehicles() } }, enabled = prefs.isLoggedIn) { Text("Cargar vehículos") }
                OutlinedButton(onClick = {
                    launchTask {
                        val s = client.vehicleData()
                        // Una lectura manual justo después de cargar al 100 % también alimenta la estimación de batería.
                        withContext(Dispatchers.IO) { BatteryLog(ctx).observe(s) }
                        "Lectura OK · ${Fmt.km(s.odometerKm)} · ${s.soc} % · autonomía ${s.ratedRangeKm?.let { Fmt.km(it) } ?: "—"}" +
                            " · marcha ${s.shift ?: "P"} · ${s.outsideTemp?.let { "${Fmt.dec(it)} °C" } ?: ""}"
                    }
                }, enabled = prefs.isConfigured) { Text("Probar lectura") }
            }
            Hint("Si el coche duerme, la lectura falla (no se despierta para no gastar). Pruébalo con el coche despierto.")
        }

        SectionCard("6 · Detección automática por Bluetooth") {
            Hint("Cuando el móvil se conecta al Bluetooth del coche se empieza a registrar; al desconectarse se cierra la ruta.")
            if (!btGranted || !notifGranted) {
                Button(onClick = {
                    permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                }, Modifier.padding(top = 8.dp)) { Text("Conceder permisos de Bluetooth y notificaciones") }
            }
            Text("Coche: ${prefs.btName ?: "sin elegir"}", Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { showDevices = true }, enabled = btGranted) { Text("Elegir dispositivo Bluetooth") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registro automático", Modifier.weight(1f))
                Switch(checked = prefs.autoMode, onCheckedChange = { prefs.autoMode = it; refresh++ })
            }
            Text(
                if (batteryFree) "Batería: sin restricciones ✓" else "Batería: restringida (necesario quitar la restricción)",
                color = if (batteryFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!batteryFree) {
                @SuppressLint("BatteryLife")
                val req = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                OutlinedButton(onClick = { ctx.startActivity(req) }) { Text("Quitar restricción de batería") }
            }
            Hint("En MagicOS abre también Ajustes › Batería › Inicio de aplicaciones, desactiva «Gestionar automáticamente» para esta app y activa inicio automático, inicio secundario y ejecución en segundo plano.")
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Abrir información de la app") }
        }

        SectionCard("7 · Cálculos") {
            Hint("Capacidad útil de fábrica: Propulsión trasera LFP 60 kWh brutos ≈ 57,5 kWh útiles; Gran Autonomía/Performance ≈ 75 kWh.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { battery = "57,5" }) { Text("LFP 57,5 kWh") }
                OutlinedButton(onClick = { battery = "75,0" }) { Text("75 kWh") }
            }
            NumberField("Capacidad útil de fábrica (kWh)", battery) { battery = it }
            NumberField("Autonomía al 100 % de fábrica (km, vacío = automática)", fullRange) { fullRange = it }
            Hint("Si la dejas vacía se usa la mayor autonomía al 100 % que haya leído la app (válido para un coche casi nuevo).")
            NumberField("Precio de la electricidad (€/kWh)", price) { price = it }
            NumberField("Intervalo entre lecturas al conducir (s)", poll) { poll = it }
            Hint("Cada lectura cuesta 0,002 $ en la Fleet API (Tesla regala 10 $/mes). A 60 s, una hora diaria de conducción son unos 3,6 $/mes.")
            Button(onClick = {
                battery.toFloatOrNullEs()?.let { prefs.batteryKwh = it }
                prefs.newFullRangeKm = fullRange.toFloatOrNullEs() ?: 0f
                price.toFloatOrNullEs()?.let { prefs.pricePerKwh = it }
                poll.toIntOrNull()?.let { prefs.pollSeconds = it }
                poll = prefs.pollSeconds.toString()
                message = "Parámetros guardados"
            }, Modifier.padding(top = 8.dp)) { Text("Guardar parámetros") }
        }

        SectionCard("Datos") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { csvLauncher.launch("rutas.csv") }) { Text("Exportar CSV") }
                OutlinedButton(onClick = { confirmWipe = true }) { Text("Borrar todas las rutas") }
            }
        }
    }

    if (showDevices) {
        val devices = remember {
            runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList() }
                .getOrNull().orEmpty().sortedBy { it.name ?: it.address }
        }
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("Bluetooth del coche") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (devices.isEmpty()) Text("No hay dispositivos emparejados. Empareja antes el móvil con el coche.")
                    devices.forEach { d ->
                        Text(
                            d.name ?: d.address,
                            Modifier.fillMaxWidth().clickable {
                                prefs.btAddress = d.address
                                prefs.btName = d.name ?: d.address
                                showDevices = false
                                refresh++
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Cerrar") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("¿Borrar todas las rutas?") },
            text = { Text("Se eliminará todo el historial de este móvil. Exporta antes un CSV si quieres conservarlo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    launchTask { withContext(Dispatchers.IO) { Db.get(ctx).deleteAll() }; "Historial borrado" }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.toFloatOrNullEs() = trim().replace(',', '.').toFloatOrNull()

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("clave", text))
    Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Compartir clave pública"))
}
