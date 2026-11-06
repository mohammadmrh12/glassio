-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keep class com.glasslauncher.tv.HomeKeyService { *; }
-keep class com.glasslauncher.tv.saver.GlassDreamService { *; }
-keepattributes *Annotation*
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keep class dadb.** { *; }

# LiteRT (depth effect): the native side calls into these classes.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# NewPipe Extractor (trailers)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
-dontwarn org.jsoup.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
