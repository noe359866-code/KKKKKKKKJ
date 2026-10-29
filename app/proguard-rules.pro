# ---------------------------------------------------------------------------
# Reglas de R8 para el APK de release
# ---------------------------------------------------------------------------
# Objetivo: ofuscar todo lo ofuscable y dejar intacto únicamente lo que se
# resuelve por reflexión en tiempo de ejecución.
#
# Regla de oro: no hay `-keep class paquete.**`. Un keep de paquete deja el
# código sin ofuscar y además impide que R8 elimine lo que no se usa; es lo que
# hincha los APK. Las librerías (OkHttp, Coil, Media3, kotlinx.serialization)
# publican sus propias *consumer rules* dentro del AAR, así que aquí sólo se
# añade lo que es específico de esta app.
#
# Comprobación después del build:
#   app/build/outputs/mapping/release/mapping.txt     → nombres renombrados
#   app/build/outputs/mapping/release/usage.txt       → lo que R8 eliminó
#   app/build/outputs/mapping/release/seeds.txt       → lo que se conservó
# ---------------------------------------------------------------------------

# Atributos que el runtime consulta: anotaciones (serialización), genéricos
# (Signature), clases internas y excepciones declaradas.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions

# Permite a R8 relajar visibilidades y optimizar entre clases; no cambia el
# comportamiento, sólo da margen al optimizador.
-allowaccessmodification

# Kotlin: `Metadata` describe los tipos para kotlin-reflect y para el propio
# compilador; renombrarlo rompe `copy()`/`equals()` generados por reflexión.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ---------------------------------------------------------------------------
# kotlinx.serialization (DTOs del protocolo Stremio)
# ---------------------------------------------------------------------------
# El compilador genera `Companion.serializer()` y clases `$$serializer` que el
# runtime instancia y cuyos descriptores incluyen el nombre de cada campo.
-keepclassmembers class com.tanasi.streamflix.** {
    *** Companion;
}
-keepclasseswithmembers class com.tanasi.streamflix.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tanasi.streamflix.**$$serializer { *; }

# `@SerialName` es el contrato con el JSON del backend: el nombre del campo Kotlin
# puede cambiar, el valor anotado no. Se conserva la anotación (para que el
# descriptor siga generándose) pero se permite ofuscar el nombre de la clase.
-keepclassmembers,allowobfuscation class com.tanasi.streamflix.** {
    @kotlinx.serialization.SerialName <fields>;
}

-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# Logs de depuración
# ---------------------------------------------------------------------------
# R8 sustituye la llamada por un nop y, al quedar el argumento sin usar, elimina
# también la construcción del mensaje. Resultado: ni trazas de desarrollo ni
# URLs firmadas en el APK final. `w`/`e` se conservan: son el único diagnóstico
# que tendrá el usuario de una TV.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# ---------------------------------------------------------------------------
# Media3 incluye consumer rules para sus renderers, sus vistas (PlayerView
# infla layouts por nombre) y DRM. Aquí sólo se silencian los módulos opcionales
# que no se empaquetan (castlos, extensiones NDK, decodificadores AV1/VP9
# externos) para que R8 no avise de clases ausentes.
-dontwarn androidx.media3.**

# ---------------------------------------------------------------------------
# OkHttp / Okio / Coil
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Chaquopy / Python embebido
# ---------------------------------------------------------------------------
# El runtime se usa desde JNI y carga módulos por nombre. Se conserva completo
# para que R8 no elimine el puente que ejecuta los scrapers dentro de la APK.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ---------------------------------------------------------------------------
# Componentes del manifest
# ---------------------------------------------------------------------------
# El framework instancia estas clases **por nombre** y llama al constructor sin
# argumentos. Los miembros no se tocan: no hay reflexión sobre ellos.
-keep class com.tanasi.streamflix.MainActivity { <init>(); }
-keep class com.tanasi.streamflix.StreamflixApp { <init>(); }
-keep class com.tanasi.streamflix.ui.player.PlayerActivity { <init>(); }
