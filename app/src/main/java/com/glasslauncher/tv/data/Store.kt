package com.glasslauncher.tv.data

import com.glasslauncher.tv.BuildConfig

/**
 * Which build this is. The play build (Google Play) leaves out what Play does not allow: updating itself, installing
 * other apps, trailers outside YouTube's own player, gift cards, and giving itself rights over the TV's ADB (the
 * person turns those on in the TV's settings instead, after reading what they are for). A constant, so R8 drops
 * the code behind it from the play build. See docs/play/README.md.
 */
object Store {
    const val PLAY: Boolean = BuildConfig.PLAY
}
