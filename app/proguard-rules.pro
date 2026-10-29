# --- Kotlinx serialization ---------------------------------------------------
# The generated serializers are looked up reflectively by the runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tanasi.streamflix.**$$serializer { *; }
-keepclassmembers class com.tanasi.streamflix.** {
    *** Companion;
}
-keepclasseswithmembers class com.tanasi.streamflix.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Media3 / ExoPlayer ------------------------------------------------------
# Optional extensions are loaded by name; the app does not ship them, but the
# library references them.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.datasource.** { *; }

# --- OkHttp ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
