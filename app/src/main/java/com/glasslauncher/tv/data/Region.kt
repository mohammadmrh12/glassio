package com.glasslauncher.tv.data

import java.util.Locale

/**
 * The country a title's streaming offers are read for: what is on Netflix or Prime Video differs from country to
 * country. Settings → Library → Country; "Automatic" is the country the TV is set to, and the US when it has none.
 */
object Region {
    /** The ones offered in Settings (any two-letter code set another way is used as it is). */
    val CHOICES = listOf("", "US", "GB", "CA", "AU", "DE", "FR", "ES", "IT", "NL", "SE", "PL", "TR", "BR", "MX", "IN", "JP", "KR",
        "SA", "AE", "IQ", "EG", "KW", "QA", "JO", "MA")

    fun valid(code: String) = code.length == 2 && code.all { it in 'A'..'Z' }

    /** [chosen] if it is a country code, else [device] (the TV's), else the US. */
    fun pick(chosen: String, device: String): String {
        val c = chosen.trim().uppercase(Locale.ROOT)
        if (valid(c)) return c
        val d = device.trim().uppercase(Locale.ROOT)
        return if (valid(d)) d else "US"
    }

    fun of(s: Settings): String = pick(s.watchCountry, Locale.getDefault().country)

    fun name(code: String): String = if (!valid(code)) "Automatic" else Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

    /** What the Settings row shows. */
    fun label(s: Settings): String = if (valid(s.watchCountry)) name(s.watchCountry) else "Automatic · " + name(of(s))
}
