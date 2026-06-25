# Keep Nostr serialization models
-keep class com.locapeer.nostr.** { *; }
-keep class com.locapeer.beacon.HeartbeatPayload { *; }
-keep class com.locapeer.invite.InviteData { *; }
-keep class com.locapeer.invite.InviteResponse { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.locapeer.**$$serializer { *; }
-keepclassmembers class com.locapeer.** {
    *** Companion;
}
-keepclasseswithmembers class com.locapeer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# secp256k1
-keep class fr.acinq.secp256k1.** { *; }

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# OSMDroid
-keep class org.osmdroid.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
