package com.glasslauncher.tv.data

import org.junit.Assume
import org.junit.Test

/** Looks a trailer up for real (network): only with PROBE=<YouTube id> set. Prints its title and subtitles. */
class TrailerProbeTest {
    @Test fun probe() {
        val id = System.getenv("PROBE")
        Assume.assumeTrue(id != null)
        val r = TrailerSource.resolve(id!!, 1080) ?: error("no streams")
        println("PROBE title=${r.title} subs=${r.subs.map { "${it.lang}/${it.auto}/${it.mime}" }}")
        val l = TrailerSubs.load(r.subs, "ar")
        println("PROBE loaded lang=${l?.lang} lines=${l?.lines?.size} first=${l?.lines?.take(3)}")
    }
}
