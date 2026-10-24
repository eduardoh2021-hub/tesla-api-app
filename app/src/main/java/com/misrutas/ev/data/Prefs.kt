package com.misrutas.ev.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes de la app. Los secretos (client secret, tokens, clave privada) se cifran con el Android Keystore. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var region: String
        get() = sp.getString("region", "eu")!!
        set(v) = sp.edit { putString("region", v) }

    val baseUrl: String
        get() = if (region == "na") BASE_NA else BASE_EU

    var clientId: String
        get() = sp.getString("client_id", "")!!
        set(v) = sp.edit { putString("client_id", v.trim()) }

    var clientSecret: String?
        get() = secret("client_secret")
        set(v) = setSecret("client_secret", v?.trim())

    /** Dominio donde se publica la clave pública y callback.html, p. ej. "usuario.github.io". */
    var domain: String
        get() = sp.getString("domain", "")!!
        set(v) = sp.edit {
            putString("domain", v.trim().removePrefix("https://").removePrefix("http://").trimEnd('/'))
        }

    var accessToken: String?
        get() = secret("access_token")
        set(v) = setSecret("access_token", v)

    var refreshToken: String?
        get() = secret("refresh_token")
        set(v) = setSecret("refresh_token", v)

    var tokenExpiry: Long
        get() = sp.getLong("token_expiry", 0)
        set(v) = sp.edit { putLong("token_expiry", v) }

    var oauthState: String?
        get() = sp.getString("oauth_state", null)
        set(v) = sp.edit { putString("oauth_state", v) }

    var publicKeyPem: String?
        get() = sp.getString("public_key", null)
        set(v) = sp.edit { putString("public_key", v) }

    var privateKeyPem: String?
        get() = secret("private_key")
        set(v) = setSecret("private_key", v)

    var vin: String?
        get() = sp.getString("vin", null)
        set(v) = sp.edit { putString("vin", v) }

    var vehicleName: String?
        get() = sp.getString("vehicle_name", null)
        set(v) = sp.edit { putString("vehicle_name", v) }

    var btAddress: String?
        get() = sp.getString("bt_address", null)
        set(v) = sp.edit { putString("bt_address", v) }

    var btName: String?
        get() = sp.getString("bt_name", null)
        set(v) = sp.edit { putString("bt_name", v) }

    var autoMode: Boolean
        get() = sp.getBoolean("auto_mode", true)
        set(v) = sp.edit { putBoolean("auto_mode", v) }

    /** Segundos entre lecturas mientras conduces. */
    var pollSeconds: Int
        get() = sp.getInt("poll_seconds", 60)
        set(v) = sp.edit { putInt("poll_seconds", v.coerceIn(20, 600)) }

    /** Capacidad útil de fábrica de la batería en kWh (LFP 60 kWh brutos ≈ 57,5 útiles). */
    var batteryKwh: Float
        get() = sp.getFloat("battery_kwh", 57.5f)
        set(v) = sp.edit { putFloat("battery_kwh", v) }

    /** Autonomía homologada al 100 % con la batería nueva; 0 = se toma la mayor observada. */
    var newFullRangeKm: Float
        get() = sp.getFloat("new_full_range_km", 0f)
        set(v) = sp.edit { putFloat("new_full_range_km", v.coerceAtLeast(0f)) }

    /** Última lectura vista del coche, para detectar cargas entre una sesión y la siguiente. */
    var lastSeenSoc: Int
        get() = sp.getInt("last_seen_soc", -1)
        set(v) = sp.edit { putInt("last_seen_soc", v) }

    var lastSeenEnergyAdded: Float
        get() = sp.getFloat("last_seen_energy_added", -1f)
        set(v) = sp.edit { putFloat("last_seen_energy_added", v) }

    /** Última lectura completa conocida del coche (base del modo respaldo para detectar km sin registrar). */
    var lastSeen: Snapshot?
        get() = sp.getString("last_seen_snapshot", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                Snapshot(
                    ts = o.getLong("ts"),
                    odometerKm = o.getDouble("odo"),
                    soc = o.getInt("soc"),
                    ratedRangeKm = o.optNum("range"),
                    speedKmh = null,
                    shift = null,
                    lat = o.optNum("lat"),
                    lon = o.optNum("lon"),
                    powerKw = null,
                    outsideTemp = o.optNum("temp"),
                    chargingState = null,
                    chargeEnergyAdded = o.optNum("added"),
                )
            }.getOrNull()
        }
        set(v) = sp.edit {
            if (v == null) {
                remove("last_seen_snapshot")
            } else {
                val o = JSONObject()
                    .put("ts", v.ts).put("odo", v.odometerKm).put("soc", v.soc)
                    .put("range", v.ratedRangeKm).put("lat", v.lat).put("lon", v.lon)
                    .put("temp", v.outsideTemp).put("added", v.chargeEnergyAdded)
                putString("last_seen_snapshot", o.toString())
            }
        }

    /** Momento de la última conexión Bluetooth al coche (o sesión de registro). */
    var lastConnectTs: Long
        get() = sp.getLong("last_connect_ts", 0)
        set(v) = sp.edit { putLong("last_connect_ts", v) }

    var backupMode: Boolean
        get() = sp.getBoolean("backup_mode", true)
        set(v) = sp.edit { putBoolean("backup_mode", v) }

    var lastBackupCheckTs: Long
        get() = sp.getLong("last_backup_check_ts", 0)
        set(v) = sp.edit { putLong("last_backup_check_ts", v) }

    var lastBackupResult: String?
        get() = sp.getString("last_backup_result", null)
        set(v) = sp.edit { putString("last_backup_result", v) }

    /** Precio de la electricidad en €/kWh, para estimar el coste de cada ruta. */
    var pricePerKwh: Float
        get() = sp.getFloat("price_kwh", 0.15f)
        set(v) = sp.edit { putFloat("price_kwh", v) }

    val isLoggedIn get() = refreshToken != null
    val isConfigured get() = isLoggedIn && vin != null

    private fun JSONObject.optNum(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

    private fun secret(key: String): String? = sp.getString(key, null)?.let { SecretBox.decrypt(it) }

    private fun setSecret(key: String, value: String?) = sp.edit {
        if (value.isNullOrEmpty()) remove(key) else putString(key, SecretBox.encrypt(value))
    }

    companion object {
        const val BASE_EU = "https://fleet-api.prd.eu.vn.cloud.tesla.com"
        const val BASE_NA = "https://fleet-api.prd.na.vn.cloud.tesla.com"
    }
}

private object SecretBox {
    private const val ALIAS = "misrutas_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(stored: String): String? = runCatching {
        val (iv, ct) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
