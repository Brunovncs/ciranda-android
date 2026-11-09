//! Being in a voice channel: the desktop's `media/call.rs` and `media/voice.rs` in one, sound
//! only. Joining and leaving through the socket, keeping the media tokens fresh, mute and deafen,
//! your microphone published to your slot's `v` path over WHIP, and one WHEP subscription to
//! everyone else's, each playing through its own source in the mix at that person's volume.
//! Every async setup carries a generation, so a stale completion hangs up instead of attaching;
//! failures back off 1, 2, 4, 8 s; a subscription that goes quiet is replaced.

use super::session::Session;
use super::{Engine, Handle};
use crate::core::api::ApiError;
use crate::core::types::*;
use crate::media::audio::{self, Cue, MicGuard, Source, SourceKind};
use crate::media::rtc::{self, Link, Track};
use futures::StreamExt;
use libwebrtc::audio_source::AudioSourceOptions;
use libwebrtc::audio_source::native::NativeAudioSource;
use libwebrtc::audio_stream::native::NativeAudioStream;
use libwebrtc::peer_connection_factory::native::PeerConnectionFactoryExt;
use parking_lot::Mutex;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

/// How long a new connection gets to come up before it counts as failed.
const CONNECT_LIMIT: Duration = Duration::from_secs(10);
/// How long a subscription may go without a packet before it is replaced.
const STALL: Duration = Duration::from_secs(8);
/// A roster that hasn't caught up with an announcement yet doesn't repeat it before this.
const ANNOUNCE_AGAIN: Duration = Duration::from_secs(3);
/// How long the microphone's connection may sit disconnected before it is published again.
const MIC_DISCONNECTED: Duration = Duration::from_secs(8);
/// After a reconnect everyone's state comes back in a rush that is not news.
const RESYNC_QUIET: Duration = Duration::from_secs(5);

const SPEAK_ON: f32 = 0.0075;
const SPEAK_OFF: f32 = 0.0035;

/// Per-person volumes (0..3.5) on the server of the call, by user.
pub fn volumes() -> &'static Mutex<HashMap<UserId, f32>> {
    static V: OnceLock<Mutex<HashMap<UserId, f32>>> = OnceLock::new();
    V.get_or_init(Default::default)
}

pub fn volume_of(user: UserId) -> f32 {
    volumes().lock().get(&user).copied().unwrap_or(1.)
}

pub fn set_volume(user: UserId, gain: f32) {
    volumes().lock().insert(user, gain);
}

static AUDIO_STARTED: AtomicBool = AtomicBool::new(false);

/// The audio engine, started the first time a call needs it.
fn audio_engine() -> &'static audio::Audio {
    AUDIO_STARTED.store(true, Ordering::Relaxed);
    audio::audio()
}

/// The audio engine if a call has started it; nothing is opened just to set it.
pub fn audio_started() -> Option<&'static audio::Audio> {
    AUDIO_STARTED.load(Ordering::Relaxed).then(audio::audio)
}

/// The old client's gate: 0 is off, 1..100 maps to an RMS threshold.
pub fn gate_threshold(sensitivity: u32) -> f32 {
    if sensitivity == 0 { 0. } else { 0.0008 * 60f32.powf(sensitivity as f32 / 100.) }
}

fn next_generation() -> u64 {
    static N: AtomicU64 = AtomicU64::new(1);
    N.fetch_add(1, Ordering::Relaxed)
}

fn not_connected() -> ApiError {
    ApiError {
        status: 0,
        code: ErrorCode::MediaError,
        message: tr!("The connection to the media server never came up.", "A conexão com o servidor de mídia não se estabeleceu.").into(),
        body: Value::Null,
    }
}

fn cue(cue: Cue, volume: Option<f32>) {
    if let Some(v) = volume {
        audio::cue(cue, v);
    }
}

/// Spacing between attempts after failures: 1, 2, 4, then 8 s.
#[derive(Clone, Copy, Debug, Default)]
struct Backoff {
    failures: u32,
    last: Option<Instant>,
}

