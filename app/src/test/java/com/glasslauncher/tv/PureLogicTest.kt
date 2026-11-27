package com.glasslauncher.tv

import com.glasslauncher.tv.data.I18n
import com.glasslauncher.tv.saver.AerialSource
import com.glasslauncher.tv.ui.Cam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- Hue: the bridge's certificate is the one first seen
    @Test fun huePinIsLearntOnceThenHeld() {
        val hue = com.glasslauncher.tv.data.Hue
        val Learn = com.glasslauncher.tv.data.Hue.Pin.Learn; val Trust = com.glasslauncher.tv.data.Hue.Pin.Trust; val Refuse = com.glasslauncher.tv.data.Hue.Pin.Refuse
        assertEquals(Learn, hue.pinVerdict("", "192.0.2.5", "AAA"))
        assertEquals(Trust, hue.pinVerdict("192.0.2.5 AAA", "192.0.2.5", "AAA"))
        assertEquals(Refuse, hue.pinVerdict("192.0.2.5 AAA", "192.0.2.5", "BBB"))
        // Another address (the bridge was changed, or got a new address): learnt again.
        assertEquals(Learn, hue.pinVerdict("192.0.2.5 AAA", "192.0.2.9", "BBB"))
        // A damaged value never locks the bridge out.
        assertEquals(Learn, hue.pinVerdict("garbage", "192.0.2.5", "AAA"))
    }

    // ---- Profiles: which file, which setting belongs to the TV, what a new profile starts with
    @Test fun homeProfileKeepsTheOldFiles() {
        val p = com.glasslauncher.tv.data.Profiles
        assertEquals("glass", p.fileName("glass", 1, 1))
        assertEquals("glass_p2", p.fileName("glass", 2, 1))
        assertEquals("glass_catalogs_p2", p.fileName("glass_catalogs", 2, 1))
        // Another profile was in use before profiles existed: it keeps the files, the main one gets its own.
        assertEquals("glass", p.fileName("glass", 3, 3))
        assertEquals("glass_p1", p.fileName("glass", 1, 3))
    }

    @Test fun tvSettingsAreSharedLookIsNot() {
        val p = com.glasslauncher.tv.data.Profiles
        for (k in listOf("hueIp", "hueUser", "huePin", "cameras", "keyMaps", "updateChannel", "perfMode", "doorbellOn", "haToken", "lockCode", "inputsOrder", "channelsHue"))
            assertEquals(k, true, p.isDevice(k))
        for (k in listOf("theme", "dockOrder", "hiddenApps", "bgMode", "saverMode", "likes", "hiddenTitles", "settingsIcon", "heroAutoplay", "hasSomething", "happy"))
            assertEquals(k, false, p.isDevice(k))
    }

    /** Every key named as the TV's is a real setting (a typo there would quietly make it per profile). */
    @Test fun tvKeysExistInSettings() {
        val settings = java.io.File("src/main/java/com/glasslauncher/tv/data/Settings.kt").readText()
        val profiles = java.io.File("src/main/java/com/glasslauncher/tv/data/Profiles.kt").readText()
        val block = profiles.substringAfter("private val DEVICE = setOf(").substringBefore("\n    )")
        val keys = Regex("\"(\\w+)\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(true, keys.size > 40)
        for (k in keys) assertEquals("\"$k\" is not a key in Settings.kt", true, settings.contains("\"$k\""))
    }

    @Test fun aNewProfileStartsWithTheLookNotThePerson() {
        val seed = com.glasslauncher.tv.data.Profiles.seed(mapOf("theme" to "water", "dockOrder" to "a\nb", "likes" to "x", "hiddenTitles" to "y",
            "hueIp" to "192.0.2.5", "cwTrakt" to true, "settingsIcon" to "photo", "dockScale" to 0.9f))
        assertEquals("water", seed["theme"]); assertEquals("a\nb", seed["dockOrder"]); assertEquals(0.9f, seed["dockScale"])
        assertEquals(false, seed.containsKey("likes")); assertEquals(false, seed.containsKey("hiddenTitles")); assertEquals(false, seed.containsKey("settingsIcon"))
        assertEquals(false, seed.containsKey("hueIp"))
        assertEquals(false, seed["cwTrakt"]); assertEquals(false, seed["libraryTrakt"])
    }

    @Test fun atWakeAskOrGoToTheAutomaticProfile() {
        val p = com.glasslauncher.tv.data.Profiles
        // One profile, or not signed in: nothing to choose.
        assertEquals(0, p.onWake("ask", 1, 1, listOf(1), true))
        assertEquals(0, p.onWake("ask", 1, 1, listOf(1, 2), false))
        assertEquals(-1, p.onWake("ask", 1, 1, listOf(1, 2), true))
        // Automatic: stays when already there, goes back to it from another, never to one that is gone.
        assertEquals(0, p.onWake("auto", 1, 1, listOf(1, 2), true))
        assertEquals(1, p.onWake("auto", 2, 1, listOf(1, 2), true))
        assertEquals(0, p.onWake("auto", 2, 3, listOf(1, 2), true))
    }

    @Test fun titleLinks() {
        val l = com.glasslauncher.tv.data.PlatformLinks
        assertEquals("https://www.netflix.com/title/81446288", l.fromOffer("https://www.netflix.com/title/81446288", "", "FROM")!!.url)
        // An Apple TV show: the web link is an episode, the show's id comes from the TV link.
        val show = l.fromOffer("https://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?at=1",
            "intent://tv.apple.com/us/episode/x/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000", "Severance")!!
        assertEquals(l.APPLE, show.pkg)
        assertEquals("https://tv.apple.com/us/show/severance/umc.cmc.1srk2goyh2q2zdxcx605w8vtx", show.url)
        assertEquals("https://tv.apple.com/us/movie/the-gorge/umc.cmc.abc123", l.fromOffer("https://tv.apple.com/us/movie/the-gorge/umc.cmc.abc123?at=1", "", "The Gorge")!!.url)
        // An episode with no show id is not a title's page.
        assertEquals(null, l.fromOffer("https://tv.apple.com/us/episode/x/umc.cmc.abc", "", "X"))
        assertEquals("https://watch.amazon.com/detail?gti=amzn1.dv.gti.92c7e5ed-9cf4", l.fromOffer("https://watch.amazon.com/detail?gti=amzn1.dv.gti.92c7e5ed-9cf4", "", "X")!!.url)
        assertEquals(l.DISNEY, l.fromOffer("https://www.disneyplus.com/browse/entity-8744ec55-8d14", "", "X")!!.pkg)
        // A shop is not a place to watch.
        assertEquals(null, l.fromOffer("https://www.amazon.com/dp/B0DK99V19R?tag=x", "", "X"))
        assertEquals("ant-man-and-the-wasp-quantumania", l.slug("Ant-Man and the Wasp: Quantumania"))
        // The app the title is listed on first, then the usual order; never an app that isn't installed.
        val found = listOf(l.netflix("1"), l.primeAsin("B0"), l.apple("umc.cmc.a", true, "A"))
        assertEquals(l.PRIME, l.pick(found, setOf(l.NETFLIX, l.PRIME), l.PRIME)!!.pkg)
        assertEquals(l.NETFLIX, l.pick(found, setOf(l.NETFLIX, l.PRIME), l.DISNEY)!!.pkg)
        assertEquals(l.APPLE, l.pick(found, setOf(l.APPLE), null)!!.pkg)
        assertEquals(null, l.pick(found, setOf(l.DISNEY), null))
    }

    @Test fun watchCountry() {
        val r = com.glasslauncher.tv.data.Region
        assertEquals("IQ", r.pick("iq", "US"))       // chosen in Settings
        assertEquals("DE", r.pick("", "de"))         // the TV's own
        assertEquals("US", r.pick("", ""))           // a TV with no country
        assertEquals("US", r.pick("", "419"))        // a region that isn't a country (es-419)
        assertEquals("GB", r.pick("x", "GB"))
        // An Apple TV link keeps the storefront the title was found in.
        val l = com.glasslauncher.tv.data.PlatformLinks
        assertEquals("https://tv.apple.com/gb/movie/the-gorge/umc.cmc.abc123", l.fromOffer("https://tv.apple.com/gb/movie/the-gorge/umc.cmc.abc123", "", "The Gorge")!!.url)
        assertEquals("https://tv.apple.com/us/show/x/umc.cmc.a", l.apple("umc.cmc.a", false, "X", "nonsense").url)
    }

    @Test fun platformRows() {
        val p = com.glasslauncher.tv.data.PlatformRows
        val l = com.glasslauncher.tv.data.PlatformLinks
        assertEquals(listOf("netflix", "apple"), p.wanted(p.AUTO) { it == l.NETFLIX || it == l.APPLE }.map { it.key })
        assertEquals(emptyList<String>(), p.wanted(p.OFF) { true }.map { it.key })
        // Chosen ones show whether or not their app is installed, in the usual order.
        assertEquals(listOf("prime", "shahid"), p.wanted("shahid, prime") { false }.map { it.key })
        assertEquals(p.SERVICES.size, p.SERVICES.map { it.key }.toSet().size)
        // A service's numbers come from the country's own list; one sold inside another service isn't it.
        val all = mapOf(8 to "Netflix", 1796 to "Netflix Standard with Ads", 119 to "Amazon Prime Video", 582 to "Paramount+ Amazon Channel",
            531 to "Paramount Plus", 2 to "Apple TV Store", 350 to "Apple TV", 1899 to "Max", 1825 to "Max Amazon Channel", 9999 to "Maxdome")
        fun ids(k: String) = p.idsIn(p.SERVICES.first { it.key == k }, all)
        assertEquals(listOf(8, 1796), ids("netflix"))
        assertEquals(listOf(119), ids("prime"))
        assertEquals(listOf(350), ids("apple"))    // the shop ("Apple TV Store") is not the service
        assertEquals(listOf(531), ids("paramount"))
        assertEquals(listOf(1899), ids("max"))
        assertEquals(listOf(337), ids("disney"))   // not in the list: its usual number
        fun item(k: String) = com.glasslauncher.tv.data.HeroItem(k, k, "movie", k, "", "", "", "", "", "", emptyList(), "")
        assertEquals(listOf("s1", "m1", "s2", "m2", "m3"), p.mix(listOf(item("m1"), item("m2"), item("m3")), listOf(item("s1"), item("s2")), 5).map { it.key })
        assertEquals(listOf("a", "b"), p.mix(listOf(item("a"), item("b")), listOf(item("a")), 9).map { it.key })
    }

    @Test fun menuGreeting() {
        fun g(icon: String, hello: String, name: String) = com.glasslauncher.tv.ui.menuGreeting(icon, hello, name)
        assertEquals("Hello, mohammad" to true, g("sliders", "Hello", "mohammad"))
        // The button already says Hello: the name finishes it.
        assertEquals("mohammad" to true, g("hello", "Hello", " mohammad "))
        assertEquals("Sara" to true, g("helloScript", "Hello", "Sara"))
        // The button is the profile's picture: it isn't shown a second time.
        assertEquals("Hello, mohammad" to false, g("picture", "Hello", "mohammad"))
    }

    @Test fun reportScrubbing() {
        val r = com.glasslauncher.tv.data.Report
        fun gone(private: String, text: String) { val out = r.scrub(text); assertTrue("\"$private\" is still in: $out", !out.contains(private)) }
        // Camera links whole: address, port, path, name and password.
        gone("s3cret", "Camera rtsp://admin:s3cret@198.51.100.50:554/stream1 failed")
        gone("198.51.100.50", "Camera rtsp://admin:s3cret@198.51.100.50:554/stream1 failed")
        gone("stream1", "Camera rtsps://198.51.100.50/stream1 failed")
        // A name and password in any link.
        gone("hunter2", "GET https://bob:hunter2@example.com/x")
        gone("bob", "GET https://bob:hunter2@example.com/x")
        // Addresses: the home network's, a public one, IPv6, with a port.
        for (a in listOf("203.0.113.12", "10.20.30.40", "172.16.4.9", "8.8.4.4", "100.64.1.2"))  gone(a, "Sync with $a:8765 failed")
        gone("fe80::1c2d:3eff:fe4a:5b6c", "bound to fe80::1c2d:3eff:fe4a:5b6c%wlan0")
        gone("2001:db8:85a3:0:0:8a2e:370:7334", "peer 2001:db8:85a3:0:0:8a2e:370:7334 gone")
        gone("livingroom-tv", "Hue bridge at livingroom-tv.local")
        // Hardware addresses.
        gone("AA:BB:CC:11:22:33", "Bluetooth AA:BB:CC:11:22:33 disconnected")
        gone("aa-bb-cc-11-22-33", "wlan0 aa-bb-cc-11-22-33")
        // Emails.
        gone("someone.name+tv@example.com", "Nuvio account connected (someone.name+tv@example.com)")
        // Tokens and keys, however they are written.
        gone("eyJ0eXAiOiJKV1QifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEF123_-xyz", "auth failed for eyJ0eXAiOiJKV1QifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEF123_-xyz")
        gone("abc123def", "Authorization: Bearer abc123def456")
        gone("tok_9f8e7d", "nuvioToken=tok_9f8e7d")
        gone("p4ss", "{\"password\": \"p4ss w0rd\", \"x\": 1}")
        gone("0123", "lockCode=0123")
        gone("sk_live_1", "api_key: sk_live_1")
        gone("abcdef0123456789abcdef0123456789", "tmdb abcdef0123456789abcdef0123456789 refused")
        gone("tok_0123456789abcdefghijABCDEFGHIJ012345", "git said tok_0123456789abcdefghijABCDEFGHIJ012345")
        // The network's name.
        gone("MyHomeWifi", "connected, SSID=\"MyHomeWifi\" rssi -50")
        gone("MyHomeWifi", "ssid: MyHomeWifi")
        // A person's folder.
        gone("alex", "/Users/alex/work/x.kt and /home/alex/x")
        // What a report is for stays readable.
        val keep = "11-19 17:40:12 W Title link from JustWatch: IllegalStateException: HTTP 503 at com.glasslauncher.tv.data.PlatformLinks.find(PlatformLinks.kt:120)"
        assertEquals(keep, r.scrub(keep))
        assertEquals("theme=water_still  dockStyle=strip  versionCode=68  memTier=2", r.scrub("theme=water_still  dockStyle=strip  versionCode=68  memTier=2"))
        assertEquals("Glassio 4.0.0-beta1 (68)", r.scrub("Glassio 4.0.0-beta1 (68)"))
        // Scrubbing what is already scrubbed changes nothing.
        val once = r.scrub("rtsp://a:b@10.0.0.2/x token=abc mail me@x.io")
        assertEquals(once, r.scrub(once))
    }

    @Test fun reportCrashSignature() {
        val r = com.glasslauncher.tv.data.Report
        val a = "2026-11-19 10:00\njava.lang.IllegalStateException: no item 7\n\tat kotlin.collections.X.get(X.kt:1)\n\tat com.glasslauncher.tv.ui.HeroKt.HeroSection(Hero.kt:320)\n"
        val b = "2026-11-20 22:15\njava.lang.IllegalStateException: no item 12\n\tat kotlin.collections.X.get(X.kt:1)\n\tat com.glasslauncher.tv.ui.HeroKt.HeroSection(Hero.kt:331)\n"
        val c = "2026-11-20 22:15\njava.lang.NullPointerException: x\n\tat com.glasslauncher.tv.ui.HeroKt.HeroSection(Hero.kt:331)\n"
        // The same crash on another day, with another message and line: one signature. Another exception: another one.
        assertEquals(r.signatureOf(a), r.signatureOf(b))
        assertTrue(r.signatureOf(a) != r.signatureOf(c))
        assertEquals(12, r.signatureOf(a).length)
        assertEquals("", r.signatureOf(""))
        // The phone's link: a new issue with the short summary, small enough for a QR code, never the log.
        val d = com.glasslauncher.tv.data.Report.Data("The dock froze\nafter the screensaver", "4.0.0-beta1 (68)", "Google Streamer", "Android 14", "profile 1 of 2", "theme=x", "LOGLINE", a, r.signatureOf(a))
        val link = r.issueLink(d)
        assertTrue(link.startsWith("https://github.com/mohammadmrh12/glassio/issues/new?labels=bug&title=The+dock+froze&body="))
        assertTrue(!link.contains("LOGLINE") && link.length < 900)
    }

    @Test fun appCatalogs() {
        val a = com.glasslauncher.tv.data.AppCatalogs
        val l = com.glasslauncher.tv.data.PlatformLinks
        fun c(name: String) = com.glasslauncher.tv.data.CatalogRef("https://x/manifest.json", "Catalogs", "movie", name.lowercase().replace(' ', '_'), name)
        val all = listOf(c("Netflix Movies"), c("Netflix Series"), c("Apple TV+ Series"), c("Popular"), c("Prime Video Movies"), c("Maximum Action"), c("Internetflixers"))
        // A catalog named after a service is its app's; a word that only contains the name is not.
        assertEquals(listOf("Netflix Movies", "Netflix Series"), a.suggested(all, l.NETFLIX).map { k -> all.first { it.key == k }.name })
        assertEquals(listOf("Apple TV+ Series"), a.suggested(all, l.APPLE).map { k -> all.first { it.key == k }.name })
        assertEquals(listOf("Prime Video Movies"), a.suggested(all, l.PRIME).map { k -> all.first { it.key == k }.name })
        assertEquals(emptyList<String>(), a.suggested(all, "com.wbd.stream"))
        assertEquals(emptyList<String>(), a.suggested(all, "some.other.app"))
        // Chosen by hand: exactly that, also when it is nothing; never chosen: null (the suggested ones are used).
        assertEquals(null, a.chosen(emptyList(), l.NETFLIX))
        val one = a.withChosen(emptyList(), l.NETFLIX, listOf("k1", "k2"))
        assertEquals(listOf("k1", "k2"), a.chosen(one, l.NETFLIX))
        assertEquals(null, a.chosen(one, l.APPLE))
        val none = a.withChosen(one, l.NETFLIX, emptyList())
        assertEquals(emptyList<String>(), a.chosen(none, l.NETFLIX))
        assertEquals(listOf("k9"), a.chosen(a.withChosen(none, l.APPLE, listOf("k9")), l.APPLE))
        // What stands in the carousel's place: rows stay unless catalogs are asked for; nothing changes without either.
        assertEquals(a.ROWS, a.effective("", true, true))
        assertEquals(a.CATALOGS, a.effective("", false, true))
        assertEquals(a.NONE, a.effective("", false, false))
        assertEquals(a.CATALOGS, a.effective(a.CATALOGS, true, true))
        assertEquals(a.ROWS, a.effective(a.CATALOGS, true, false))
        assertEquals(a.NONE, a.effective(a.NONE, true, true))
        assertEquals(a.CATALOGS, a.effective(a.ROWS, false, true))
    }

    @Test fun appContinueWatching() {
        val a = com.glasslauncher.tv.data.AppCatalogs
        val l = com.glasslauncher.tv.data.PlatformLinks
        fun item(k: String) = com.glasslauncher.tv.data.HeroItem(k, k, "movie", k, "", "", "", "", "", "", emptyList(), "")
        val nuvio = "com.nuvio.tv"
        val cw = listOf(item("onNetflix"), item("onApple"), item("unknown"), item("appleWatchedInNuvio"), item("onNetflix2"), item("onlyWatchedInNuvio"),
            item("oddServiceInNuvio"), item("hboWatchedInNuvio"), item("hboNowhere"))
        val platform = mapOf("onNetflix" to "Netflix", "onApple" to "Apple TV", "appleWatchedInNuvio" to "Apple TV", "onNetflix2" to "Netflix",
            "oddServiceInNuvio" to "Some Unknown Service", "hboWatchedInNuvio" to "HBO", "hboNowhere" to "HBO")
        val watched = mapOf("onNetflix" to nuvio, "appleWatchedInNuvio" to nuvio, "onlyWatchedInNuvio" to nuvio, "oddServiceInNuvio" to nuvio, "hboWatchedInNuvio" to nuvio)
        // On this TV: Netflix, Apple TV and Nuvio. No HBO app.
        val here = setOf(l.NETFLIX, l.APPLE, nuvio)
        fun of(pkg: String) = a.continueFor(pkg, cw, { platform[it.key].orEmpty() }, { watched[it.key] }, { it in here }).map { it.key }
        // The service a title is on comes before where it was watched; Continue Watching's own order is kept.
        assertEquals(listOf("onNetflix", "onNetflix2"), of(l.NETFLIX))
        assertEquals(listOf("onApple", "appleWatchedInNuvio"), of(l.APPLE))
        // Where it was watched counts when no service is known for it, or the service's app isn't on this TV.
        assertEquals(listOf("onlyWatchedInNuvio", "oddServiceInNuvio", "hboWatchedInNuvio"), of(nuvio))
        assertEquals(l.NETFLIX, a.appOf(nuvio, "Netflix") { true })
        assertEquals(nuvio, a.appOf(nuvio, "Netflix") { false })
        assertEquals(nuvio, a.appOf(nuvio, "") { true })
        // A title no app is known for is in no app's carousel.
        assertEquals(emptyList<String>(), of(l.PRIME))
        assertEquals(null, a.appOf(null, "") { true })
        assertEquals(null, a.appOf(null, "Some Unknown Service") { true })
        assertEquals(l.NETFLIX, a.appOf(null, "Netflix") { true })
        assertEquals(null, a.appOf(null, "HBO") { false })
    }

    @Test fun trailerScreensaverFallsBackToPosters() {
        fun f(p: Boolean, n: Boolean, t: Boolean, o: Boolean) = com.glasslauncher.tv.ui.saverShowsPosters(p, n, t, o)
        // A trailer playing: the trailer, whatever else is true.
        assertEquals(false, f(true, true, true, false))
        // Nothing to play: no titles, none of them with a trailer, or no internet: the posters.
        assertEquals(true, f(false, false, true, true))
        assertEquals(true, f(false, true, false, true))
        assertEquals(true, f(false, false, false, false))
        // Still looking for the first one: neither (the title's picture shows meanwhile).
        assertEquals(false, f(false, false, false, true))
    }

    /** The section Settings was left on before the sections were reordered opens the section that holds it now. */
    @Test fun lastSectionFollowsTheNewOrder() {
        val o = com.glasslauncher.tv.ui.CATEGORY_ORDER
        assertEquals(11, o.size)
        assertEquals("About", o.last())
        fun now(old: Int) = o[com.glasslauncher.tv.ui.sectionAfterReorder(old)]
        assertEquals("Accounts & profiles", now(0))               // Connections
        assertEquals("Continue Watching & Library", now(3))       // Library
        assertEquals("Music & app rows", now(6))                  // Channels
        assertEquals("Look & menu", now(7))                       // Menu & themes
        assertEquals("Background & screensaver", now(10))         // Screensaver
        assertEquals("Smart home", now(12))                       // Hue lights
        assertEquals("System", now(16))                           // Sync
        assertEquals("About", now(18))
        assertEquals("Accounts & profiles", now(40))              // not a section: the first one
        // Every new section name has its Arabic.
        I18n.lang = "ar"
        o.forEach { assertNotEquals(it, I18n.tr(it)) }
    }

    /** The search reads a source's quality and size from the words an add-on writes, whatever its style. */
    @Test fun searchReadsQualityAndSize() {
        val S = com.glasslauncher.tv.data.Search
        assertEquals(2160, S.qualityOf("Torrentio\n4k HDR"))
        assertEquals(2160, S.qualityOf("Some.Film.2160p.WEB-DL.x265"))
        assertEquals(1080, S.qualityOf("[TB+] Film 1080p BluRay"))
        assertEquals(720, S.qualityOf("Film.720p.HDTV"))
        assertEquals(0, S.qualityOf("Film HDRip"))
        assertEquals(0, S.qualityOf("x1080px"))                    // inside another word: not a quality
        assertEquals(2254857830L, S.sizeOf("👤 12 💾 2.1 GB ⚙️ site"))
        assertEquals(734003200L, S.sizeOf("700 MB"))
        assertEquals(1610612736L, S.sizeOf("1,5 GiB"))
        assertEquals(0L, S.sizeOf("no size here"))
        assertEquals("2.1 GB", S.sizeText(2254857830L))
        assertEquals("700 MB", S.sizeText(734003200L))
        // Films and series in turn, the longer list's rest at the end.
        assertEquals(listOf("a", "x", "b", "y", "c"), S.mix(listOf("a", "b", "c"), listOf("x", "y")))
    }

    /** Every language has its file of the screens' words, whole: one translation a line, none empty, no word twice. */
    @Test fun languageFilesAreWhole() {
        val dir = java.io.File("src/main/assets/i18n")
        for (code in I18n.LANGS.keys - "en") {
            val f = java.io.File(dir, "$code.tsv")
            assertTrue("$code.tsv is missing", f.exists())
            val lines = f.readLines().filter { it.isNotBlank() }
            assertTrue("$code.tsv has only ${lines.size} lines", lines.size > 1400)
            lines.forEach { l -> assertEquals("$code: one tab a line: $l", 1, l.count { it == '\t' }); assertTrue("$code: empty side: $l", l.substringBefore('\t').isNotBlank() && l.substringAfter('\t').isNotBlank()) }
            assertEquals("$code: a word twice", lines.size, I18n.parse(lines.asSequence()).size)
        }
        // A line break is written as \n in the file and read back as one.
        assertEquals(mapOf("a\nb" to "c\nd"), I18n.parse(sequenceOf("a\\nb\tc\\nd")))
    }

    /** A profile's PIN as it is kept (salted, hashed), and the wait after wrong tries. */
    @Test fun profilePin() {
        val P = com.glasslauncher.tv.data.ProfilePin
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val kept = P.kept("4821", salt)
        assertTrue(P.matches(kept, "4821"))
        assertTrue(!P.matches(kept, "4822"))
        assertTrue(!P.matches(kept, ""))
        // The digits themselves are nowhere in what is kept.
        assertTrue(!kept.contains("4821"))
        // Another salt: another value for the same PIN.
        assertTrue(kept != P.kept("4821", ByteArray(16) { it.toByte() }))
        // Broken or empty values never match.
        assertTrue(!P.matches("", "4821")); assertTrue(!P.matches("zz:zz", "4821")); assertTrue(!P.matches(":", "4821"))
        // Four wrong tries cost nothing; then 30 s, 1, 2 and 5 minutes.
        assertEquals(listOf(0, 0, 30, 60, 120, 300, 300), listOf(1, 4, 5, 6, 7, 8, 20).map { P.waitAfter(it) })
    }

    /** Lyrics: reading LRC, where the song is among the lines, the words lit, and the names tried for a video's title. */
    @Test fun lyrics() {
        val L = com.glasslauncher.tv.data.Lyrics
        val lines = L.parse("[00:33.03] first line\n[00:39.48]second\n[01:02.5]\n[ar: someone]\nno stamp\n[00:10.00][01:10.00] twice")
        assertEquals(listOf(10_000L, 33_030L, 39_480L, 62_500L, 70_000L), lines.map { it.at })
        assertEquals(listOf("twice", "first line", "second", "", "twice"), lines.map { it.text })
        // Before the first line; inside one; a line lasts until the next starts, 9 s at most.
        assertEquals(-1, L.at(lines, 5_000).first)
        assertEquals(1, L.at(lines, 34_000).first)
        assertEquals(1f, L.at(lines, 60_000).second)           // "second" ran out long before the pause at 62.5 s
        assertEquals(4, L.at(lines, 500_000).first)
        // Words light in turn by their length; all of them a little before the line ends.
        val w = listOf("one", "elephant", "is")
        assertEquals(0, L.wordsSung(w, 0f)); assertEquals(1, L.wordsSung(w, 0.1f)); assertEquals(3, L.wordsSung(w, 0.9f))
        assertEquals(0, L.wordsSung(emptyList(), 0.5f))
        // A video's title: as it is, without the brackets, then split at the dash both ways.
        assertEquals(listOf("Ludovico Einaudi - Experience (Official Video)" to "Chan", "Ludovico Einaudi - Experience" to "Chan", "Experience" to "Ludovico Einaudi", "Ludovico Einaudi" to "Experience"),
            L.candidates("Ludovico Einaudi - Experience (Official Video)", "Chan"))
        assertEquals(listOf("Time" to "Hans Zimmer"), L.candidates("Time", "Hans Zimmer - Topic"))
        // Plain words: spread over the song, in order.
        val sp = L.spread("a\n\nb\nc", 100_000)
        assertEquals(3, sp.size); assertTrue(sp[0].at < sp[1].at && sp[1].at < sp[2].at && sp[2].at < 100_000)
    }

    /** Lyrics from the other sources: word-by-word files, credits left out, which answer wins, which song is the same. */
    @Test fun lyricsSources() {
        val L = com.glasslauncher.tv.data.Lyrics
        // NetEase's word file without the space between some words: taken from the same song's plain line near that time.
        val glued = L.parseYrc("[32820,4800](32820,540,0)Maybe (33360,120,0)you (33900,330,0)me (34230,390,0)how(34620,120,0)to (34740,900,0)love(35640,30,0), (35670,1950,0)maybe\n[38430,2130](38430,240,0)I'm(38670,300,0)going (38970,180,0)through")
        val fixed = L.respace(glued, L.parse("[00:32.82]Maybe you me how to love, maybe\n[00:50.00]I'm going through"))
        assertEquals(listOf("Maybe you me how to love, maybe", "I'mgoing through"), fixed.map { it.text })
        assertEquals(listOf("Maybe ", "you ", "me ", "how ", "to ", "love", ", ", "maybe"), fixed[0].words!!.map { it.text })
        assertEquals(34620L, fixed[0].words!![4].at)
        // (A space sent as a word of its own doesn't double the space.)
        assertEquals(listOf("a ", "b"), L.respace(L.parseYrc("[1000,900](1000,300,0)a(1300,10,0) (1400,500,0)b\n[5000,900](5000,400,0)x (5400,500,0)y\n[9000,900](9000,400,0)x (9400,500,0)y"), L.parse("[00:01.00]a  b"))[0].words!!.map { it.text })
        // (Kept as one text: the word lines, then the plain lines.)
        assertEquals("how to", L.decode("Y netease\n[1000,900](1000,400,0)how(1400,500,0)to\n[2000,900](2000,400,0)a (2400,500,0)b\n[00:01.00]how to\n[00:02.00]a b", 0)!!.lines[0].text)
        // NetEase: each word's own moment; its header lines are no lines; a pause where the singing stops for 4 s.
        val yrc = "{\"t\":0,\"c\":[{\"tx\":\"x: \"},{\"tx\":\"y\"}]}\n[18940,3660](18940,270,0)We're (19210,180,0)no (19390,600,0)strangers\n[22810,1000](22810,330,0)You (23140,300,0)know\n[30000,900](30000,400,0)Never (30400,500,0)gonna"
        val y = L.parseYrc(yrc)
        assertEquals(listOf("We're no strangers", "You know", "", "Never gonna"), y.map { it.text })
        assertEquals(listOf(18940L, 22810L, 23810L, 30000L), y.map { it.at })
        assertEquals(listOf(18940L, 19210L, 19390L), y[0].words!!.map { it.at })
        assertEquals("We're ", y[0].words!![0].text)
        // KuGou: a word's moment counts from its line's start; and its file opens (four letters, then packed under a mask).
        val krcText = "[id:1]\r\n[1000,2000]<0,500,0>Tamally <500,700,0>maak\r\n[3200,900]<0,400,0>We <400,500,0>law"
        val k = L.parseKrc(krcText)
        assertEquals(listOf("Tamally maak", "We law"), k.map { it.text }); assertEquals(listOf(1000L, 1500L), k[0].words!!.map { it.at })
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val zipped = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(krcText.toByteArray()) } }.toByteArray()
        val file = "krc1".toByteArray() + ByteArray(zipped.size) { i -> (zipped[i].toInt() xor key[i % 16]).toByte() }
        assertEquals(krcText, L.unKrc(file)); assertNull(L.unKrc(byteArrayOf(1, 2)))
        // Credits and a "Name - Artist" first line are no words of the song; a dash later in the song stays.
        val raw = L.parse("[00:00.00]Some Song - Some Singer\n[00:01.00] 作词 : Somebody\n[00:02.00]Lyrics by：Somebody\n[00:10.00]first words\n[00:14.00]and more - of them\n[00:18.00]制作人 someone\n[00:20.00]the last")
        assertEquals(listOf("first words", "and more - of them", "the last"), L.clean(raw).map { it.text })
        // Which answer wins: word by word from NetEase, then LRCLIB's lines; the song's own writing before Latin letters.
        val ne = com.glasslauncher.tv.data.Lyrics.Found("netease", 'Y', "[1,2](1,2,0)hello (3,4,0)there"); val lr = com.glasslauncher.tv.data.Lyrics.Found("lrclib", 'S', "[00:01.00]hello there")
        val ku = com.glasslauncher.tv.data.Lyrics.Found("kugou", 'K', "[1,2]<0,2,0>Tamally <2,2,0>maak"); val ar = com.glasslauncher.tv.data.Lyrics.Found("lrclib", 'S', "[00:45.60]تملي معاك")
        val plain = com.glasslauncher.tv.data.Lyrics.Found("ovh", 'P', "hello there")
        org.junit.Assert.assertSame(ne, L.pick(listOf(plain, lr, ne))); org.junit.Assert.assertSame(lr, L.pick(listOf(plain, lr, ku)))
        org.junit.Assert.assertSame(ar, L.pick(listOf(ku, ar))); org.junit.Assert.assertSame(ar, L.pick(listOf(com.glasslauncher.tv.data.Lyrics.Found("netease", 'Y', "[1,2](1,2,0)Tamally (3,4,0)maak"), ar)))
        org.junit.Assert.assertSame(plain, L.pick(listOf(plain))); assertNull(L.pick(emptyList()))
        // The same song: the same name (or most of it), and the artist when both are known.
        assertTrue(L.sameSong("Never Gonna Give You Up", "Rick Astley", "Never Gonna Give You Up (Remastered)", listOf("Rick Astley")))
        assertTrue(L.sameSong("Hekm El Alb", "Wael Kfoury", "Hekm El Alb", emptyList()))
        org.junit.Assert.assertFalse(L.sameSong("Time", "Hans Zimmer", "Summertime Sadness", listOf("Lana Del Rey")))
        org.junit.Assert.assertFalse(L.sameSong("Bad Times", "Imael Angel", "Bad Times", listOf("Someone Else")))
        // What is kept reads back; an older file (no source named) is LRCLIB's.
        val kept = L.decode(ne.kept(), 0)!!
        assertTrue(kept.byWord && kept.synced); assertEquals("netease", kept.source)
        assertEquals("lrclib", L.decode("S\n[00:01.00]hello there", 0)!!.source); assertNull(L.decode("-", 0))
    }
}
