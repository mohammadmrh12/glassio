package com.glasslauncher.tv

import com.glasslauncher.tv.data.I18n
import com.glasslauncher.tv.saver.AerialSource
import com.glasslauncher.tv.ui.Cam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PureLogicTest {
    @After fun english() { I18n.lang = "en" }

    // ---- Cameras are kept as "Name|url" lines
    @Test fun cameraLineRoundTrip() {
        val c = Cam("Garden", "rtsp://user:pass@cam.local:554/stream1")
        assertEquals(c, Cam.parse(c.line()))
    }
    @Test fun cameraWithoutNameUsesHost() = assertEquals("cam.local", Cam.parse("|rtsp://cam.local/live")?.name)
    @Test fun notACameraLink() = assertNull(Cam.parse("Garden|not a link"))

    // ---- Languages
    @Test fun englishIsUnchanged() { I18n.lang = "en"; assertEquals("Settings", I18n.tr("Settings")) }
    @Test fun everyLanguageTranslatesTheSections() {
        for (l in I18n.LANGS.keys - "en") {
            I18n.lang = l
            for (w in listOf("Settings", "Connections", "Library", "Screensaver", "About", "Hello", "Later"))
                assertNotEquals("$l has no \"$w\"", w, I18n.tr(w))
        }
    }
    @Test fun untranslatedStaysEnglish() { I18n.lang = "ar"; assertEquals("Some new row", I18n.tr("Some new row")) }
    @Test fun indentedRowsKeepTheirDot() { I18n.lang = "ar"; assertEquals("  · " + I18n.tr("Glow"), I18n.tr("  · Glow")) }

    // ---- Screensaver video quality falls back to what a video has
    private val v = AerialSource.Video("a", "A", "amazon", mapOf("4K-SDR" to "u4k", "1080-SDR" to "u1080"), "")
    @Test fun qualityItHas() = assertEquals("u4k", v.url("4K-SDR"))
    @Test fun hdrFallsBackTo4k() = assertEquals("u4k", v.url("4K-HDR"))
    @Test fun h264FallsBackTo1080() = assertEquals("u1080", v.url("1080-H264"))
}