impl Backoff {
    fn delay(failures: u32) -> Duration {
        match failures {
            0 => Duration::ZERO,
            n => Duration::from_secs(1 << (n - 1).min(3)),
        }
    }

    fn ready(&self, now: Instant) -> bool {
        self.last.is_none_or(|t| now >= t + Backoff::delay(self.failures))
    }

    fn fail(&mut self, now: Instant) {
        self.failures += 1;
        self.last = Some(now);
    }

    fn reset(&mut self) {
        *self = Backoff::default();
    }
}

/// One WHEP subscription's life: the attempt in flight, the backoff, and the packet count that
/// tells a live stream from a stalled one.
#[derive(Default)]
struct Watch {
    link: Option<Arc<Link>>,
    generation: u64,
    connecting: bool,
    backoff: Backoff,
    packets: u64,
    quiet_since: Option<Instant>,
    pumps: Vec<tokio::task::JoinHandle<()>>,
}

impl Watch {
    fn due(&self, now: Instant) -> bool {
        self.link.is_none() && !self.connecting && self.backoff.ready(now)
    }

    fn begin(&mut self) -> u64 {
        self.hang_up();
        self.connecting = true;
        self.generation = next_generation();
        self.generation
    }

    /// Whether the attempt was the current one and attached.
    fn finish(&mut self, generation: u64, result: Result<Link, ApiError>, pumps: Vec<tokio::task::JoinHandle<()>>) -> Option<ApiError> {
        if generation != self.generation || !self.connecting {
            pumps.iter().for_each(|p| p.abort());
            return None;
        }
        self.connecting = false;
        match result {
            Ok(link) => {
                self.link = Some(Arc::new(link));
                self.pumps = pumps;
                self.backoff.reset();
                self.packets = 0;
                self.quiet_since = None;
                None
            }
            Err(e) => {
                pumps.iter().for_each(|p| p.abort());
                self.backoff.fail(Instant::now());
                Some(e)
            }
        }
    }

    fn stalled(&mut self, generation: u64, packets: u64, now: Instant) -> bool {
        if generation != self.generation || self.link.is_none() {
            return false;
        }
        if packets > self.packets {
            self.packets = packets;
            self.quiet_since = None;
            return false;
        }
        now.duration_since(*self.quiet_since.get_or_insert(now)) >= STALL
    }

    fn hang_up(&mut self) {
        self.pumps.drain(..).for_each(|p| p.abort());
        if let Some(l) = self.link.take() {
            l.close();
        }
        self.connecting = false;
        self.packets = 0;
        self.quiet_since = None;
    }
}

impl Drop for Watch {
    fn drop(&mut self) {
        self.hang_up();
    }
}

struct Peer {
    user: UserId,
    source: Arc<Source>,
    watch: Watch,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CallState {
    Joining,
    Connected,
    Reconnecting,
}

pub struct Call {
    /// Unique to this call, so a late answer for an earlier one is dropped.
    pub id: u64,
    pub channel: ChannelId,
    pub state: CallState,
    tokens: Option<VoiceTokens>,
    refresh_at: Option<Instant>,
    mid: Option<i64>,
    pub muted: bool,
    pub deafened: bool,
    muted_by_deafen: bool,
    /// An admin or the channel's lock holds you muted.
    force_muted: bool,
    password: Option<String>,
    cue_roster: Option<Vec<Member>>,
    quiet_until: Option<Instant>,
    pub left: bool,
    /// The media has started (the first join answered).
    started: bool,
    mic: Option<Arc<Link>>,
    mic_readable: bool,
    mic_attempt: Option<u64>,
    mic_backoff: Backoff,
    mic_guard: Option<MicGuard>,
    peers: HashMap<i64, Peer>,
    announced: HashMap<&'static str, (bool, Instant)>,
    speaking: HashSet<UserId>,
    envelopes: HashMap<UserId, (f32, Instant)>,
    ping_ms: Option<u32>,
    roster: Vec<Member>,
    /// What went wrong, for the interface; the call is over.
    pub error: Option<String>,
}

impl Call {
    pub fn new(channel: ChannelId, password: Option<String>, muted: bool, deafened: bool) -> Call {
        Call {
            id: next_generation(),
            channel,
            state: CallState::Joining,
            tokens: None,
            refresh_at: None,
            mid: None,
            muted,
            deafened,
            muted_by_deafen: false,
            force_muted: false,
            password,
            cue_roster: None,
            quiet_until: None,
            left: false,
            started: false,
            mic: None,
            mic_readable: false,
            mic_attempt: None,
            mic_backoff: Backoff::default(),
            mic_guard: None,
            peers: HashMap::new(),
            announced: HashMap::new(),
            speaking: HashSet::new(),
            envelopes: HashMap::new(),
            ping_ms: None,
            roster: Vec::new(),
            error: None,
        }
    }

