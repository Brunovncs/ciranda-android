//! The one place the app's state lives. Everything happens on the network runtime, in one task
//! that owns an `Engine`: commands from the interface, the socket's events, and whatever an async
//! job finished, each a closure run on the engine in turn (the desktop's `this.update(cx, ..)`,
//! without a window). What the interface draws goes out as JSON, coalesced: the state, the voice
//! call and the open chat each when they changed, at most once per pass.

mod call;
mod render;
pub mod session;
mod signin;
mod watch;

use crate::core::api::Api;
use crate::core::cache::Cache;
use crate::core::invite::Invite;
use crate::core::settings::{SavedServer, Store, same_server};
use crate::core::types::*;
use crate::media::audio;
use call::Call;
use serde_json::{Value, json};
use session::Session;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;

pub type Job = Box<dyn FnOnce(&mut Engine) + Send>;

/// How long the app may be away before the server on screen stops seeing you online: its socket
/// is put down, and a background one listens for mentions instead.
const PARK_AFTER: Duration = Duration::from_secs(60);

/// How async work gets back to the engine.
#[derive(Clone)]
pub struct Handle(mpsc::UnboundedSender<Job>);

impl Handle {
    pub fn update(&self, f: impl FnOnce(&mut Engine) + Send + 'static) {
        let _ = self.0.send(Box::new(f));
    }
}

/// Hands each event to the interface.
pub type Emit = Arc<dyn Fn(String) + Send + Sync>;

static HANDLE: OnceLock<Handle> = OnceLock::new();
/// The phone's language, for when the settings leave it to the system.
static SYSTEM_LANG: OnceLock<String> = OnceLock::new();

/// Where the app keeps its files, and how to reach the interface. Once per process.
pub fn start(cache_dir: PathBuf, emit: Emit) -> &'static Handle {
    HANDLE.get_or_init(|| {
        let (tx, mut rx) = mpsc::unbounded_channel::<Job>();
        let handle = Handle(tx);
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let mut engine = Engine::new(h.clone(), cache_dir, emit);
            engine.dirty.state = true;
            engine.flush();
            // Ten times a second: speaking rings, retries, stalls and token refreshes.
            let tick = h.clone();
            tokio::spawn(async move {
                let mut every = tokio::time::interval(Duration::from_millis(100));
                loop {
                    every.tick().await;
                    tick.update(|e| e.tick());
                }
            });
            while let Some(job) = rx.recv().await {
                job(&mut engine);
                // Whatever else is already waiting goes in the same pass, so a burst of pushes
                // makes one update for the interface.
                while let Ok(job) = rx.try_recv() {
                    job(&mut engine);
                }
                engine.flush();
            }
        });
        handle
    })
}

pub fn handle() -> Option<&'static Handle> {
    HANDLE.get()
}

#[derive(Default)]
struct Dirty {
    state: bool,
    voice: bool,
    chat: bool,
}

/// Where the app is: signing in, or in a server.
#[derive(Clone, Debug, PartialEq)]
pub enum Phase {
    SignedOut,
    SigningIn,
    In,
}

pub struct Engine {
    pub handle: Handle,
    emit: Emit,
    pub store: Store,
    pub cache: Cache,
    phase: Phase,
    /// What went wrong with the last sign-in, for the sign-in screen.
    sign_in_error: Option<String>,
    /// The last sign-in failed for want of the server's own password.
    needs_server_password: bool,
    /// The sign-in screen's fields as last tried, so a failure leaves them filled.
    sign_in_form: Value,
    /// Bumped by every sign-in and sign-out: an answer for an older one is dropped.
    sign_in_generation: u64,
    /// The sign-in under way came from the form (not a kept sign-in), which stays on screen.
    sign_in_via_form: bool,
    /// Failed sign-ins so far, so the form knows a new failure from the last; and whether the
    /// last was the account's password, the one field it then empties.
    sign_in_failures: u64,
    wrong_password: bool,
    /// A kept sign-in that found no network: tried again at this time; and how many times so far.
    retry: Option<(Instant, signin::Form)>,
    retry_tries: u32,
    /// Invites opened, so the form knows a new one from the last.
    invites: u64,
    pub session: Option<Session>,
    pub call: Option<Call>,
    dirty: Dirty,
    ticks: u64,
    /// Whether the interface is on screen; nothing of the chat is sent while it is not.
    visible: bool,
    /// Since when it has not been.
    away_since: Option<Instant>,
    /// Whether mentions become notifications: the interface's setting.
    notify: bool,
    watching: watch::Watching,
}

