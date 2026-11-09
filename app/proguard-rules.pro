# Called from Rust through JNI, so invisible to R8.
-keep class app.ciranda.android.Native { *; }
-keep class livekit.org.** { *; }
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
-dontwarn livekit.org.**
