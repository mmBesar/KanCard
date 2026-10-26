# Keep rules for the release build (R8 shrinking).

# kotlinx.serialization looks up generated serializers by name at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mmbesar.kancard.**$$serializer { *; }
-keepclassmembers class com.mmbesar.kancard.** { *** Companion; }
-keepclasseswithmembers class com.mmbesar.kancard.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp optional dependencies that are not on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
