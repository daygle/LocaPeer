# Keep Nostr serialization models
-keep class com.locapeer.nostr.** { *; }
-keep class com.locapeer.beacon.HeartbeatPayload { *; }
-keep class com.locapeer.invite.InviteData { *; }
-keep class com.locapeer.invite.InviteResponse { *; }

# secp256k1
-keep class fr.acinq.secp256k1.** { *; }

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# MapLibre ships its own consumer rules; keep the JNI-facing classes in full.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
