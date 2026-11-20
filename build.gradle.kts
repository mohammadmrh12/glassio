// AGP 9 builds Kotlin itself (built-in Kotlin); the classpath line picks the Kotlin version it uses.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
