package com.misrutas.ev.api

import android.content.Context
import android.net.Uri
import com.misrutas.ev.data.Prefs
import com.misrutas.ev.data.Snapshot
import com.misrutas.ev.data.VehicleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

open class TeslaException(msg: String) : Exception(msg)
class VehicleUnavailable : TeslaException("Coche dormido o sin conexión")

/**
 * Cliente mínimo de la Tesla Fleet API: OAuth (authorization code + refresh token),
 * registro de socio y lectura de vehicle_data. Solo lectura, nunca envía comandos ni despierta el coche.
 */
class TeslaClient private constructor(ctx: Context) {
    private val prefs = Prefs(ctx)
    private val mutex = Mutex()

    companion object {
        const val AUTH_URL = "https://auth.tesla.com/oauth2/v3/authorize"
        const val TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"
        const val SCOPES = "openid offline_access vehicle_device_data vehicle_location"
        private const val MI_TO_KM = 1.609344
        private const val DATA_ENDPOINTS = "charge_state;climate_state;drive_state;location_data;vehicle_state"

        @Volatile private var inst: TeslaClient? = null
        fun get(ctx: Context): TeslaClient =
            inst ?: synchronized(this) { inst ?: TeslaClient(ctx.applicationContext).also { inst = it } }
    }

    fun redirectUri() = "https://${prefs.domain}/callback.html"

    fun authorizeUrl(): String {
        val state = UUID.randomUUID().toString()
        prefs.oauthState = state
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", prefs.clientId)
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("locale", "es-ES")
            .appendQueryParameter("prompt_missing_scopes", "true")
            .build().toString()
    }

