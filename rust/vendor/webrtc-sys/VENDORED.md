# Vendored webrtc-sys

This is `webrtc-sys` **0.3.53** from crates.io (LiveKit's bindings, pulled in by `libwebrtc =
"0.3.51"`), copied from the registry source minus `.cargo-ok` and `.cargo_vcs_info.json`, and used
instead of the published crate through `rust/Cargo.toml`:

```toml
[patch.crates-io]
webrtc-sys = { path = "vendor/webrtc-sys" }
```

## What differs from upstream 0.3.53

One function. `git diff --no-index <registry>/webrtc-sys-0.3.53 rust/vendor/webrtc-sys` shows it.

**`src/adm_proxy.cpp`**, `AdmProxy::Terminate()`: no longer terminates the sub-ADMs; they live as
long as the proxy and are terminated in `~AdmProxy`, as upstream issue
[livekit/rust-sdks#1468](https://github.com/livekit/rust-sdks/issues/1468) proposes. WebRTC
terminates the ADM when the last peer connection closes and calls the no-op `Init()` for the next
one; the synthetic ADM's 10 ms pumping task died with `Terminate`, so upstream never pulled remote
audio again: once every connection had closed (leaving a voice channel, or a private call ending),
every voice received played nothing until the app restarted. The desktop's own vendored copy
(`ciranda/client/vendor/webrtc-sys`, 0.3.48) carries the same fix; it cannot be used here as is,
since it also drops the Android setup that lives in `webrtc-sys-build`.

`voice_plays_after_a_private_call` (in the desktop's `media/rtc.rs`, compiled into this crate)
covers it: it fails without this change.

Nothing else is changed; the build script and the Android setup are upstream's.

## Upgrading webrtc-sys

1. Copy the new version from the registry over this directory, keeping this file.
2. Re-apply the `AdmProxy::Terminate` change unless #1468 is fixed by then (run
   `cargo test voice_plays_after_a_private_call` without it to see).
3. `cargo test` and `./gradlew assembleDebug`.

To drop the vendoring, remove the `[patch.crates-io]` lines and this directory.
