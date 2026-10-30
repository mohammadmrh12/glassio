package com.glasslauncher.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for the settings file. Every setting is stored under a key on the TV; a key that changes by mistake
 * (a typo, a copy-paste from the next line) silently resets that setting for everyone who updates.
 */
class SettingsKeysTest {
    private val src = File("src/main/java/com/glasslauncher/tv/data/Settings.kt").readText()

    /** "var name: Type get() = x("key", …); set(v) = putX("key", …)" lines. */
    private val props = Regex("""var (\w+): [\w<>]+\s+get\(\) = [bifs]\("(\w+)",[^;]*;\s*set\(v\)\s*(?:=|\{)\s*put[BIFS]\("(\w+)"""").findAll(src).toList()

    @Test fun everySettingIsFound() = assertTrue("found ${props.size}", props.size > 120)

    @Test fun readAndWriteUseTheSameKey() {
        for (m in props) {
            val (name, readKey, writeKey) = m.destructured
            assertEquals("$name reads \"$readKey\" but writes \"$writeKey\"", readKey, writeKey)
        }
    }

    @Test fun keyIsThePropertyName() {
        for (m in props) {
            val (name, key) = m.destructured
            assertEquals("property $name is stored as \"$key\"", name, key)
        }
    }

    @Test fun noKeyIsUsedTwice() {
        val keys = props.map { it.groupValues[2] }
        val twice = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("used by two settings: $twice", twice.isEmpty())
    }
}