    fn place(&self) -> Place {
        Place::Channel(self.channel)
    }

    /// What the interface draws of the call.
    pub fn state(&self, session: Option<&Session>) -> Value {
        let me = session.map(|s| s.me.id);
        let volumes: serde_json::Map<String, Value> =
            self.roster.iter().filter(|m| Some(m.user_id) != me).map(|m| (m.user_id.to_string(), json!(volume_of(m.user_id)))).collect();
        json!({
            "channelId": self.channel,
            "channel": session.and_then(|s| s.channel(self.channel)).map(|c| c.name.clone()),
            "state": match self.state {
                CallState::Joining => "joining",
                CallState::Connected => "connected",
                CallState::Reconnecting => "reconnecting",
            },
            "muted": self.muted,
            "deafened": self.deafened,
            "forceMuted": self.force_muted,
            "speaking": self.speaking,
            "pingMs": self.ping_ms,
            "micFailed": self.mic.is_none() && !self.force_muted && self.mic_backoff.failures > 0,
            "volumes": volumes,
        })
    }

    pub fn send_join(&mut self, session: &Session, handle: &Handle) {
        let mut payload = json!({ "channelId": self.channel, "muted": self.muted, "deafened": self.deafened });
        if let Some(p) = &self.password {
            payload["password"] = json!(p);
        }
        let reply = session.request("voice:join", payload);
        let id = self.id;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let reply = reply.await;
            h.update(move |e| e.joined(id, reply));
        });
    }

    pub fn refresh_due(&self, now: Instant) -> bool {
        self.refresh_at.is_some_and(|t| now >= t) && !self.left
    }

    fn schedule_refresh(&mut self) {
        let every =
            self.tokens.as_ref().map(|t| Duration::from_millis((t.expires_in_ms / 2).max(5000))).unwrap_or(Duration::from_secs(300));
        self.refresh_at = Some(Instant::now() + every);
    }

    pub fn refresh_tokens(&mut self, session: Option<&Session>, handle: &Handle) {
        self.schedule_refresh();
        let Some(s) = session else { return };
        let reply = s.request("voice:refresh", self.place().payload());
        let id = self.id;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            if let Ok(v) = reply.await
                && let Ok(tokens) = serde_json::from_value::<VoiceTokens>(v)
            {
                h.update(move |e| {
                    if let Some(c) = e.call.as_mut().filter(|c| c.id == id) {
                        c.tokens = Some(tokens);
                    }
                });
            }
        });
    }

    /// The socket is back: join again. Subscriptions to others stay; what you publish stays too
    /// if the slot is the same.
    pub fn reconnected(&mut self, session: Option<&Session>, handle: &Handle) {
        self.state = CallState::Reconnecting;
        self.cue_roster = None;
        self.quiet_until = Some(Instant::now() + RESYNC_QUIET);
        if let Some(s) = session {
            self.send_join(s, handle);
        }
    }

    pub fn socket_down(&mut self) {
        if self.state == CallState::Connected {
            self.state = CallState::Reconnecting;
        }
    }

    pub fn on_roster(&mut self, session: Option<&Session>, handle: &Handle, cues: Option<f32>) {
        let Some(s) = session else { return };
        let roster = s.rosters.get(&self.channel).cloned().unwrap_or_default();
        let me = s.me.id;
        if let Some(mine) = roster.iter().find(|m| m.user_id == me) {
            self.mid = Some(mine.mid);
            self.force_muted = mine.silenced();
            if mine.silenced() && !self.muted {
                self.muted = true;
                cue(Cue::Mute, cues);
                self.apply_mute_locally();
            }
        }
        let quiet = self.quiet_until.is_some_and(|t| Instant::now() < t);
        if let Some(before) = &self.cue_roster
            && !quiet
        {
            for c in roster_cues(before, &roster, me) {
                cue(c, cues);
            }
        }
        self.cue_roster = Some(roster.clone());
        if self.started {
            self.sync(roster, s, handle);
        } else {
            self.roster = roster;
        }
    }

    /// Brings the subscriptions in line with a new roster.
    fn sync(&mut self, roster: Vec<Member>, s: &Session, handle: &Handle) {
        let me = s.me.id;
        self.roster = roster;
        let wanted: HashMap<i64, UserId> =
            self.roster.iter().filter(|m| m.user_id != me && m.publishes("v")).map(|m| (m.mid, m.user_id)).collect();
        let gone: Vec<i64> = self.peers.iter().filter(|(mid, p)| wanted.get(mid) != Some(&p.user)).map(|(mid, _)| *mid).collect();
        for mid in gone {
            if let Some(p) = self.peers.remove(&mid) {
                close_peer(p);
            }
        }
        let mut delay = 0u64;
        for (mid, user) in wanted {
            if self.peers.contains_key(&mid) {
                continue;
            }
            let source = Source::new(SourceKind::Voice, volume_of(user), false);
            audio_engine().mixer.add(source.clone());
            self.peers.insert(mid, Peer { user, source, watch: Watch::default() });
            self.connect_peer(mid, Duration::from_millis(delay), s, handle);
            delay += 75;
        }
        self.reconcile(Some(s), handle);
    }

    fn peer_url(&self, mid: i64) -> Option<String> {
        let t = self.tokens.as_ref()?;
        let token = url::form_urlencoded::byte_serialize(t.token.as_bytes()).collect::<String>();
        Some(format!("{}/{}/whep?token={token}", t.whep_base.trim_end_matches('/'), self.place().path(mid, "v")))
    }

    fn connect_peer(&mut self, mid: i64, after: Duration, s: &Session, handle: &Handle) {
        let Some(url) = self.peer_url(mid) else {
            return;
        };
        let Some(peer) = self.peers.get_mut(&mid) else {
            return;
        };
        let generation = peer.watch.begin();
        let source = peer.source.clone();
        let api = s.api.clone();
        let ice = s.ice_servers.clone();
        let id = self.id;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            tokio::time::sleep(after).await;
            let pumps: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>> = Default::default();
            let slot = pumps.clone();
            let link = rtc::watch(&api, &url, &ice, false, None, move |track| {
                if let Track::Audio(a) = track {
                    let src = source.clone();
                    slot.lock().push(crate::core::runtime().spawn(async move {
                        let mut stream = NativeAudioStream::new(a, audio::RATE as i32, 1);
                        while let Some(frame) = stream.next().await {
                            src.push_i16(&frame.data, frame.num_channels as usize);
                        }
                    }));
                }
            })
            .await;
            let pumps = std::mem::take(&mut *pumps.lock());
            h.update(move |e| {
                let Some(c) = e.call.as_mut().filter(|c| c.id == id) else {
                    pumps.iter().for_each(|p| p.abort());
                    return;
                };
                let Some(peer) = c.peers.get_mut(&mid) else {
                    pumps.iter().for_each(|p| p.abort());
                    return;
                };
                match peer.watch.finish(generation, link, pumps) {
                    None => log::info!("voice from slot {mid}: connected or superseded"),
                    Some(err) => {
                        log::info!("voice from slot {mid}: {} ({})", err.message, err.code);
                        if err.code == ErrorCode::Unauthorized {
                            c.refresh_tokens(e.session.as_ref(), &e.handle);
                        }
                    }
                }
            });
        });
    }

    fn start_mic(&mut self, s: &Session, handle: &Handle) {
        let Some(url) = self.tokens.as_ref().map(|t| t.publish.voice.clone()) else {
            return;
        };
        let generation = next_generation();
        self.mic_attempt = Some(generation);
        let source = NativeAudioSource::new(AudioSourceOptions::default(), audio::RATE, 1, 0);
        let track = rtc::factory().create_audio_track("mic", source.clone());
        let api = s.api.clone();
        let ice = s.ice_servers.clone();
        let mid = self.mid;
        let id = self.id;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let link = match rtc::publish(&api, &url, &ice, Some((track, 32_000)), None, None).await {
                Ok(link) if link.connected(CONNECT_LIMIT).await => Ok(link),
                Ok(_) => Err(not_connected()),
                Err(e) => Err(e),
            };
            h.update(move |e| {
                let Some(c) = e.call.as_mut().filter(|c| c.id == id && c.mic_attempt == Some(generation)) else {
                    return;
                };
                c.mic_attempt = None;
                match link {
                    Ok(link) => {
                        log::info!("microphone published to slot {mid:?}");
                        audio_engine().set_mic_sink(Some(Box::new(move |pcm: &[i16]| {
                            let frame = libwebrtc::audio_frame::AudioFrame {
                                data: pcm.to_vec().into(),
                                sample_rate: audio::RATE,
                                num_channels: 1,
                                samples_per_channel: pcm.len() as u32,
                            };
                            let _ = futures::executor::block_on(source.capture_frame(&frame));
                        })));
                        let link = Arc::new(link);
                        c.mic = Some(link.clone());
                        c.mic_backoff.reset();
                        // Announced once the first packets have had time to reach the server,
                        // which only then lets anyone read the path.
                        let h = e.handle.clone();
                        crate::core::runtime().spawn(async move {
                            let start = Instant::now();
                            while link.packets_sent().await == 0 && start.elapsed() < Duration::from_secs(3) {
                                tokio::time::sleep(Duration::from_millis(50)).await;
                            }
                            let crossing = link.rtt_ms().await.unwrap_or(200).clamp(100, 1000);
                            tokio::time::sleep(Duration::from_millis(crossing as u64)).await;
                            h.update(move |e| {
                                let Some(c) = e.call.as_mut().filter(|c| c.id == id) else {
                                    return;
                                };
                                if c.mic.as_ref().is_some_and(|m| Arc::ptr_eq(m, &link)) {
                                    c.mic_readable = true;
                                    c.announce(e.session.as_ref(), &e.handle);
                                }
                            });
                        });
                    }
                    Err(err) => {
                        c.mic_backoff.fail(Instant::now());
                        log::warn!("microphone publish failed ({} in a row): {} ({})", c.mic_backoff.failures, err.message, err.code);
                        if err.code == ErrorCode::Unauthorized {
                            c.refresh_tokens(e.session.as_ref(), &e.handle);
                        }
                    }
                }
                e.mark_voice();
            });
        });
    }

    fn drop_mic(&mut self) {
        if let Some(a) = audio_started() {
            a.set_mic_sink(None);
        }
        self.mic_readable = false;
        self.mic_attempt = None;
        if let Some(link) = self.mic.take() {
            link.close();
        }
    }

    /// Puts what you publish right, and what the server is told about it.
    fn reconcile(&mut self, session: Option<&Session>, handle: &Handle) {
        let Some(s) = session else { return };
        if !self.started {
            return;
        }
        let now = Instant::now();
        if self.mic.as_ref().is_some_and(|l| l.is_stuck(MIC_DISCONNECTED)) {
            log::info!("the microphone connection dropped; publishing again");
            self.drop_mic();
        }
        if self.force_muted && (self.mic.is_some() || self.mic_attempt.is_some()) {
            self.drop_mic();
        }
        if self.mic.is_none() && self.mic_attempt.is_none() && !self.force_muted && self.mic_backoff.ready(now) {
            self.start_mic(s, handle);
        }
        self.announce(session, handle);
    }

    fn announce(&mut self, session: Option<&Session>, handle: &Handle) {
        let Some(s) = session else { return };
        let me = s.me.id;
        let Some(mine) = self.roster.iter().find(|m| m.user_id == me && Some(m.mid) == self.mid) else {
            return;
        };
        let live = [("v", self.mic.is_some() && self.mic_readable), ("c", false), ("s", false)];
        let now = Instant::now();
        let fixes: Vec<(&'static str, bool)> =
            live.into_iter().filter(|(kind, on)| mine.publishing.iter().any(|p| p == kind) != *on).collect();
        for (kind, on) in fixes {
            if self.announced.get(kind).is_some_and(|&(was, at)| was == on && now.duration_since(at) < ANNOUNCE_AGAIN) {
                continue;
            }
            self.announced.insert(kind, (on, now));
            log::info!("telling the server {kind} is {}", if on { "on" } else { "off" });
            let reply = s.request("voice:publishing", self.place().with(json!({ "kind": kind, "on": on })));
            let id = self.id;
            let h = handle.clone();
            crate::core::runtime().spawn(async move {
                if let Err(err) = reply.await {
                    log::warn!("voice:publishing {kind} {on} failed: {err}");
                    h.update(move |e| {
                        if let Some(c) = e.call.as_mut().filter(|c| c.id == id) {
                            c.announced.remove(kind);
                        }
                    });
                }
            });
        }
    }

    /// Speaking rings; true when they changed.
    pub fn tick(&mut self, session: Option<&Session>) -> bool {
        let Some(s) = session else { return false };
        let Some(a) = audio_started() else {
            return false;
        };
        let now = Instant::now();
        let me = s.me.id;
        let mine = a.mic.level.take();
        let mut levels: Vec<(UserId, f32)> =
            self.peers.values().map(|p| (p.user, p.source.peak.take() * p.source.gain.get().min(1.))).collect();
        levels.push((me, if self.muted || !a.mic.open.load(Ordering::Relaxed) { 0. } else { mine }));
        let mut speaking = HashSet::new();
        for (user, level) in levels {
            let (env, held) = self.envelopes.entry(user).or_insert((0., now));
            *env = level.max(*env * 0.65);
            let was = self.speaking.contains(&user);
            if *env > SPEAK_ON {
                *held = now;
            }
            if *env > SPEAK_ON || (was && (*env > SPEAK_OFF || now.duration_since(*held) < Duration::from_millis(400))) {
                speaking.insert(user);
            }
        }
        if speaking != self.speaking {
            self.speaking = speaking;
            return true;
        }
        false
    }

    /// Whoever is due gets another attempt; once a second.
    pub fn retry(&mut self, session: Option<&Session>, handle: &Handle) -> bool {
        let Some(s) = session else { return false };
        if !self.started {
            return false;
        }
        let now = Instant::now();
        let due: Vec<i64> = self.peers.iter().filter(|(_, p)| p.watch.due(now)).map(|(mid, _)| *mid).collect();
        for mid in due {
            self.connect_peer(mid, Duration::ZERO, s, handle);
        }
        let failed = self.mic_backoff.failures;
        self.reconcile(session, handle);
        failed != self.mic_backoff.failures
    }

    /// Stalls and ping, which need stats; every four seconds.
    pub fn check(&self, handle: &Handle) {
        let links: Vec<(i64, u64, Arc<Link>)> =
            self.peers.iter().filter_map(|(mid, p)| Some((*mid, p.watch.generation, p.watch.link.clone()?))).collect();
        let mic = self.mic.clone();
        let id = self.id;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let mut counts = Vec::new();
            for (mid, generation, l) in links {
                counts.push((mid, generation, l.packets_received().await));
            }
            let rtt = match mic {
                Some(m) => m.rtt_ms().await,
                None => None,
            };
            h.update(move |e| {
                let Some(c) = e.call.as_mut().filter(|c| c.id == id) else {
                    return;
                };
                if c.ping_ms != rtt {
                    c.ping_ms = rtt;
                    e.mark_voice();
                }
                let Some(c) = e.call.as_mut() else { return };
                let now = Instant::now();
                for (mid, generation, n) in counts {
                    if c.peers.get_mut(&mid).is_some_and(|p| p.watch.stalled(generation, n, now)) {
                        log::info!("voice from slot {mid} went quiet; subscribing again");
                        if let Some(s) = e.session.as_ref() {
                            c.connect_peer(mid, Duration::ZERO, s, &e.handle);
                        }
                    }
                }
            });
        });
    }

    pub fn apply_volume(&self, user: UserId, gain: f32) {
        for p in self.peers.values().filter(|p| p.user == user) {
            p.source.gain.set(gain);
        }
    }

    pub fn set_muted(&mut self, muted: bool, session: Option<&Session>, settings: &crate::core::settings::Settings) {
        let cues = settings.voice_sounds.then_some(settings.sound_volume as f32 / 100.);
        if !muted && self.force_muted {
            return;
        }
        if !muted && self.deafened {
            self.deafened = false;
            cue(Cue::Undeafen, cues);
        } else if muted != self.muted {
            cue(if muted { Cue::Mute } else { Cue::Unmute }, cues);
        }
        self.muted = muted;
        self.muted_by_deafen = false;
        self.apply_mute(session);
    }

    pub fn set_deafened(&mut self, deafened: bool, session: Option<&Session>, settings: &crate::core::settings::Settings) {
        let cues = settings.voice_sounds.then_some(settings.sound_volume as f32 / 100.);
        if deafened != self.deafened {
            cue(if deafened { Cue::Deafen } else { Cue::Undeafen }, cues);
        }
        self.deafened = deafened;
        if deafened {
            self.muted_by_deafen |= !self.muted;
            self.muted = true;
        } else if std::mem::take(&mut self.muted_by_deafen) && !self.force_muted {
            self.muted = false;
        }
        self.apply_mute(session);
    }

    /// Muted, the microphone is closed, so the phone's "in use" mark goes away too.
    fn apply_mute_locally(&mut self) {
        if !self.started {
            return;
        }
        let a = audio_engine();
        a.mic.muted.store(self.muted, Ordering::Relaxed);
        a.mixer.deafened.store(self.deafened, Ordering::Relaxed);
        if self.muted {
            self.mic_guard = None;
        } else if self.mic_guard.is_none() {
            self.mic_guard = Some(a.acquire_mic());
        }
    }

    fn apply_mute(&mut self, session: Option<&Session>) {
        self.apply_mute_locally();
        self.send_mute(session);
    }

    fn send_mute(&self, session: Option<&Session>) {
        let Some(s) = session else { return };
        let reply = s.request("voice:mute", self.place().with(json!({ "muted": self.muted, "deafened": self.deafened })));
        crate::core::runtime().spawn(async move {
            let _ = reply.await;
        });
    }

    /// Hangs up every connection and leaves.
    pub fn hang_up(&mut self, session: Option<&Session>) {
        let told = std::mem::replace(&mut self.left, true);
        self.drop_mic();
        self.mic_guard = None;
        for (_, p) in self.peers.drain() {
            close_peer(p);
        }
        if let Some(a) = audio_started() {
            a.mixer.deafened.store(false, Ordering::Relaxed);
        }
        if let Some(s) = session
            && !told
        {
            let reply = s.request("voice:leave", self.place().payload());
            crate::core::runtime().spawn(async move {
                if let Err(e) = reply.await {
                    log::info!("leaving: {e}");
                }
            });
        }
    }
}

