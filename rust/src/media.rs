//! The desktop client's media files that need no window.

#[path = "../../ciranda/client/src/media/audio.rs"]
pub mod audio;
#[path = "../../ciranda/client/src/media/deepfilter.rs"]
pub mod deepfilter;
/// Only its name for the desktop's sound tap, which `audio` keeps out of the device lists: a
/// phone shares no system sound.
pub mod loopback;
#[path = "../../ciranda/client/src/media/rtc.rs"]
pub mod rtc;