impl Engine {
    fn new(handle: Handle, cache_dir: PathBuf, emit: Emit) -> Engine {
        let store = Store::load();
        let cache = Cache::open(cache_dir.join("media"), store.values.media_cache_mb.max(64));
        let e = Engine {
            handle,
            emit,
            store,
            cache,
            phase: Phase::SignedOut,
            sign_in_error: None,
            needs_server_password: false,
            sign_in_form: Value::Null,
            sign_in_generation: 0,
            sign_in_via_form: false,
            sign_in_failures: 0,
            wrong_password: false,
            retry: None,
            retry_tries: 0,
            invites: 0,
            session: None,
            call: None,
            dirty: Dirty::default(),
            ticks: 0,
            // Until the interface says otherwise: started for a notification, or at boot, there
            // is none.
            visible: false,
            away_since: Some(Instant::now()),
            notify: false,
            watching: watch::Watching::default(),
        };
        e.apply_audio_prefs();
        e
    }

    pub fn mark(&mut self) {
        self.dirty.state = true;
    }

    pub fn mark_voice(&mut self) {
        self.dirty.voice = true;
    }

    pub fn mark_chat(&mut self) {
        self.dirty.chat = true;
    }

    pub fn send(&self, event: Value) {
        (self.emit)(event.to_string());
    }

    pub fn toast(&self, text: impl Into<String>) {
        self.send(json!({ "type": "toast", "text": text.into() }));
    }

