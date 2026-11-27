package com.glasslauncher.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.glasslauncher.tv.GlassApp

/**
 * A profile's PIN (four digits, as in Nuvio).
 *
 * - **Whose PIN.** A profile that has a PIN in Nuvio uses that one: it is checked by Nuvio itself
 *   (`verify_profile_pin`, the call Nuvio's own TV app makes), never kept here in the clear. A profile without one can
 *   be given a PIN in Glassio (Settings → Accounts & profiles → *PIN*), kept on this TV only. Nothing is written to Nuvio.
 * - **When it is asked** ([asks]), per profile and per TV: *Every time* (the TV starting, waking after a long sleep,
 *   changing to the profile) or *Automatic* (this TV goes in without it). Changing that, or the PIN, needs the PIN.
 * - **Without Nuvio** (no connection): the PIN Nuvio last accepted on this TV still opens the profile.
 * - Kept in `profile_pins`, salted and hashed. Not a "glass…" file on purpose: never in a backup, a sync between TVs
 *   or a problem report.
 */
object ProfilePin {
    const val LENGTH = 4
    private const val STORE = "profile_pins"
    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    sealed class Result {
        object Ok : Result()
        object Wrong : Result()
        /** Too many wrong tries: again in [seconds]. */
        data class Wait(val seconds: Int) : Result()
        /** Nuvio's PIN, Nuvio can't be reached, and it was never entered on this TV before. */
        object Offline : Result()
    }

    /** QA (dev key, `--es pin_fake "2:1234"`): profiles taken to have that PIN in Nuvio, answered here; "off" in place of the PIN: Nuvio unreachable. */
    @Volatile var fake: Map<Int, String> = emptyMap()

    /** [id] has a PIN in Nuvio (as last read from the account, while signed in). */
    fun inNuvio(ctx: Context, id: Int): Boolean {
        if (id in fake) return true
        val app = ctx.applicationContext as? GlassApp ?: return false
        return app.library.nuvio.connected && Profiles.known(ctx).firstOrNull { it.id == id }?.locked == true
    }
    /** [id] has a PIN made in Glassio (it counts only while Nuvio has none for it). */
    fun own(ctx: Context, id: Int): Boolean = sp(ctx).contains("own$id")
    fun has(ctx: Context, id: Int): Boolean = inNuvio(ctx, id) || own(ctx, id)
    /** "nuvio", "glassio" or "" (no PIN). */
    fun source(ctx: Context, id: Int): String = if (inNuvio(ctx, id)) "nuvio" else if (own(ctx, id)) "glassio" else ""

    /** This TV goes into [id] without its PIN (*Automatic*). */
    fun auto(ctx: Context, id: Int): Boolean = sp(ctx).getBoolean("auto$id", false)
    fun setAuto(ctx: Context, id: Int, on: Boolean) { sp(ctx).edit().putBoolean("auto$id", on).apply() }
    /** The PIN is asked before [id] opens. */
    fun asks(ctx: Context, id: Int): Boolean = has(ctx, id) && !auto(ctx, id)

    /** The PIN is being asked at a start or a wake and wasn't given yet: a restart of Glassio asks again. */
    fun gate(ctx: Context): Boolean = sp(ctx).getBoolean("gate", false)
    fun setGate(ctx: Context, on: Boolean) { if (gate(ctx) != on) sp(ctx).edit().putBoolean("gate", on).commit() }

    /** Blocking. Is [pin] the PIN of [id]? */
    fun check(ctx: Context, id: Int, pin: String): Result {
        if (pin.length != LENGTH || !pin.all { it.isDigit() }) return Result.Wrong
        if (inNuvio(ctx, id)) {
            val answer = fake[id]?.let { f -> if (f == "off") null else (f == pin) to 0 }
                ?: if (id in fake) null else (ctx.applicationContext as? GlassApp)?.library?.nuvio?.verifyPin(id, pin)
            if (answer != null) {
                if (answer.first) { remember(ctx, "seen$id", pin); clearFails(ctx, id); return Result.Ok }
                return if (answer.second > 0) Result.Wait(answer.second) else Result.Wrong
            }
            // Nuvio didn't answer: the PIN it last accepted here.
            if (!sp(ctx).contains("seen$id")) return Result.Offline
            return local(ctx, id, "seen$id", pin)
        }
        if (!own(ctx, id)) return Result.Ok
        return local(ctx, id, "own$id", pin)
    }

    /** A PIN of Glassio's own for [id] (for a profile without one in Nuvio). */
    fun setOwn(ctx: Context, id: Int, pin: String): Boolean {
        if (pin.length != LENGTH || !pin.all { it.isDigit() }) return false
        remember(ctx, "own$id", pin); clearFails(ctx, id)
        return true
    }
    /** Glassio's own PIN of [id] gone, and with it *Automatic* (nothing left to skip). */
    fun clearOwn(ctx: Context, id: Int) { sp(ctx).edit().remove("own$id").remove("auto$id").apply() }
    /** Everything kept for [id] (the profile was forgotten on this TV). */
    fun forget(ctx: Context, id: Int) { sp(ctx).edit().remove("own$id").remove("seen$id").remove("auto$id").remove("fails$id").remove("until$id").apply() }

    // ---- kept on the TV: "salt:hash", and a wait after wrong tries (5 wrong: 30 s, then 1, 2, 5 minutes)
    private fun local(ctx: Context, id: Int, key: String, pin: String): Result {
        val s = sp(ctx)
        val left = ((s.getLong("until$id", 0L) - System.currentTimeMillis()) / 1000).toInt()
        if (left > 0) return Result.Wait(left)
        if (matches(s.getString(key, "").orEmpty(), pin)) { clearFails(ctx, id); return Result.Ok }
        val fails = s.getInt("fails$id", 0) + 1
        val wait = waitAfter(fails)
        s.edit().putInt("fails$id", fails).apply { if (wait > 0) putLong("until$id", System.currentTimeMillis() + wait * 1000L) }.apply()
        return if (wait > 0) Result.Wait(wait) else Result.Wrong
    }
    /** Seconds to wait after the [fails]th wrong PIN in a row (kept apart for tests). */
    fun waitAfter(fails: Int): Int = when { fails < 5 -> 0; fails == 5 -> 30; fails == 6 -> 60; fails == 7 -> 120; else -> 300 }
    private fun clearFails(ctx: Context, id: Int) { sp(ctx).edit().remove("fails$id").remove("until$id").apply() }

    private fun remember(ctx: Context, key: String, pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        sp(ctx).edit().putString(key, hex(salt) + ":" + hex(hash(pin, salt))).apply()
    }
    /** Whether [pin] is the one behind [kept] ("salt:hash"; kept apart for tests). */
    fun matches(kept: String, pin: String): Boolean {
        val salt = unhex(kept.substringBefore(':', "")) ?: return false
        val want = unhex(kept.substringAfter(':', "")) ?: return false
        return salt.isNotEmpty() && java.security.MessageDigest.isEqual(want, hash(pin, salt))
    }
    /** "salt:hash" for [pin] with [salt] (for tests). */
    fun kept(pin: String, salt: ByteArray): String = hex(salt) + ":" + hex(hash(pin, salt))
    private fun hash(pin: String, salt: ByteArray): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? = runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()?.takeIf { s.length % 2 == 0 }

    /** For problem reports: where each profile's PIN comes from and whether it is asked (never the PIN). */
    fun status(ctx: Context): String = Profiles.all(ctx).filter { has(ctx, it) }.joinToString { "p$it " + source(ctx, it) + if (auto(ctx, it)) " auto" else " ask" }.ifEmpty { "none" }
}
