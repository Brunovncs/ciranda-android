//! LiveKit's WebRTC calls back into Java (`livekit.org.webrtc.*`), whose native halves live in
//! the static libwebrtc. webrtc-sys asks for them to be kept and exported, but a dependency's link
//! arguments never reach the final library, so this one asks again for the cdylib itself.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        webrtc_sys_build::configure_jni_symbols().expect("libwebrtc's JNI symbols");
    }
}
