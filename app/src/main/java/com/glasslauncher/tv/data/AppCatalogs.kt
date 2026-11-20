package com.glasslauncher.tv.data

import androidx.compose.runtime.mutableStateOf

/**
 * Catalogs of an app: with a streaming app selected in the dock, the carousel shows the titles of the catalogs
 * linked to that app (Netflix → "Netflix Movies", "Netflix Series" …) instead of the general ones.
 *
 * Settings → Catalogs → Catalogs for apps (off by default: nothing changes until it is turned on, or a catalog is
 * linked from an app's menu in the dock). A catalog whose name carries a service's name is linked to that service's
 * app on its own, until the app's list is edited. A linked catalog doesn't have to be chosen for the general
 * carousel. An app that also has rows (Channels) keeps its rows unless "Catalogs in the carousel" is chosen for it.
 */
object AppCatalogs {
    const val ROWS = "rows"; const val CATALOGS = "catalogs"; const val NONE = "none"

    /** The app whose catalogs the carousel shows now (null: the general carousel). Set by the home screen. */
    val pkg = mutableStateOf<String?>(null)

    /** The title the carousel shows now (for dump_focus). */
    @Volatile var slide = ""

    /** Test only (--es app_cw_as "Title=package", until Glassio restarts): a title in progress counted as that app's. Nothing is stored. */
    val testOwner = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val NOT_WORD = Regex("[^a-z0-9]+")
    // (Kept per name: Settings asks for every app's suggestions over the same hundred-odd catalogs.)
    private val wordsOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun words(text: String): String {
        if (wordsOf.size > 2000) wordsOf.clear()
        return wordsOf.getOrPut(text) { " " + text.lowercase().replace("+", " ").replace(NOT_WORD, " ").trim() + " " }
    }

    /** The catalogs whose name says they are [pkg]'s service ("Netflix Movies" for Netflix), in the list's order. */
    fun suggested(catalogs: List<CatalogRef>, pkg: String): List<String> {
        val mine = NuvioLaunch.platformWords().filter { it.second == pkg }.map { " ${it.first} " }
        if (mine.isEmpty()) return emptyList()
        return catalogs.filter { c -> val n = words(c.name); mine.any { n.contains(it) } }.map { it.key }
    }

    /** What was chosen for [pkg] in [raw] ("package<TAB>catalog" lines; a line with no catalog: chosen, perhaps none). Null: never chosen. */
    fun chosen(raw: List<String>, pkg: String): List<String>? {
        val mine = raw.filter { it.substringBefore('\t') == pkg }
        return if (mine.isEmpty()) null else mine.map { it.substringAfter('\t', "") }.filter { it.isNotBlank() }
    }

    fun withChosen(raw: List<String>, pkg: String, keys: List<String>): List<String> =
        raw.filterNot { it.substringBefore('\t') == pkg } + listOf("$pkg\t") + keys.distinct().map { "$pkg\t$it" }

    /** The app's catalogs: the ones chosen for it, or the suggested ones while nothing was chosen. Only ones that still exist. */
    fun keysFor(st: Settings, catalogs: List<CatalogRef>, pkg: String): List<String> {
        val have = catalogs.map { it.key }.toSet()
        return (chosen(st.appCatalogs, pkg) ?: suggested(catalogs, pkg)).filter { it in have }
    }

    fun toggle(st: Settings, catalogs: List<CatalogRef>, pkg: String, key: String) {
        val now = keysFor(st, catalogs, pkg)
        st.appCatalogs = withChosen(st.appCatalogs, pkg, if (key in now) now - key else now + key)
        // Linking a catalog is asking for this: it turns itself on.
        st.appCatalogsOn = true
    }

    /** What was chosen to stand in the carousel's place for [pkg] ("" : not chosen). */
    fun stage(st: Settings, pkg: String): String = st.appStage.firstOrNull { it.substringBefore('=') == pkg }?.substringAfter('=').orEmpty()
    fun setStage(st: Settings, pkg: String, mode: String) {
        st.appStage = st.appStage.filterNot { it.substringBefore('=') == pkg } + (if (mode.isBlank()) emptyList() else listOf("$pkg=$mode"))
    }

    /**
     * What stands in the carousel's place for an app: its rows, its catalogs in the carousel, or nothing (the
     * general carousel). Not chosen: its rows when it has any (as before this existed), else its catalogs.
     * A choice that can't be (rows it doesn't have, catalogs with none linked) falls to the other, then to nothing.
     */
    fun effective(chosen: String, hasRows: Boolean, hasCatalogs: Boolean): String = when (chosen) {
        NONE -> NONE
        CATALOGS -> if (hasCatalogs) CATALOGS else if (hasRows) ROWS else NONE
        else -> if (hasRows) ROWS else if (hasCatalogs) CATALOGS else NONE
    }

    /**
     * The app a title in Continue Watching belongs to: the app of the service it is on in the person's country
     * ([platform], the name TMDB gives) when that app is on this TV ([installed]), so a Netflix title opens Netflix's
     * carousel even when it was watched in Nuvio; else the app it was watched in ([watchedIn], a package): a title
     * on a service whose app isn't here (HBO with no HBO app) stays with Nuvio / Stremio instead of being in no
     * carousel at all. Null: not known, and then it only shows in the general carousel.
     */
    fun appOf(watchedIn: String?, platform: String, installed: (String) -> Boolean): String? =
        NuvioLaunch.platformPackage(platform)?.takeIf(installed) ?: watchedIn

    /**
     * The titles in progress that open [pkg]'s carousel, in Continue Watching's own order. [cw] is what Continue
     * Watching holds; [platformOf] and [watchedIn] are read from what is already known (no request is made here).
     */
    fun continueFor(pkg: String, cw: List<HeroItem>, platformOf: (HeroItem) -> String, watchedIn: (HeroItem) -> String?, installed: (String) -> Boolean): List<HeroItem> =
        cw.filter { appOf(watchedIn(it), platformOf(it), installed) == pkg }

    /** Play on a title of [pkg]'s carousel opens it in [pkg] (Settings → Catalogs & add-ons → Play opens the streaming app, and its apps). */
    fun playsIn(st: Settings, pkg: String): Boolean = st.playInApp && pkg !in st.appCatalogsPlayOff

    /** Each app's catalogs, for the apps given (the ones in the dock): what is loaded ahead. */
    fun links(st: Settings, catalogs: List<CatalogRef>, pkgs: List<String>): Map<String, List<String>> =
        if (!st.appCatalogsOn) emptyMap() else pkgs.associateWith { keysFor(st, catalogs, it) }.filterValues { it.isNotEmpty() }
}
