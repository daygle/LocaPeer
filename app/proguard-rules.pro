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

# OSMDroid
-keep class org.osmdroid.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