    /// Runs `f` on the network runtime and hands its output to `done` on the engine.
    pub fn spawn<T: Send + 'static>(
        &self,
        f: impl Future<Output = T> + Send + 'static,
        done: impl FnOnce(&mut Engine, T) + Send + 'static,
    ) {
        let h = self.handle.clone();
        crate::core::runtime().spawn(async move {
            let out = f.await;
            h.update(move |e| done(e, out));
        });
    }

    fn flush(&mut self) {
        if std::mem::take(&mut self.dirty.state) {
            let state = self.state();
            self.send(state);
        }
        if std::mem::take(&mut self.dirty.voice) {
            let voice = self.call.as_ref().map(|c| c.state(self.session.as_ref())).unwrap_or(Value::Null);
            self.send(json!({ "type": "voice", "voice": voice }));
        }
        if std::mem::take(&mut self.dirty.chat)
            && self.visible
            && let Some(chat) = self.session.as_ref().and_then(|s| s.chat_state())
        {
            self.send(chat);
        }
    }

    fn state(&self) -> Value {
        let v = &self.store.values;
        let saved: Vec<Value> = v
            .saved_servers
            .iter()
            .map(|s| {
                json!({
                    "url": s.url,
                    "host": s.host(),
                    "username": s.username,
                    "name": s.name,
                    "signedIn": !s.session_token.is_empty(),
                })
            })
            .collect();
        let settings = json!({
            "noiseSuppression": v.noise_suppression,
            "neural": v.noise_suppressor != "classic",
            "echoCancellation": v.echo_cancellation,
            "autoGain": v.auto_gain,
            "voiceSounds": v.voice_sounds,
            "checkUpdates": v.check_updates,
            "micGain": v.mic_gain,
            "micSensitivity": v.mic_sensitivity,
            "soundVolume": v.sound_volume,
            "language": v.language,
        });
        json!({
            "type": "state",
            "phase": match self.phase {
                Phase::SignedOut => "signedOut",
                Phase::SigningIn => "signingIn",
                Phase::In => "in",
            },
            "signIn": {
                "error": self.sign_in_error,
                "serverPassword": self.needs_server_password,
                "viaForm": self.sign_in_via_form,
                "failures": self.sign_in_failures,
                "wrongPassword": self.wrong_password,
                "retrying": self.retry.is_some(),
                "form": self.sign_in_form,
                "saved": saved,
            },
            "session": self.session.as_ref().map(Session::state),
            "settings": settings,
            "version": env!("CARGO_PKG_VERSION"),
        })
    }

    fn tick(&mut self) {
        self.ticks += 1;
        let n = self.ticks;
        if let Some(call) = self.call.as_mut() {
            let session = self.session.as_ref();
            let mut changed = call.tick(session);
            if n.is_multiple_of(10) {
                changed |= call.retry(session, &self.handle);
            }
            if n.is_multiple_of(40) {
                call.check(&self.handle);
            }
            if call.refresh_due(Instant::now()) {
                call.refresh_tokens(session, &self.handle);
            }
            if changed {
                self.dirty.voice = true;
            }
        }
        if n.is_multiple_of(10) {
            if self.retry.as_ref().is_some_and(|(at, _)| Instant::now() >= *at)
                && let Some((_, form)) = self.retry.take()
            {
                self.begin_sign_in(form);
            }
            if n.is_multiple_of(50)
                && let Some(s) = self.session.as_mut()
            {
                s.retry_stale(&self.handle);
            }
            let away = self.away_since.is_some_and(|t| t.elapsed() >= PARK_AFTER);
            if away
                && self.call.is_none()
                && let Some(s) = self.session.as_mut().filter(|s| !s.parked)
            {
                s.park();
                self.mark();
            }
            self.sync_watch();
        }
        if n.is_multiple_of(20) {
            let cache = self.cache.clone();
            crate::core::runtime().spawn_blocking(move || cache.flush());
        }
    }

    pub fn command(&mut self, cmd: Value) {
        let kind = cmd.get("cmd").and_then(Value::as_str).unwrap_or_default().to_string();
        let int = |k: &str| cmd.get(k).and_then(Value::as_i64);
        let text = |k: &str| cmd.get(k).and_then(Value::as_str).unwrap_or_default().to_string();
        let flag = |k: &str| cmd.get(k).and_then(Value::as_bool).unwrap_or(false);
        match kind.as_str() {
            "hello" => {
                if let Some(l) = cmd.get("locale").and_then(Value::as_str) {
                    let _ = SYSTEM_LANG.set(l.to_string());
                    self.apply_audio_prefs();
                }
                self.mark();
                self.mark_voice();
                self.mark_chat();
            }
            "visible" => {
                self.visible = flag("on");
                self.away_since = if self.visible { None } else { Some(Instant::now()) };
                if self.visible {
                    if let Some(s) = self.session.as_mut().filter(|s| s.parked) {
                        s.unpark(&self.handle);
                        self.mark();
                    }
                    // The channel on screen again: what was counted on it while away is seen.
                    if let Some(s) = self.session.as_mut()
                        && let Some(id) = s.open_channel
                    {
                        s.viewing = Some(id);
                        s.mentioned.remove(&id);
                        s.unread.remove(&id);
                        self.mark();
                    }
                    self.sync_watch();
                    self.mark_chat();
                }
            }
            "notifications" => {
                self.notify = flag("on");
                self.sync_watch();
            }
            "openAt" => self.open_at(&text("server"), &text("username"), int("channelId")),
            "signIn" => self.sign_in(&cmd),
            "invite" => self.open_invite(&text("link")),
            "shareInvite" => self.share_invite(),
            "resume" => self.resume(cmd.get("url").and_then(Value::as_str), cmd.get("username").and_then(Value::as_str)),
            "signOut" => self.sign_out(flag("forget")),
            "switch" => self.switch_server(),
            "retryNow" => {
                if let Some(r) = self.retry.as_mut() {
                    r.0 = Instant::now();
                }
            }
            "open" => {
                if let (Some(id), Some(s)) = (int("channelId"), self.session.as_mut()) {
                    s.open(id, &self.handle);
                    let server = s.api.base();
                    self.store.values.set_last_channel(&server, id);
                    self.store.save();
                    self.mark();
                    self.mark_chat();
                }
            }
            "close" => {
                if let Some(s) = self.session.as_mut() {
                    s.viewing = None;
                }
            }
            "older" => {
                if let Some(s) = self.session.as_mut() {
                    s.load_older(&self.handle);
                }
            }
            "send" => {
                if let (Some(id), Some(s)) = (int("channelId"), self.session.as_ref()) {
                    s.send_message(id, text("body"), int("replyTo"), &self.handle);
                }
            }
            "upload" => {
                if let (Some(id), Some(s)) = (int("channelId"), self.session.as_ref()) {
                    s.upload(id, PathBuf::from(text("path")), text("name"), text("mime"), text("body"), &self.handle);
                }
            }
            "react" => {
                if let (Some(id), Some(s)) = (int("id"), self.session.as_ref()) {
                    s.react(id, text("emoji"), flag("on"), &self.handle);
                }
            }
            "edit" => {
                if let (Some(id), Some(s)) = (int("id"), self.session.as_ref()) {
                    s.edit(id, text("body"), &self.handle);
                }
            }
            "delete" => {
                if let (Some(id), Some(s)) = (int("id"), self.session.as_ref()) {
                    s.delete(id, &self.handle);
                }
            }
            "picture" => {
                let hash = text("hash");
                if let Some(s) = self.session.as_ref() {
                    s.picture(hash, text("kind"), self.cache.clone(), &self.handle);
                }
            }
            "file" => {
                if let Some(s) = self.session.as_ref() {
                    let size = cmd.get("size").and_then(Value::as_u64);
                    s.file(text("hash"), text("name"), size, &self.handle);
                }
            }
            "join" => {
                if let Some(id) = int("channelId") {
                    self.join(id, cmd.get("password").and_then(Value::as_str).map(str::to_string));
                }
            }
            "leave" => self.leave(),
            "mute" => {
                if let Some(c) = self.call.as_mut() {
                    c.set_muted(flag("on"), self.session.as_ref(), &self.store.values);
                    self.mark_voice();
                }
            }
            "deafen" => {
                if let Some(c) = self.call.as_mut() {
                    c.set_deafened(flag("on"), self.session.as_ref(), &self.store.values);
                    self.mark_voice();
                }
            }
            "volume" => {
                let (Some(user), Some(gain)) = (int("userId"), cmd.get("gain").and_then(Value::as_f64)) else {
                    return;
                };
                let gain = (gain as f32).clamp(0., audio::MAX_GAIN);
                call::set_volume(user, gain);
                if let Some(c) = self.call.as_ref() {
                    c.apply_volume(user, gain);
                }
                if let Some(s) = self.session.as_ref() {
                    let server = s.api.base();
                    self.store.values.set_voice_volume(&server, user, gain);
                    self.store.save();
                }
                self.mark_voice();
            }
            "settings" => self.change_settings(&cmd),
            "changePassword" => self.change_password(text("current"), text("password")),
            other => log::warn!("unknown command {other:?}"),
        }
    }

    fn change_settings(&mut self, cmd: &Value) {
        let v = &mut self.store.values;
        let get = |k: &str| cmd.get(k);
        if let Some(b) = get("noiseSuppression").and_then(Value::as_bool) {
            v.noise_suppression = b;
        }
        if let Some(b) = get("neural").and_then(Value::as_bool) {
            v.noise_suppressor = if b { "neural" } else { "classic" }.into();
        }
        if let Some(b) = get("echoCancellation").and_then(Value::as_bool) {
            v.echo_cancellation = b;
        }
        if let Some(b) = get("autoGain").and_then(Value::as_bool) {
            v.auto_gain = b;
        }
        if let Some(b) = get("voiceSounds").and_then(Value::as_bool) {
            v.voice_sounds = b;
        }
        if let Some(b) = get("checkUpdates").and_then(Value::as_bool) {
            v.check_updates = b;
        }
        if let Some(n) = get("micGain").and_then(Value::as_u64) {
            v.mic_gain = n.min(200) as u32;
        }
        if let Some(n) = get("micSensitivity").and_then(Value::as_u64) {
            v.mic_sensitivity = n.min(100) as u32;
        }
        if let Some(n) = get("soundVolume").and_then(Value::as_u64) {
            v.sound_volume = n.min(200) as u32;
        }
        if let Some(l) = get("language").and_then(Value::as_str) {
            v.language = l.to_string();
        }
        self.store.save();
        self.apply_audio_prefs();
        self.mark();
    }

    /// The microphone's processing as the settings say; plain stores, read by the audio threads.
    fn apply_audio_prefs(&self) {
        use std::sync::atomic::Ordering::Relaxed;
        let v = &self.store.values;
        let lang = if v.language.is_empty() { SYSTEM_LANG.get().map(String::as_str).unwrap_or("en") } else { &v.language };
        crate::i18n::set(crate::i18n::resolve(if lang.starts_with("pt") { "pt" } else { "en" }));
        // The audio engine starts only for a call; until then there is nothing to set.
        let Some(a) = call::audio_started() else {
            return;
        };
        a.mic.echo_cancellation.store(v.echo_cancellation, Relaxed);
        a.mic.noise_suppression.store(v.noise_suppression, Relaxed);
        a.mic.neural.store(v.noise_suppressor != "classic", Relaxed);
        a.mic.auto_gain.store(v.auto_gain, Relaxed);
        a.mic.gain.set(v.mic_gain as f32 / 100.);
        a.mic.gate.set(call::gate_threshold(v.mic_sensitivity));
    }

    // Signing in and out.

    fn sign_in(&mut self, cmd: &Value) {
        let text = |k: &str| cmd.get(k).and_then(Value::as_str).unwrap_or_default().trim().to_string();
        let form = signin::Form {
            server: text("server"),
            server_password: cmd.get("serverPassword").and_then(Value::as_str).unwrap_or_default().to_string(),
            nickname: text("nickname"),
            password: cmd.get("password").and_then(Value::as_str).unwrap_or_default().to_string(),
            register: cmd.get("register").and_then(Value::as_bool).unwrap_or(false),
            token: None,
        };
        // Everything typed stays for another try but the account's password; an invite stays with
        // its server.
        let before = std::mem::take(&mut self.sign_in_form);
        let mut kept = json!({
            "server": form.server,
            "serverPassword": form.server_password,
            "nickname": form.nickname,
            "register": form.register,
        });
        if before.get("server").and_then(Value::as_str).is_some_and(|s| same_server(&base_for(s), &base_for(&form.server))) {
            for k in ["invite", "seq"] {
                if let Some(v) = before.get(k) {
                    kept[k] = v.clone();
                }
            }
        }
        self.sign_in_form = kept;
        self.begin_sign_in(form);
    }

    /// An invite link opened on the phone (`core::invite`): the server it names, signed in to
    /// straight away where this phone already is, and otherwise the form, filled in, making an
    /// account.
    fn open_invite(&mut self, link: &str) {
        let Some(invite) = Invite::parse(link) else {
            self.toast(tr!("That invite link is broken.", "Esse link de convite está quebrado."));
            return;
        };
        let place = if invite.name.is_empty() { invite.server.clone() } else { invite.name.clone() };
        if self.session.as_ref().is_some_and(|s| same_server(&s.api.base(), &invite.server)) {
            self.toast(trf!("You're already in {}.", "Você já está em {}.", place));
            return;
        }
        let kept =
            self.store.values.saved_servers.iter().find(|s| same_server(&s.url, &invite.server) && !s.session_token.is_empty()).cloned();
        if let Some(saved) = kept {
            self.resume(Some(&saved.url), Some(&saved.username));
            return;
        }
        // Whatever sign-in was on its way (the last server, at start) gives way to the invite.
        self.retry = None;
        self.retry_tries = 0;
        self.sign_in_generation += 1;
        self.end_session();
        self.invites += 1;
        self.phase = Phase::SignedOut;
        self.sign_in_error = None;
        self.needs_server_password = false;
        self.sign_in_form = json!({
            "server": invite.server,
            "serverPassword": invite.password,
            "nickname": "",
            "register": true,
            "invite": place,
            "seq": self.invites,
        });
        self.mark();
    }

    /// An invite to the server on screen, for the interface to share.
    fn share_invite(&self) {
        let Some(s) = self.session.as_ref() else { return };
        let url = s.api.base();
        let password = self.store.values.saved_server_as(&url, &s.me.nickname).map(|v| v.password.clone()).unwrap_or_default();
        let link = Invite::new(&url, &password, &s.server_name).link();
        self.send(json!({ "type": "inviteLink", "link": link, "name": s.server_name }));
    }

    /// Where a notification leads: a channel on a server, signed in to first if it is not the one
    /// on screen.
    fn open_at(&mut self, url: &str, username: &str, channel: Option<ChannelId>) {
        let here = self.session.as_ref().is_some_and(|s| same_server(&s.api.base(), url) && s.me.nickname.eq_ignore_ascii_case(username));
        if here {
            if let Some(id) = channel {
                self.command(json!({ "cmd": "open", "channelId": id }));
            }
            return;
        }
        let Some(saved) =
            self.store.values.saved_servers.iter().find(|s| same_server(&s.url, url) && s.username.eq_ignore_ascii_case(username)).cloned()
        else {
            return;
        };
        if let Some(id) = channel {
            self.store.values.set_last_channel(&saved.url, id);
            self.store.save();
        }
        self.resume(Some(&saved.url), Some(&saved.username));
    }

    /// A saved server: the one asked for, or the last one used.
    fn resume(&mut self, url: Option<&str>, username: Option<&str>) {
        self.retry_tries = 0;
        let v = &self.store.values;
        let saved = match (url, username) {
            (Some(u), Some(n)) => v.saved_server_as(u, n).cloned(),
            _ => v.active_server().and_then(|i| v.saved_servers.get(i)).cloned(),
        };
        let Some(saved) = saved else {
            self.phase = Phase::SignedOut;
            self.mark();
            return;
        };
        self.sign_in_form = json!({ "server": saved.host(), "nickname": saved.username, "register": false });
        if saved.session_token.is_empty() {
            // Signed out there: the form, filled in, waits for the password.
            self.phase = Phase::SignedOut;
            self.mark();
            return;
        }
        let form = signin::Form {
            server: saved.url.clone(),
            server_password: saved.password.clone(),
            nickname: saved.username.clone(),
            password: saved.account_password.clone(),
            register: false,
            token: Some(saved.session_token.clone()),
        };
        self.begin_sign_in(form);
    }

    fn begin_sign_in(&mut self, form: signin::Form) {
        self.retry = None;
        self.end_session();
        self.sign_in_generation += 1;
        let generation = self.sign_in_generation;
        self.phase = Phase::SigningIn;
        self.sign_in_error = None;
        self.sign_in_via_form = form.token.is_none();
        self.mark();
        let api = Api::new();
        let base = base_for(&form.server);
        api.set_server(&base, &form.server_password);
        let job_api = api.clone();
        let job_form = form.clone();
        self.spawn(async move { signin::sign_in(&job_api, &job_form).await }, move |e, got| {
            if e.sign_in_generation != generation {
                return;
            }
            match got {
                Ok(done) => {
                    let saved = SavedServer {
                        url: api.base(),
                        password: form.server_password.clone(),
                        username: done.user.nickname.clone(),
                        session_token: done.token.clone(),
                        account_password: String::new(),
                        name: done.server_name.clone(),
                        icon: done.server_icon.clone().unwrap_or_default(),
                    };
                    e.store.values.remember_server(saved);
                    e.store.values.switch_server(&api.base(), &done.user.nickname);
                    e.store.save();
                    for note in &done.notes {
                        e.toast(*note);
                    }
                    let last = e.store.values.last_channel(&api.base());
                    // What was typed is done with: the door password and an invite too.
                    e.sign_in_form = json!({ "server": saved_host(&api.base()), "nickname": done.user.nickname, "register": false });
                    e.wrong_password = false;
                    if e.sign_in_via_form {
                        e.send(json!({ "type": "signedIn" }));
                    }
                    let mut s = Session::new(api, done, &e.handle);
                    s.last_channel = last;
                    // Started with nothing on screen (at boot, for the notifications): not online
                    // there until the app is opened.
                    if !e.visible && e.call.is_none() {
                        s.park();
                    }
                    e.session = Some(s);
                    e.retry_tries = 0;
                    e.phase = Phase::In;
                }
                Err(f) => {
                    // No network for a kept sign-in: not a reason to drop it. It is tried again
                    // after a while, longer each time, the connecting screen saying so.
                    if f.offline && form.token.is_some() {
                        let wait = Duration::from_secs((5u64 << e.retry_tries.min(4)).min(60));
                        e.retry_tries += 1;
                        e.retry = Some((Instant::now() + wait, form.clone()));
                        e.sign_in_error = Some(f.message);
                        e.mark();
                        return;
                    }
                    e.retry_tries = 0;
                    if f.expired {
                        e.store.values.forget_token(&api.base(), &form.nickname);
                        e.store.save();
                    }
                    e.sign_in_error = Some(f.message);
                    e.needs_server_password = f.server_password;
                    e.sign_in_failures += 1;
                    e.wrong_password = f.wrong_password;
                    e.phase = Phase::SignedOut;
                }
            }
            e.mark();
        });
    }

    /// Back to the list of servers, still signed in to this one: it goes on telling of mentions,
    /// and is one tap away.
    fn switch_server(&mut self) {
        self.retry = None;
        self.retry_tries = 0;
        self.sign_in_generation += 1;
        self.end_session();
        self.sign_in_error = None;
        self.phase = Phase::SignedOut;
        self.mark();
    }

    /// A new password for the account on screen. The server signs every other device out and
    /// hands this one a new sign-in, kept in place of the old.
    fn change_password(&mut self, current: String, password: String) {
        let Some(s) = self.session.as_ref() else { return };
        let api = s.api.clone();
        let (url, name) = (s.api.base(), s.me.nickname.clone());
        self.spawn(async move { api.change_password(&current, &password).await }, move |e, got| match got {
            Ok(token) => {
                if let Some(s) = e.session.as_ref().filter(|s| same_server(&s.api.base(), &url)) {
                    s.api.set_token(&token);
                }
                if let Some(mut saved) = e.store.values.saved_server_as(&url, &name).cloned() {
                    saved.session_token = token;
                    e.store.values.remember_server(saved);
                    e.store.save();
                }
                e.send(json!({ "type": "passwordChanged", "ok": true }));
            }
            Err(err) => e.send(json!({ "type": "passwordChanged", "ok": false, "error": err.message })),
        });
    }

    /// Leaves the server: the call, the socket, and with `forget` the saved sign-in too.
    fn sign_out(&mut self, forget: bool) {
        self.retry = None;
        self.retry_tries = 0;
        if let Some(s) = self.session.as_ref() {
            let (url, name) = (s.api.base(), s.me.nickname.clone());
            if forget {
                self.store.values.forget_server(&url, &name);
            } else {
                self.store.values.forget_token(&url, &name);
            }
            self.store.save();
            let api = s.api.clone();
            crate::core::runtime().spawn(async move {
                let _ = api.logout().await;
            });
        }
        self.sign_in_generation += 1;
        self.end_session();
        self.phase = Phase::SignedOut;
        self.mark();
    }

    /// The server told this sign-in to go (expired, or the account removed).
    pub fn signed_out(&mut self, why: String) {
        if let Some(s) = self.session.as_ref() {
            let (url, name) = (s.api.base(), s.me.nickname.clone());
            self.store.values.forget_token(&url, &name);
            self.store.save();
        }
        self.end_session();
        self.phase = Phase::SignedOut;
        self.sign_in_error = Some(why);
        self.mark();
    }

    fn end_session(&mut self) {
        self.leave();
        if let Some(mut s) = self.session.take() {
            s.stop();
        }
        self.mark_chat();
    }

    // Voice.

    fn join(&mut self, channel: ChannelId, password: Option<String>) {
        let (muted, deafened) = (false, false);
        self.join_as(channel, password, muted, deafened);
    }

    fn join_as(&mut self, channel: ChannelId, password: Option<String>, muted: bool, deafened: bool) {
        if self.call.as_ref().is_some_and(|c| c.channel == channel && !c.left) {
            return;
        }
        self.leave();
        let Some(session) = self.session.as_ref() else {
            return;
        };
        let v = &self.store.values;
        *call::volumes().lock() = v.voice_volumes_on(&session.api.base());
        let mut c = Call::new(channel, password, muted, deafened);
        c.send_join(session, &self.handle);
        self.call = Some(c);
        self.apply_audio_prefs();
        self.mark_voice();
        self.send(json!({ "type": "call", "on": true }));
    }

    pub fn leave(&mut self) {
        if let Some(mut c) = self.call.take() {
            c.hang_up(self.session.as_ref());
            self.send(json!({ "type": "call", "on": false }));
            self.mark_voice();
        }
    }

    pub fn voice_sounds(&self) -> Option<f32> {
        let v = &self.store.values;
        v.voice_sounds.then_some(v.sound_volume as f32 / 100.)
    }
}

