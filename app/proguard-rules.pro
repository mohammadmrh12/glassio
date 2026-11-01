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
