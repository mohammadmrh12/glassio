package com.glasslauncher.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The look and behaviour a new install starts with: the owner's own setup on the Google TV Streamer (2026-11-20),
 * taken from its settings. Only general looks and behaviours are here, never what is personal or needs something
 * set up first (accounts, devices, apps, places, codes). Reads the defaults from Settings.kt itself.
 */
class DefaultsTest {
    private val src = File("src/main/java/com/glasslauncher/tv/data/Settings.kt").readText()

    private val expected = mapOf(
        "artChoice" to "best",
        "bgClockGlass" to "20",
        "bgClockSize" to "0.8",
        "bgInfoWeather" to "false",
        "blurRadius" to "8",
        "channelsBg" to "blur",
        "channelsBlurBri" to "110",
        "channelsCards" to "3",
        "channelsGlow" to "color",
        "channelsGlowSize" to "1.4",
        "channelsLabel" to "icon",
        "channelsLayout" to "single",
        "continueJump" to "true",
        "cwNuvioOnly" to "false",
        "cwOpenSource" to "true",
        "dockColumns" to "7",
        "dockStrip" to "true",
        "dotsMax" to "7",
        "dotsMode" to "liquid",
        "dotsScale" to "0.8",
        "glassColorAmount" to "0.2",
        "glowSize" to "1.6",
        "heroButtonLook" to "theme",
        "islandScale" to "0.7",
        "islandStyle" to "theme",
        "menuHello" to "false",
        "menuScale" to "0.95",
        "newFirst" to "true",
        "refraction" to "10",
        "saverBar" to "black",
        "saverClockShown" to "false",
        "saverMode" to "aerial",
        "saverTrailerInfoSec" to "30",
        "saverTrailerVolume" to "20",
        "settingsFrame" to "circle",
        "showDate" to "true",
        "shuffle" to "true",
        "sideShadeOff" to "true",
        "stagePageBg" to "carousel",
        "sysCpu" to "false",
        "sysGpu" to "false",
        "sysRam" to "false",
        "sysStorage" to "false",
        "topAutoHide" to "false",
        "trailerSubScale" to "1.3",
        "trailerVolume" to "50",
        "transitionMs" to "900",
    )

    private fun defaultOf(key: String): String {
        val m = Regex("""[bifs]\("$key",\s*([^)]*?)\)""").find(src) ?: error("no default for $key")
        return m.groupValues[1].trim().removeSurrounding("\"").removeSuffix("f")
    }

    @Test fun newDefaultsAreHisSetup() {
        for ((k, v) in expected) {
            val d = defaultOf(k)
            val same = d == v || (d.toDoubleOrNull() != null && v.toDoubleOrNull() != null && kotlin.math.abs(d.toDouble() - v.toDouble()) < 1e-6)
            assertTrue("$k: default $d, his setup $v", same)
        }
    }

    /** Left as they were (his choice to make): they turn whole parts of the home screen on or off for everyone. */
    @Test fun featureSwitchesKeptAsBefore() {
        assertEquals("false", defaultOf("channelsOn"))
        assertEquals("first", defaultOf("continueMode"))
        // (Music mode is off from the start since 2026-11-27, his decision: the player's page only on the Music icon.)
        assertEquals("false", defaultOf("musicMode"))
    }
}