impl Drop for Call {
    fn drop(&mut self) {
        if !self.left {
            self.hang_up(None);
        }
    }
}

fn close_peer(mut p: Peer) {
    if let Some(a) = audio_started() {
        a.mixer.remove(&p.source);
    }
    p.watch.hang_up();
}

/// What a roster change sounds like: someone else arriving or leaving, at most one of each.
fn roster_cues(before: &[Member], now: &[Member], me: UserId) -> Vec<Cue> {
    let present = |list: &[Member], user: UserId| list.iter().any(|m| m.user_id == user);
    let joined = now.iter().any(|m| m.user_id != me && !present(before, m.user_id));
    let left = before.iter().any(|m| m.user_id != me && !present(now, m.user_id));
    [(joined, Cue::Join), (left, Cue::Leave)].into_iter().filter_map(|(yes, cue)| yes.then_some(cue)).collect()
}

impl Engine {
    /// The answer to `voice:join`.
    pub(super) fn joined(&mut self, id: u64, reply: Result<Value, crate::core::realtime::RequestError>) {
        let cues = self.voice_sounds();
        let Some(c) = self.call.as_mut().filter(|c| c.id == id) else {
            return;
        };
        match reply {
            Ok(v) => {
                let tokens: Option<VoiceTokens> = serde_json::from_value(v.clone()).ok();
                c.mid = v.get("mid").and_then(Value::as_i64);
                if let Some(roster) = v.get("roster").and_then(|r| serde_json::from_value::<Vec<Member>>(r.clone()).ok())
                    && let Some(s) = self.session.as_mut()
                {
                    s.rosters.insert(c.channel, roster);
                    s.unlocked.insert(c.channel);
                }
                let first = c.tokens.is_none();
                if first {
                    cue(Cue::Connect, cues);
                }
                c.tokens = tokens;
                c.state = CallState::Connected;
                c.schedule_refresh();
                if first {
                    let a = audio_engine();
                    a.mixer.deafened.store(c.deafened, Ordering::Relaxed);
                    a.mic.muted.store(c.muted, Ordering::Relaxed);
                    if !c.muted {
                        c.mic_guard = Some(a.acquire_mic());
                    }
                }
                c.started = true;
                // A new slot means new paths: everything is published again.
                if let Some(mine) = self
                    .session
                    .as_ref()
                    .and_then(|s| s.rosters.get(&c.channel).and_then(|r| r.iter().find(|m| m.user_id == s.me.id)).map(|m| m.mid))
                    && c.mic.is_some()
                    && c.mid != Some(mine)
                {
                    c.drop_mic();
                    c.mic_backoff.reset();
                }
                c.on_roster(self.session.as_ref(), &self.handle, None);
                if c.muted || c.deafened {
                    c.send_mute(self.session.as_ref());
                }
                self.apply_audio_prefs();
                self.mark();
            }
            Err(err) => {
                if matches!(err.code, ErrorCode::Offline | ErrorCode::Timeout) {
                    // The socket is down; the reconnect joins again.
                    c.state = CallState::Reconnecting;
                    self.mark_voice();
                    return;
                }
                c.left = true;
                let channel = c.channel;
                let why = match err.code {
                    ErrorCode::PasswordRequired | ErrorCode::BadPassword => {
                        self.send(json!({ "type": "needsPassword", "channelId": channel, "wrong": err.code == ErrorCode::BadPassword }));
                        None
                    }
                    ErrorCode::ChannelFull => Some(match err.reply.get("cap").and_then(Value::as_i64) {
                        Some(cap) => trf!("That channel is full ({} people).", "Esse canal está cheio ({} pessoas).", cap),
                        None => tr!("That channel is full.", "Esse canal está cheio.").into(),
                    }),
                    ErrorCode::NoSuchChannel => Some(tr!("That channel is gone.", "Esse canal não existe mais.").into()),
                    _ => Some(
                        crate::core::api::friendly(err.code, &err.reply)
                            .unwrap_or_else(|| trf!("Could not join voice ({}).", "Não foi possível entrar na voz ({}).", err)),
                    ),
                };
                self.leave();
                if let Some(why) = why {
                    self.toast(why);
                }
            }
        }
        self.mark_voice();
    }
}
