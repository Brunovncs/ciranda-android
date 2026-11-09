//! Ciranda for Android. The desktop client's own files do the work (`core`, the sound and the
//! WebRTC connections); what the desktop does with GPUI entities, keeping a session and a call
//! going, is done here for a Kotlin interface that drives it through `bridge`.

#[macro_use]
#[path = "../../ciranda/client/src/i18n.rs"]
pub mod i18n;

#[path = "../../ciranda/client/src/core/mod.rs"]
pub mod core;

pub mod media;

#[path = "../../ciranda/client/src/emoji.rs"]
pub mod emoji;
#[path = "../../ciranda/client/src/markdown.rs"]
pub mod markdown;

pub mod engine;

#[cfg(target_os = "android")]
mod bridge;
