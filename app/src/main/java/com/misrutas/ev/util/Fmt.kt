package com.misrutas.ev.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    val locale: Locale = Locale.forLanguageTag("es-ES")
    private val zone get() = ZoneId.systemDefault()
    private val timeF = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayF = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", locale)
    private val dateTimeF = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale)
    private val monthF = DateTimeFormatter.ofPattern("MMM", locale)

    fun dec(v: Double, digits: Int = 1) = String.format(locale, "%.${digits}f", v)
    fun km(v: Double) = "${dec(v)} km"
    fun kwh(v: Double) = "${dec(v, 2)} kWh"
    fun eur(v: Double) = "${dec(v, 2)} €"

    fun time(ts: Long): String = timeF.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun dateTime(ts: Long): String = dateTimeF.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun localDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    fun day(d: LocalDate): String = dayF.format(d).replaceFirstChar { it.titlecase(locale) }
    fun month(d: LocalDate): String = monthF.format(d).trimEnd('.')

    fun duration(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}
