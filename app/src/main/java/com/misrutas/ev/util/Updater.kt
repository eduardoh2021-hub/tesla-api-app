package com.misrutas.ev.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.misrutas.ev.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Actualiza la app con el APK de la última Release del repositorio de GitHub. */
object Updater {
    data class Release(val tag: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            when (c.responseCode) {
                404 -> return@withContext null
                in 200..299 -> Unit
                else -> throw IllegalStateException("GitHub respondió ${c.responseCode}")
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
            Release(
                tag = o.getString("tag_name"),
                apkUrl = apk.getString("browser_download_url"),
                sizeBytes = apk.optLong("size"),
                notes = o.optString("body"),
            )
        } finally {
            c.disconnect()
        }
    }

    /** true si la etiqueta de la Release (p. ej. «v1.2») es posterior a la versión instalada. */
    fun isNewer(tag: String, current: String = currentVersion): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Descarga el APK y abre el instalador del sistema, que pide confirmación al usuario. */
    suspend fun downloadAndInstall(ctx: Context, release: Release, onProgress: (Float) -> Unit) {
        val file = File(ctx.cacheDir, "actualizacion.apk")
        withContext(Dispatchers.IO) {
            val c = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                if (c.responseCode !in 200..299) throw IllegalStateException("Descarga fallida (${c.responseCode})")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }

            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("app.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pi.intentSender)
            }
        }
    }
}

/** Recibe el resultado del instalador: abre la pantalla de confirmación o muestra el error. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(ctx, "No se pudo actualizar: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
