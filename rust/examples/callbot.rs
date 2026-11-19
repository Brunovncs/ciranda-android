//! The other side of a private call, with no window, for trying the app's calls end to end: signs
//! in (making the account if it has none), makes its key, and either waits to be rung and answers,
//! or rings someone; then publishes a 660 Hz tone and prints how loud the other person arrives.
//! Both ways the media is sealed with the call's key, as the apps seal it, so sound that arrives
//! at all is sound that opened. Uses the desktop's own `dm::Vault`, core and WebRTC code.
//!
//! cargo run --example callbot -- http://127.0.0.1:8580 robo robo1234 answer [seconds]
//! cargo run --example callbot -- http://127.0.0.1:8580 robo robo1234 decline [seconds]
//! cargo run --example callbot -- http://127.0.0.1:8580 robo robo1234 dial <their nickname> [seconds]
//!
//! Dialing, it gives up after `seconds` if nobody answers, which the other side sees as missed.

use ciranda::core::api::Api;
use ciranda::core::keystore::KeyFile;
use ciranda::core::realtime::{Event, Realtime, Scope};
use ciranda::core::types::*;
use ciranda::dm::{Next, Vault};
use ciranda::media::rtc::{self, FrameKey, Link, Track};
use futures::StreamExt;
use libwebrtc::audio_source::AudioSourceOptions;
use libwebrtc::audio_source::native::NativeAudioSource;
use libwebrtc::audio_stream::native::NativeAudioStream;
use libwebrtc::peer_connection_factory::native::PeerConnectionFactoryExt;
use serde_json::{Value, json};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (base, nick, pass, mode) = (a[1].clone(), a[2].clone(), a[3].clone(), a[4].clone());
    let peer_nick = (mode == "dial").then(|| a[5].clone());
    let rest = if mode == "dial" { 6 } else { 5 };
    let seconds: u64 = a.get(rest).and_then(|s| s.parse().ok()).unwrap_or(60);
    // Its own key file, apart from any client's.
    let dir = std::env::temp_dir().join(format!("ciranda-callbot-{nick}"));
    // SAFETY: before any thread reads the environment.
    unsafe { std::env::set_var("CIRANDA_DATA_DIR", &dir) };
    ciranda::core::runtime().block_on(async move {
        let api = Api::new();
        api.set_server(&base, "");
        let auth = match api.register(&nick, &pass, None).await {
            Ok(a) => a,
            Err(_) => api.login(&nick, &pass, None).await.expect("login"),
        };
        api.set_token(&auth.token);
        let me = auth.user.id;
        let ice = api.session(&nick, false).await.map(|s| s.ice_servers).unwrap_or_default();
        let mut vault = Vault::new(KeyFile::open(&base, &nick), me);
        if vault.on_keys(api.keys().await.expect("keys")) == Next::Create {
            let (identity, recovery, wrapped) = Vault::create();
            let key = api.publish_key(&identity.public_b64(), &wrapped).await.expect("publish key");
            vault.adopt(key, identity, &recovery, true);
        }
        println!("{nick} (user {me}): key ready");

        let (tx, rx) = async_channel::unbounded();
        let socket = Realtime::start(ciranda::core::runtime().handle(), api.clone(), Scope::Full, tx);
        let mut waiting: Vec<CallInfo> = Vec::new();
        while let Ok(ev) = rx.recv().await {
            if let Event::Up(snap) = ev {
                waiting = snap.map(|s| s.calls).unwrap_or_default();
                break;
            }
        }
        println!("socket up");

        // The call: rung here, or rung from here.
        let (conversation, key) = match peer_nick {
            Some(peer_nick) => {
                let peer = loop {
                    let users = api.users().await.expect("users");
                    if let Some(u) = users.iter().find(|u| u.nickname.eq_ignore_ascii_case(&peer_nick)) {
                        break u.id;
                    }
                    println!("waiting for {peer_nick} to exist…");
                    tokio::time::sleep(Duration::from_secs(3)).await;
                };
                let conversation = api.open_dm(peer).await.expect("open conversation").id;
                while !vault.peer_has_key(peer) {
                    vault.learn(api.keys().await.expect("keys").keys);
                    if !vault.peer_has_key(peer) {
                        println!("waiting for {peer_nick}'s key…");
                        tokio::time::sleep(Duration::from_secs(3)).await;
                    }
                }
                let mut key = ciranda::core::crypto::random::<32>();
                let sealed = vault.seal_call_key(conversation, peer, &key).expect("seal the call's key");
                let reply = socket
                    .request(
                        "call:start",
                        json!({
                            "conversationId": conversation,
                            "sealedKey": sealed.text,
                            "senderKey": sealed.sender_key,
                            "recipientKey": sealed.recipient_key,
                        }),
                    )
                    .await;
                match reply {
                    Ok(v) => {
                        let info: CallInfo = serde_json::from_value(v["call"].clone()).expect("call");
                        if info.caller_id != me {
                            println!("they were calling at the same moment: their call, their key");
                            key = vault.open_call_key(&info).expect("their key");
                        }
                        println!("ringing {peer_nick} in conversation {conversation}");
                    }
                    Err(e) => {
                        println!("could not call: {e}");
                        socket.stop();
                        return;
                    }
                }
                (conversation, key)
            }
            None => {
                println!("waiting to be rung…");
                let info = loop {
                    if let Some(c) = waiting.drain(..).find(|c| c.state == CallPhase::Ringing && c.callee_id == me) {
                        break c;
                    }
                    let Ok(Event::Push(v)) = rx.recv().await else { continue };
                    if v["type"] == "call:state"
                        && let Ok(c) = serde_json::from_value::<CallInfo>(v["call"].clone())
                        && c.state == CallPhase::Ringing
                        && c.callee_id == me
                    {
                        break c;
                    }
                };
                println!("rung by user {} in conversation {}", info.caller_id, info.conversation_id);
                if mode == "decline" {
                    tokio::time::sleep(Duration::from_secs(2)).await;
                    let r = socket.request("call:hang-up", json!({ "conversationId": info.conversation_id })).await;
                    println!("declined: {}", if r.is_ok() { "ok" } else { "failed" });
                    tokio::time::sleep(Duration::from_secs(1)).await;
                    socket.stop();
                    return;
                }
                vault.learn(api.lookup_keys(&vault.unknown([info.sender_key, info.recipient_key])).await.unwrap_or_default());
                vault.pin(info.caller_id);
                let key = vault.open_call_key(&info).expect("the call's key did not open");
                tokio::time::sleep(Duration::from_secs(2)).await;
                socket.request("call:answer", json!({ "conversationId": info.conversation_id })).await.expect("answer");
                println!("answered");
                (info.conversation_id, key)
            }
        };
        let place = Place::Call(conversation);
        let frame_key = FrameKey::new(&key);

        let joined = socket.request("call:join", place.with(json!({ "muted": false, "deafened": false }))).await.expect("join");
        let tokens: VoiceTokens = serde_json::from_value(joined.clone()).expect("tokens");
        println!("joined, slot {:?}", joined.get("mid"));

        let source = NativeAudioSource::new(AudioSourceOptions::default(), 48_000, 1, 0);
        let track = rtc::factory().create_audio_track("mic", source.clone());
        let mic = rtc::publish(&api, &tokens.publish.voice, &ice, Some((track, 32_000)), None, Some(&frame_key)).await.expect("publish");
        println!("publishing (sealed): connected = {}", mic.connected(Duration::from_secs(10)).await);
        tokio::spawn(async move {
            let mut every = tokio::time::interval(Duration::from_millis(10));
            let mut n = 0u64;
            loop {
                every.tick().await;
                let data: Vec<i16> = (0..480)
                    .map(|i| {
                        let t = (n * 480 + i) as f32 / 48_000.;
                        ((t * 660. * std::f32::consts::TAU).sin() * 0.25 * i16::MAX as f32) as i16
                    })
                    .collect();
                n += 1;
                let frame =
                    libwebrtc::audio_frame::AudioFrame { data: data.into(), sample_rate: 48_000, num_channels: 1, samples_per_channel: 480 };
                let _ = source.capture_frame(&frame).await;
            }
        });
        tokio::time::sleep(Duration::from_millis(800)).await;
        let _ = socket.request("voice:publishing", place.with(json!({ "kind": "v", "on": true }))).await;
        println!("announced");

        // The other person, once they publish: how loud they arrive, after opening.
        let token = url::form_urlencoded::byte_serialize(tokens.token.as_bytes()).collect::<String>();
        let peak = Arc::new(AtomicU64::new(0));
        let frames = Arc::new(AtomicU64::new(0));
        let mut watching: Option<(i64, Link)> = None;
        let mut roster: Vec<Member> = serde_json::from_value(joined["roster"].clone()).unwrap_or_default();
        let until = Instant::now() + Duration::from_secs(seconds);
        let mut tick = tokio::time::interval(Duration::from_secs(1));
        let mut t = 0;
        let mut over = false;
        while Instant::now() < until && !over {
            if watching.is_none()
                && let Some(m) = roster.iter().find(|m| m.user_id != me && m.publishes("v"))
            {
                let url = format!("{}/{}/whep?token={token}", tokens.whep_base.trim_end_matches('/'), place.path(m.mid, "v"));
                let (p, f) = (peak.clone(), frames.clone());
                let link = rtc::watch(&api, &url, &ice, false, Some(&frame_key), move |track| {
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
                println!("watching {} (slot {}, sealed): {}", m.nickname, m.mid, if link.is_ok() { "ok" } else { "failed" });
                if let Ok(link) = link {
                    watching = Some((m.mid, link));
                }
            }
            tokio::select! {
                _ = tick.tick() => {
                    if watching.is_some() {
                        println!("t={t}s them: {} frames, peak {}", frames.swap(0, Ordering::Relaxed), peak.swap(0, Ordering::Relaxed));
                    }
                    t += 1;
                }
                ev = rx.recv() => {
                    let Ok(Event::Push(v)) = ev else { continue };
                    match v.get("type").and_then(Value::as_str) {
                        Some("call:roster") if v["conversationId"] == conversation => {
                            roster = serde_json::from_value(v["roster"].clone()).unwrap_or_default();
                            let names: Vec<String> = roster.iter().map(|m| format!("{}{:?}{}", m.nickname, m.publishing, if m.muted { " muted" } else { "" })).collect();
                            println!("roster: {}", names.join(", "));
                        }
                        Some("call:state") if v["call"]["conversationId"] == conversation => {
                            println!("call {}{}", v["call"]["state"], v["call"]["outcome"].as_str().map(|o| format!(" ({o})")).unwrap_or_default());
                            over = v["call"]["state"] == "ended";
                        }
                        _ => {}
                    }
                }
            }
        }
        if !over {
            let _ = socket.request("call:hang-up", json!({ "conversationId": conversation })).await;
            println!("hung up");
        }
        mic.close();
        if let Some((_, link)) = watching {
            link.close();
        }
        rtc::flush_hang_ups(Duration::from_secs(1)).await;
        socket.stop();
    });
}
