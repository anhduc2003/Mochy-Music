# ProGuard rules for the PC release build.
#
# The debug packaging path doesn't shrink, so nothing here is needed for `run` or
# `createDistributable` — only for `packageRelease*`. Everything below is a load that happens by
# name at runtime, which shrinking cannot see and would otherwise strip.

# kotlinx.serialization: the generated serializers are looked up reflectively by class name
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mochyy.desktop.**$$serializer { *; }
-keepclassmembers class com.mochyy.desktop.** {
    *** Companion;
}
-keepclasseswithmembers class com.mochyy.desktop.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# sqlite-jdbc loads the driver and its native library by name
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

# jaudiotagger resolves tag handlers per format reflectively
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# the audio SPI providers are discovered through META-INF/services
-keep class javazoom.** { *; }
-keep class javazoom.spi.** { *; }
-keep class org.tritonus.** { *; }
-keep class com.googlecode.soundlibs.** { *; }
-keep class com.tianscar.** { *; }
-keep class net.sourceforge.jflac.** { *; }
-dontwarn javazoom.**
-dontwarn org.tritonus.**
-dontwarn com.googlecode.**
-dontwarn com.tianscar.**
-dontwarn net.sourceforge.**

# SLF4J is optional to sqlite-jdbc
-dontwarn org.slf4j.**

# Compose keeps its own rules, but the desktop runtime assembles its main class by name
-keep class com.mochyy.desktop.MainKt { *; }
-keep class com.mochyy.desktop.MainKt { public static void main(java.lang.String[]); }

# keep the resource files the app reads at runtime
-keep class javax.imageio.** { *; }
-dontwarn javax.imageio.**

# unused warnings from transitive optional deps
-dontwarn java.lang.invoke.**
-dontwarn **.module-info