/// An address as the form shows it: without the scheme HTTPS goes without saying.
fn saved_host(base: &str) -> String {
    base.strip_prefix("https://").unwrap_or(base).to_string()
}

/// The address typed, with its scheme. Without one, a server on the internet is reached over
/// HTTPS, as a browser would, and one nearby (this network, Tailscale) over HTTP; the desktop
/// assumes HTTP, where people type the scheme.
fn base_for(typed: &str) -> String {
    use crate::core::api::{normalize_base, sends_in_the_clear};
    let typed = typed.trim();
    if !typed.contains("://") && sends_in_the_clear(typed) {
        return normalize_base(&format!("https://{typed}"));
    }
    normalize_base(typed)
}

#[cfg(test)]
mod tests {
    use super::base_for;

    #[test]
    fn addresses_without_a_scheme_pick_one() {
        assert_eq!(base_for("bloquinho.duckdns.org:8444"), "https://bloquinho.duckdns.org:8444");
        assert_eq!(base_for("10.0.2.2:8580"), "http://10.0.2.2:8580");
        assert_eq!(base_for("192.168.0.10:8080/"), "http://192.168.0.10:8080");
        assert_eq!(base_for("pi.local:8080"), "http://pi.local:8080");
        assert_eq!(base_for("http://example.com"), "http://example.com");
        assert_eq!(base_for(" https://example.com/ "), "https://example.com");
    }
}
