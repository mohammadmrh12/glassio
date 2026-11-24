package com.glasslauncher.tv.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The two builds of Glassio on one TV hand settings to each other only when the other one is really Glassio: its
 * package and its signing certificate (SHA-256) are checked. The backup carries sign-ins, so no other app may read it.
 */
object Certs {
    /** The GitHub build: the debug key every GitHub release is signed with. */
    const val GITHUB = "0c5478f78b582f9719aa7ec69fc6a007878dcf3d5205e67bd83349a88d8f3a1b"
    /** The Play build: Google Play's app signing key (installs from the store) and the upload key (sideloaded builds). */
    val PLAY = setOf(
        "ae5fe0ca1a7f49815c22df41a7da1a2d621e44b56cd0eed2e61bd7e330ecc5e7",
        "d1d9d86bc5ee756762629e497d0a2f419b06136fe4a8c49f25524149ae8d5434",
    )
    const val GITHUB_PKG = "com.glasslauncher.tv"
    const val PLAY_PKG = "com.glasslauncher.tv.play"
    /** Each build answers at its own package + ".backup". */
    fun authority(pkg: String) = "$pkg.backup"

    /** The SHA-256 of each certificate [pkg] is signed with (empty when it isn't installed or can't be read). */
    fun of(ctx: Context, pkg: String): Set<String> = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val i = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptySet()
            if (i.hasMultipleSigners()) i.apkContentsSigners else i.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptySet()
        sigs.map { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }.getOrDefault(emptySet())

    fun isGithubGlassio(ctx: Context) = of(ctx, GITHUB_PKG).contains(GITHUB)
    fun isPlayGlassio(ctx: Context) = of(ctx, PLAY_PKG).any { it in PLAY }
    /** The other build of Glassio (not this one), installed and really Glassio. */
    fun otherPkg(ctx: Context): String? = if (Store.PLAY) GITHUB_PKG.takeIf { isGithubGlassio(ctx) } else PLAY_PKG.takeIf { isPlayGlassio(ctx) }
    /** [pkg] (a caller) is the other build of Glassio. */
    fun isOtherGlassio(ctx: Context, pkg: String?) = pkg != null && pkg == otherPkg(ctx)
}
