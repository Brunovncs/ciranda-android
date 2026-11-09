//! A voice-channel participant with no window, for trying the app's voice end to end: signs in,
//! joins a voice channel, publishes a 440 Hz tone, and listens to everyone else, printing how
//! loud each one arrives. Uses the desktop's own core and WebRTC code, as the app does.
//!
//! cargo run --example voicebot -- http://127.0.0.1:8580 gabriel harmony1 <channelId> [seconds]

use ciranda::core::api::Api;
use ciranda::core::realtime::{Event, Realtime, Scope};
use ciranda::core::types::*;
use ciranda::media::rtc::{self, Track};
use futures::StreamExt;
use libwebrtc::audio_source::AudioSourceOptions;
use libwebrtc::audio_source::native::NativeAudioSource;
use libwebrtc::audio_stream::native::NativeAudioStream;
use libwebrtc::peer_connection_factory::native::PeerConnectionFactoryExt;
use serde_json::json;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (base, nick, pass) = (a[1].clone(), a[2].clone(), a[3].clone());
    let channel: i64 = a[4].parse().expect("channel id");
    let seconds: u64 = a.get(5).and_then(|s| s.parse().ok()).unwrap_or(60);
    let rt = ciranda::core::runtime();
    rt.block_on(async move {
        let api = Api::new();
        api.set_server(&base, "");
        let auth = api.login(&nick, &pass, None).await.expect("login");
        api.set_token(&auth.token);
        let me = auth.user.id;
        let ice = api.session(&nick, false).await.map(|s| s.ice_servers).unwrap_or_default();
        let (tx, rx) = async_channel::unbounded();
        let socket = Realtime::start(ciranda::core::runtime().handle(), api.clone(), Scope::Full, tx);
        while let Ok(ev) = rx.recv().await {
            if matches!(ev, Event::Up(_)) {
                break;
            }
        }
        let joined = socket.request("voice:join", json!({ "channelId": channel, "muted": false, "deafened": false })).await.expect("join");
        let tokens: VoiceTokens = serde_json::from_value(joined.clone()).expect("tokens");
        println!("joined, slot {:?}", joined.get("mid"));

        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48_000, 1, 0);
        let track = rtc::factory().create_audio_track("mic", source.clone());
        let link = rtc::publish(&api, &tokens.publish.voice, &ice, Some((track, 32_000)), None, None).await.expect("publish");
        println!("publishing: connected = {}", link.connected(Duration::from_secs(10)).await);
        tokio::spawn(async move {
            let mut every = tokio::time::interval(Duration::from_millis(10));
            let mut n = 0u64;
            loop {
                every.tick().await;
                let data: Vec<i16> = (0..480)
                    .map(|i| {
                        let t = (n * 480 + i) as f32 / 48_000.;
                        ((t * 440. * std::f32::consts::TAU).sin() * 0.25 * i16::MAX as f32) as i16
                    })
                    .collect();
                n += 1;
                let frame = libwebrtc::audio_frame::AudioFrame {
                    data: data.into(),
                    sample_rate: 48_000,
                    num_channels: 1,
                    samples_per_channel: 480,
                };
                let _ = source.capture_frame(&frame).await;
            }
        });
        tokio::time::sleep(Duration::from_millis(800)).await;
        let _ = socket.request("voice:publishing", json!({ "channelId": channel, "kind": "v", "on": true })).await;
        println!("announced");

        // Everyone else in the channel: who publishes, and how loud they arrive.
        let roster: Vec<Member> = serde_json::from_value(joined["roster"].clone()).unwrap_or_default();
        let mut watching = Vec::new();
        let token = url::form_urlencoded::byte_serialize(tokens.token.as_bytes()).collect::<String>();
        for m in roster.iter().filter(|m| m.user_id != me && m.publishes("v")) {
            let url = format!("{}/{}/whep?token={token}", tokens.whep_base.trim_end_matches('/'), Place::Channel(channel).path(m.mid, "v"));
            let peak = Arc::new(AtomicU64::new(0));
            let frames = Arc::new(AtomicU64::new(0));
            let (p, f) = (peak.clone(), frames.clone());
            let link = rtc::watch(&api, &url, &ice, false, None, move |track| {
                if let Track::Audio(a) = track {
                    let (p, f) = (p.clone(), f.clone());
                    tokio::spawn(async move {
                        let mut s = NativeAudioStream::new(a, 48_000, 1);
                        while let Some(frame) = s.next().await {
                            let loudest = frame.data.iter().map(|x| x.unsigned_abs() as u64).max().unwrap_or(0);
                            p.fetch_max(loudest, Ordering::Relaxed);
                            f.fetch_add(1, Ordering::Relaxed);
                        }
                    });
                }
            })
            .await;
            println!("watching {} (slot {}): {}", m.nickname, m.mid, if link.is_ok() { "ok" } else { "failed" });
            watching.push((m.nickname.clone(), peak, frames, link));
        }
        for s in 0..seconds {
            tokio::time::sleep(Duration::from_secs(1)).await;
            for (name, peak, frames, _) in &watching {
                println!("t={s}s {name}: {} frames, peak {}", frames.swap(0, Ordering::Relaxed), peak.swap(0, Ordering::Relaxed));
            }
            while let Ok(ev) = rx.try_recv() {
                if let Event::Push(v) = ev
                    && v["type"] == "voice:roster"
                {
                    let names: Vec<String> =
                        v["roster"].as_array().into_iter().flatten().map(|m| format!("{}{:?}", m["nickname"], m["publishing"])).collect();
                    println!("roster: {}", names.join(", "));
                }
            }
        }
        let _ = socket.request("voice:leave", json!({ "channelId": channel })).await;
        link.close();
        rtc::flush_hang_ups(Duration::from_secs(1)).await;
        println!("left");
    });
}