    /** Procesa la URL de retorno (misrutasev://auth?code=… o la https://…/callback.html?code=… pegada a mano). */
    suspend fun handleRedirect(uri: Uri) = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let {
            throw TeslaException("Tesla devolvió un error: $it ${uri.getQueryParameter("error_description").orEmpty()}")
        }
        val code = uri.getQueryParameter("code") ?: throw TeslaException("La dirección no contiene el parámetro «code»")
        val state = uri.getQueryParameter("state")
        val expected = prefs.oauthState
        if (state != null && expected != null && state != expected) {
            throw TeslaException("El parámetro «state» no coincide; repite el inicio de sesión")
        }
        val r = Http.call(
            "POST", TOKEN_URL, form = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to prefs.clientId,
                "client_secret" to prefs.clientSecret.orEmpty(),
                "code" to code,
                "audience" to prefs.baseUrl,
                "redirect_uri" to redirectUri(),
                "scope" to SCOPES,
            )
        )
        if (r.code !in 200..299) throw TeslaException("Error al obtener el token (${r.code}): ${r.body.take(300)}")
        saveTokens(JSONObject(r.body))
        prefs.oauthState = null
    }

    /** Registra el dominio de la app como socio en la región elegida (obligatorio una vez antes de leer datos). */
    suspend fun registerPartner(): String = withContext(Dispatchers.IO) {
        val t = Http.call(
            "POST", TOKEN_URL, form = mapOf(
                "grant_type" to "client_credentials",
                "client_id" to prefs.clientId,
                "client_secret" to prefs.clientSecret.orEmpty(),
                "scope" to "openid vehicle_device_data vehicle_location",
                "audience" to prefs.baseUrl,
            )
        )
        if (t.code !in 200..299) throw TeslaException("No se pudo obtener el token de socio (${t.code}): ${t.body.take(300)}")
        val partnerToken = JSONObject(t.body).getString("access_token")
        val r = Http.call(
            "POST", "${prefs.baseUrl}/api/1/partner_accounts", bearer = partnerToken,
            json = JSONObject().put("domain", prefs.domain).toString()
        )
        if (r.code !in 200..299) throw TeslaException("Registro rechazado (${r.code}): ${r.body.take(400)}")
        "Registro correcto en ${prefs.baseUrl.substringAfter("fleet-api.prd.").substringBefore(".vn")}"
    }

    /** Descarga la clave pública publicada en el dominio para comprobar que coincide con la generada. */
    suspend fun fetchPublishedKey(): String = withContext(Dispatchers.IO) {
        val r = Http.call("GET", "https://${prefs.domain}/.well-known/appspecific/com.tesla.3p.public-key.pem")
        if (r.code !in 200..299) throw TeslaException("No se encuentra la clave en tu dominio (HTTP ${r.code})")
        r.body
    }

    fun logout() {
        prefs.accessToken = null
        prefs.refreshToken = null
        prefs.tokenExpiry = 0
    }

    private fun saveTokens(o: JSONObject) {
        prefs.accessToken = o.getString("access_token")
        o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.refreshToken = it }
        prefs.tokenExpiry = System.currentTimeMillis() + o.optLong("expires_in", 28_800) * 1000
    }

    private suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val access = prefs.accessToken
        if (!forceRefresh && access != null && System.currentTimeMillis() < prefs.tokenExpiry - 5 * 60_000) {
            return access
        }
        val refresh = prefs.refreshToken ?: throw TeslaException("No has iniciado sesión con Tesla")
        val r = Http.call(
            "POST", TOKEN_URL, form = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to prefs.clientId,
                "refresh_token" to refresh,
            )
        )
        if (r.code !in 200..299) {
            throw TeslaException("No se pudo renovar la sesión (${r.code}); vuelve a iniciar sesión en Ajustes")
        }
        saveTokens(JSONObject(r.body))
        prefs.accessToken!!
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        var r = Http.call("GET", prefs.baseUrl + path, bearer = token())
        if (r.code == 401) r = Http.call("GET", prefs.baseUrl + path, bearer = token(forceRefresh = true))
        when {
            r.code == 408 -> throw VehicleUnavailable()
            r.code == 412 -> throw TeslaException("Falta el registro de socio en esta región (paso 3 de Ajustes)")
            r.code == 421 -> throw TeslaException("Región incorrecta: cambia Europa/Norteamérica en Ajustes")
            r.code == 429 -> throw TeslaException("Demasiadas peticiones; espera un poco")
            r.code !in 200..299 -> throw TeslaException("Error ${r.code}: ${r.body.take(300)}")
        }
        JSONObject(r.body)
    }

    suspend fun vehicles(): List<VehicleInfo> {
        val arr = get("/api/1/vehicles").optJSONArray("response") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            VehicleInfo(
                vin = v.getString("vin"),
                name = v.optString("display_name").ifBlank { v.getString("vin") },
                state = v.optString("state"),
            )
        }
    }

    suspend fun vehicleData(): Snapshot {
        val vin = prefs.vin ?: throw TeslaException("Elige tu vehículo en Ajustes")
        val o = get("/api/1/vehicles/$vin/vehicle_data?endpoints=" + Uri.encode(DATA_ENDPOINTS))
        return parseSnapshot(o.getJSONObject("response"))
    }

    /**
     * Última lectura que el coche envió a Tesla, sin contactar con él (nunca lo despierta).
     * Puede tener horas de antigüedad y no incluye la posición. Si no hay caché se intenta una lectura normal.
     */
    suspend fun vehicleDataCachedOrLive(): Snapshot {
        val vin = prefs.vin ?: throw TeslaException("Elige tu vehículo en Ajustes")
        return try {
            parseSnapshot(get("/api/1/vehicles/$vin/vehicle_data_cached").getJSONObject("response"))
        } catch (e: TeslaException) {
            if (e is VehicleUnavailable) throw e
            vehicleData()
        }
    }

    private fun parseSnapshot(r: JSONObject): Snapshot {
        val ds = r.optJSONObject("drive_state") ?: JSONObject()
        val cs = r.optJSONObject("charge_state") ?: JSONObject()
        val vs = r.optJSONObject("vehicle_state") ?: JSONObject()
        val cl = r.optJSONObject("climate_state") ?: JSONObject()
        val odo = vs.num("odometer") ?: throw TeslaException("La respuesta no incluye el cuentakilómetros")
        val now = System.currentTimeMillis()
        return Snapshot(
            // Hora real de la lectura en el coche (importa en la caché, que puede ser antigua).
            ts = vs.num("timestamp")?.toLong()?.takeIf { it in 1..now } ?: now,
            odometerKm = odo * MI_TO_KM,
            soc = cs.num("battery_level")?.toInt() ?: 0,
            ratedRangeKm = cs.num("battery_range")?.times(MI_TO_KM),
            speedKmh = ds.num("speed")?.times(MI_TO_KM),
            shift = ds.str("shift_state"),
            lat = ds.num("latitude") ?: ds.num("active_route_latitude"),
            lon = ds.num("longitude") ?: ds.num("active_route_longitude"),
            powerKw = ds.num("power"),
            outsideTemp = cl.num("outside_temp"),
            chargingState = cs.str("charging_state"),
            chargeEnergyAdded = cs.num("charge_energy_added"),
        )
    }

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null
}

internal object Http {
    data class Resp(val code: Int, val body: String)

    fun call(
        method: String,
        url: String,
        bearer: String? = null,
        form: Map<String, String>? = null,
        json: String? = null,
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 40_000
            c.setRequestProperty("Accept", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val payload = when {
                form != null -> {
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
                }
                json != null -> {
                    c.setRequestProperty("Content-Type", "application/json")
                    json
                }
                else -> null
            }
            if (payload != null) {
                c.doOutput = true
                c.outputStream.use { it.write(payload.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Resp(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
