# The resolver reflectively initializes a few upstream provider objects and
# Retrofit/Jsoup/Gson model classes are parsed at runtime.
-keep class com.streamflixreborn.streamflix.providers.** { *; }
-keep class com.streamflixreborn.streamflix.extractors.** { *; }
-keep class com.streamflixreborn.streamflix.models.** { *; }
-keep class com.streamflixreborn.streamflix.utils.TMDb3** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-dontwarn org.mozilla.javascript.**
-dontwarn org.chromium.net.**
