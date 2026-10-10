# Release shrinking is currently disabled in app/build.gradle.kts. Keep these rules
# ready for a separately tested R8 enablement; they do not change today's release output.
#
# Keep runtime annotations, generic signatures, and line information for diagnostics.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,SourceFile,LineNumberTable

# Room entities, converters, database subclasses, and generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.example.data.database.** { *; }
-keep class com.example.data.dao.** { *; }
-keep class com.example.data.model.** { *; }
-keep @androidx.room.Entity class * { *; }

# Moshi Kotlin reflection/codegen adapters and JSON field names.
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class **JsonAdapter { *; }

# Google Tink / Android Keystore integration used for local message and session crypto.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.integration.android.** { *; }

# The app currently uses OkHttp + direct PostgREST calls. It does not currently depend on
# the Supabase Kotlin SDK, Ktor, or kotlinx.serialization, so no speculative rules are added
# for those libraries.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement


# Google Tink cryptographic classes used by local message/session encryption.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Truecaller SDK callback and partner model classes.
-keep class com.truecaller.android.sdk.** { *; }
-dontwarn com.truecaller.android.sdk.**

# Supabase DTOs and kotlinx.serialization-annotated fields (including future SDK usage).
-keep class io.github.jan.supabase.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
