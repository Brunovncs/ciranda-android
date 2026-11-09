//! The server you are in: who is there, the channels and who is in each, and the chat open on
//! screen. Fed by the socket and by HTTP replies, as the desktop's `session.rs` is; the private
//! conversations are not here yet.

use super::signin::Done;
use super::{Engine, Handle};
use crate::core::api::{Api, ApiError};
use crate::core::cache::Cache;
use crate::core::realtime::{self, Realtime, RequestError, Scope};
use crate::core::types::*;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

/// Messages per page of history, as the server pages them.
const PAGE: usize = 50;
/// How many channels keep their history: the open one and those opened just before it.
const KEEP_LOGS: usize = 4;
/// The most messages a channel keeps, and what it goes back down to.
const LOG_MOST: usize = 1000;
const LOG_KEEP: usize = 500;

/// Where pictures are put for the interface to draw.
static PICTURES: OnceLock<PathBuf> = OnceLock::new();

pub fn set_pictures_dir(dir: PathBuf) {
    let _ = std::fs::create_dir_all(&dir);
    let _ = PICTURES.set(dir);
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Link {
    Up,
    Reconnecting,
}

#[derive(Clone, Debug)]
enum LogOp {
    Added(Message),
    Updated(Message),
    Deleted(MessageId),
    Reactions(MessageId, Vec<Reaction>),
}

/// One channel's history, as the desktop's `ChatLog` keeps it.
#[derive(Default)]
pub struct ChatLog {
    pub messages: Vec<Message>,
    pub loaded: bool,
    fetching: bool,
    loading_older: bool,
    pending: Vec<LogOp>,
    pub more_before: bool,
    pub error: Option<String>,
    stale: bool,
    generation: u64,
}

fn next_generation() -> u64 {
    static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    N.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
}

impl ChatLog {
    fn new() -> ChatLog {
        ChatLog { generation: next_generation(), ..Default::default() }
    }

    fn push(&mut self, op: LogOp) {
        if self.fetching {
            self.pending.push(op);
        } else if self.stale && matches!(op, LogOp::Added(_)) {
            // Messages may be missing between the newest kept and this one; the next fetch
            // brings it with them.
        } else if self.loaded {
            self.apply(op);
            self.trim();
        }
    }

    fn trim(&mut self) {
        if self.messages.len() <= LOG_MOST {
            return;
        }
        let over = self.messages.len() - LOG_KEEP;
        self.messages.drain(..over);
        self.more_before = true;
        self.generation = next_generation();
        self.loading_older = false;
    }

    fn apply(&mut self, op: LogOp) {
        match op {
            LogOp::Added(m) => {
                if let Err(at) = self.find(m.id) {
                    self.messages.insert(at, m);
                }
            }
            LogOp::Updated(m) => {
                if let Ok(at) = self.find(m.id) {
                    self.messages[at] = m;
                }
            }
            LogOp::Deleted(id) => {
                if let Ok(at) = self.find(id) {
                    self.messages.remove(at);
                }
            }
            LogOp::Reactions(id, r) => {
                if let Ok(at) = self.find(id) {
                    self.messages[at].reactions = r;
                }
            }
        }
    }

    fn find(&self, id: MessageId) -> Result<usize, usize> {
        self.messages.binary_search_by_key(&id, |m| m.id)
    }

    fn land(&mut self, page: History) {
        let full = page.messages.len() >= PAGE;
        let oldest = page.messages.first().map(|m| m.id);
        let gap = oldest.is_some_and(|o| self.messages.last().is_some_and(|m| m.id < o));
        if !self.loaded || !full || gap {
            self.more_before = full;
            self.messages = page.messages;
            self.generation = next_generation();
            self.loading_older = false;
        } else if let Some(oldest) = oldest {
            self.messages.retain(|m| m.id < oldest);
            self.messages.extend(page.messages);
        }
        self.loaded = true;
        self.fetching = false;
        self.error = None;
        for op in std::mem::take(&mut self.pending) {
            self.apply(op);
        }
        self.trim();
    }

    fn fetch_failed(&mut self, message: String) {
        self.fetching = false;
        let pending = std::mem::take(&mut self.pending);
        if self.loaded {
            for op in pending {
                self.apply(op);
            }
            self.stale = true;
        } else {
            self.error = Some(message);
        }
    }

    fn begin_older(&mut self) -> Option<(MessageId, u64)> {
        if self.fetching || self.loading_older || !self.more_before {
            return None;
        }
        let first = self.messages.first()?.id;
        self.loading_older = true;
        Some((first, self.generation))
    }

    fn older_landed(&mut self, generation: u64, page: Option<History>) {
        if generation != self.generation {
            return;
        }
        self.loading_older = false;
        let Some(page) = page else { return };
        self.more_before = page.messages.len() >= PAGE;
        let first = self.messages.first().map_or(MessageId::MAX, |m| m.id);
        let mut older = page.messages;
        older.retain(|m| m.id < first);
        older.append(&mut self.messages);
        self.messages = older;
    }
}

pub struct Session {
    pub api: Api,
    pub realtime: Option<Realtime>,
    /// Which socket is the current one: events from an older one are dropped.
    socket: u64,
    pub link: Link,
    pub ever_up: bool,
    pub me: User,
    pub server_name: String,
    pub server_icon: Option<String>,
    pub server_info: Option<ServerInfo>,
    pub users: HashMap<UserId, User>,
    pub online: HashSet<UserId>,
    pub channels: Vec<Channel>,
    pub groups: Vec<Group>,
    pub rosters: HashMap<ChannelId, Vec<Member>>,
    pub unlocked: HashSet<ChannelId>,
    pub voice_cap: i64,
    pub ice_servers: Vec<IceServer>,
    pub emojis: Vec<CustomEmoji>,
    pub chats: HashMap<ChannelId, ChatLog>,
    recent: Vec<ChannelId>,
    pub open_channel: Option<ChannelId>,
    /// The chat on screen; none while the app is elsewhere.
    pub viewing: Option<ChannelId>,
    pub unread: HashSet<ChannelId>,
    pub mentioned: HashMap<ChannelId, u32>,
    /// The channel last open on this server, opened once the channels arrive.
    pub last_channel: Option<ChannelId>,
}

impl Session {
    pub fn new(api: Api, done: Done, handle: &Handle) -> Session {
        let me = done.user;
        let mut s = Session {
            api,
            realtime: None,
            socket: 0,
            link: Link::Reconnecting,
            ever_up: false,
            users: HashMap::from([(me.id, me.clone())]),
            me,
            server_name: done.server_name,
            server_icon: done.server_icon,
            server_info: None,
            online: HashSet::new(),
            channels: Vec::new(),
            groups: Vec::new(),
            rosters: HashMap::new(),
            unlocked: HashSet::new(),
            voice_cap: 16,
            ice_servers: done.ice_servers,
            emojis: Vec::new(),
            chats: HashMap::new(),
            recent: Vec::new(),
            open_channel: None,
            viewing: None,
            unread: HashSet::new(),
            mentioned: HashMap::new(),
            last_channel: None,
        };
        s.connect(handle);
        s.refresh_lists(handle);
        s
    }

    fn connect(&mut self, handle: &Handle) {
        let (tx, rx) = async_channel::unbounded();
        let rt = Realtime::start(crate::core::runtime().handle(), self.api.clone(), Scope::Full, tx);
        self.socket += 1;
        let socket = self.socket;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            while let Ok(ev) = rx.recv().await {
                h.update(move |e| e.on_socket(socket, ev));
            }
        });
        if let Some(old) = self.realtime.replace(rt) {
            old.stop();
        }
        self.link = Link::Reconnecting;
    }

    pub fn stop(&mut self) {
        self.socket += 1;
        if let Some(rt) = self.realtime.take() {
            rt.stop();
        }
    }

    /// Sends a request on the socket right away (so requests go out in the order they are made)
    /// and returns the reply to await.
    pub fn request(&self, kind: &str, payload: Value) -> impl Future<Output = Result<Value, RequestError>> + Send + use<> {
        let reply = self.realtime.as_ref().map(|rt| rt.request(kind, payload));
        async move {
            match reply {
                Some(reply) => reply.await,
                None => Err(RequestError { code: ErrorCode::Offline, reply: Value::Null }),
            }
        }
    }

    pub fn refresh_lists(&self, handle: &Handle) {
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let (users, emojis, channels, server) = tokio::join!(api.users(), api.emojis(), api.channels(), api.server());
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                if let Ok(info) = server {
                    if !info.name.is_empty() {
                        s.server_name = info.name.clone();
                    }
                    s.api.note_file_limits(&info);
                    s.server_info = Some(info);
                }
                if let Ok(users) = users {
                    s.users = users.into_iter().map(|u| (u.id, u)).collect();
                    if let Some(me) = s.users.get(&s.me.id) {
                        s.me = me.clone();
                    }
                }
                if let Ok(list) = emojis {
                    s.emojis = list;
                }
                if let Ok(snap) = channels {
                    s.unlocked = snap.channels.iter().filter(|c| c.unlocked == Some(true)).map(|c| c.id).collect();
                    if s.channels.is_empty() {
                        s.apply_snapshot(snap);
                    }
                }
                e.mark();
            });
        });
    }

    fn apply_snapshot(&mut self, snap: Snapshot) {
        self.channels = snap.channels;
        self.groups = snap.groups;
        self.online = snap.online.into_iter().collect();
        self.rosters = snap.rosters.into_iter().filter_map(|(k, v)| Some((k.parse().ok()?, v))).collect();
        if let Some(cap) = snap.voice_cap {
            self.voice_cap = cap;
        }
        if let Some(me) = snap.user {
            self.users.insert(me.id, me.clone());
            self.me = me;
        }
    }

    pub fn display_name(&self, id: Option<UserId>, fallback: Option<&str>) -> String {
        id.and_then(|id| self.users.get(&id))
            .map(|u| u.name().to_string())
            .or(fallback.map(str::to_string))
            .unwrap_or_else(|| tr!("someone", "alguém").into())
    }

    pub fn channel(&self, id: ChannelId) -> Option<&Channel> {
        self.channels.iter().find(|c| c.id == id)
    }

    /// What the interface draws of the server, the chat aside.
    pub fn state(&self) -> Value {
        let mut users: Vec<&User> = self.users.values().collect();
        users.sort_by_key(|u| u.name().to_lowercase());
        let rosters: serde_json::Map<String, Value> =
            self.rosters.iter().map(|(id, r)| (id.to_string(), serde_json::to_value(r).unwrap_or_default())).collect();
        let mentioned: serde_json::Map<String, Value> = self.mentioned.iter().map(|(id, n)| (id.to_string(), json!(n))).collect();
        json!({
            "url": self.api.base(),
            "name": self.server_name,
            "icon": self.server_icon,
            "link": if self.link == Link::Up { "up" } else { "reconnecting" },
            "me": self.me,
            "users": users,
            "online": self.online,
            "channels": self.channels,
            "groups": self.groups,
            "rosters": rosters,
            "unlocked": self.unlocked,
            "unread": self.unread,
            "mentioned": mentioned,
            "openChannel": self.open_channel,
            "maxFileBytes": self.server_info.as_ref().and_then(|i| i.max_file_bytes),
            "emojis": self.emojis,
        })
    }

    /// The open chat, for the interface.
    pub fn chat_state(&self) -> Option<Value> {
        let id = self.open_channel?;
        let log = self.chats.get(&id)?;
        let names = super::render::Names { emojis: &self.emojis, users: &self.users, me: self.me.id };
        let messages: Vec<Value> = log
            .messages
            .iter()
            .map(|m| {
                let mut v = serde_json::to_value(m).unwrap_or_default();
                v["rendered"] = super::render::body(&m.body, &names);
                v["author"] = json!(self.display_name(m.user_id, m.nickname.as_deref()));
                v["avatar"] = json!(m.user_id.and_then(|u| self.users.get(&u)).and_then(|u| u.avatar_hash.clone()));
                v
            })
            .collect();
        Some(json!({
            "type": "chat",
            "channelId": id,
            "messages": messages,
            "loaded": log.loaded,
            "moreBefore": log.more_before,
            "loadingOlder": log.loading_older,
            "error": log.error,
        }))
    }

    // Chat.

    pub fn open(&mut self, id: ChannelId, handle: &Handle) {
        self.open_channel = Some(id);
        self.viewing = Some(id);
        self.mentioned.remove(&id);
        self.unread.remove(&id);
        self.recent.retain(|c| *c != id);
        self.recent.push(id);
        if self.recent.len() > KEEP_LOGS {
            let gone: Vec<ChannelId> = self.recent.drain(..self.recent.len() - KEEP_LOGS).collect();
            self.chats.retain(|c, _| !gone.contains(c));
        }
        let log = self.chats.entry(id).or_insert_with(ChatLog::new);
        if (!log.loaded || log.stale) && !log.fetching {
            self.fetch_latest(id, handle);
        }
    }

    fn fetch_latest(&mut self, id: ChannelId, handle: &Handle) {
        let log = self.chats.entry(id).or_insert_with(ChatLog::new);
        log.fetching = true;
        log.stale = false;
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.messages(id, None).await;
            h.update(move |e| {
                let Some(log) = e.session.as_mut().and_then(|s| s.chats.get_mut(&id)) else {
                    return;
                };
                match got {
                    Ok(page) => log.land(page),
                    Err(err) => log.fetch_failed(err.message),
                }
                e.mark_chat();
            });
        });
    }

    pub fn load_older(&mut self, handle: &Handle) {
        let Some(id) = self.open_channel else { return };
        let Some((first, generation)) = self.chats.get_mut(&id).and_then(ChatLog::begin_older) else {
            return;
        };
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.messages(id, Some(first)).await;
            h.update(move |e| {
                if let Some(log) = e.session.as_mut().and_then(|s| s.chats.get_mut(&id)) {
                    log.older_landed(generation, got.ok());
                }
                e.mark_chat();
            });
        });
    }

    /// After a reconnect: the open chat catches up now, the others when opened.
    pub fn catch_up(&mut self, handle: &Handle) {
        let open = self.open_channel;
        for (id, log) in self.chats.iter_mut() {
            if Some(*id) != open && log.loaded {
                log.stale = true;
            }
        }
        if let Some(id) = open
            && self.chats.get(&id).is_some_and(|l| !l.fetching)
        {
            self.fetch_latest(id, handle);
        }
    }

    /// Runs an API call and turns a failure into a toast.
    fn call<T: Send + 'static, F: Future<Output = Result<T, ApiError>> + Send + 'static>(
        &self,
        handle: &Handle,
        f: impl FnOnce(Api) -> F,
        done: impl FnOnce(&mut Engine, T) + Send + 'static,
    ) {
        let fut = f(self.api.clone());
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = fut.await;
            h.update(move |e| match got {
                Ok(v) => done(e, v),
                Err(err) => e.toast(err.message),
            });
        });
    }

    pub fn send_message(&self, channel: ChannelId, body: String, reply_to: Option<MessageId>, handle: &Handle) {
        let body = body.trim().to_string();
        if body.is_empty() {
            return;
        }
        self.call(
            handle,
            move |api| async move { api.send_message(channel, &body, None, reply_to).await },
            |e, m: Message| {
                e.added(m);
            },
        );
    }

    /// A file from the phone, copied to `path` by the interface, sent with `body`.
    pub fn upload(&self, channel: ChannelId, path: PathBuf, name: String, mime: String, body: String, handle: &Handle) {
        let pieces = self.server_info.as_ref().is_some_and(|i| i.max_file_bytes.is_some());
        let mime = if mime.is_empty() { "application/octet-stream".to_string() } else { mime };
        let h = handle.clone();
        h.update(|e| e.send(json!({ "type": "uploading", "on": true })));
        self.call(
            handle,
            move |api| async move {
                let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                let source = crate::core::upload::Source::Disk(path.clone());
                let progress = Arc::new(crate::core::upload::Progress::default());
                let up = crate::core::upload::send(&api, source, size, &mime, pieces, progress).await;
                let _ = std::fs::remove_file(&path);
                let up = up?;
                api.send_message(channel, body.trim(), Some((&up.hash, &name)), None).await
            },
            |e, m: Message| {
                e.send(json!({ "type": "uploading", "on": false }));
                e.added(m);
            },
        );
    }

    pub fn react(&self, id: MessageId, emoji: String, on: bool, handle: &Handle) {
        self.call(handle, move |api| async move { api.react(id, &emoji, on).await }, |_, _| {});
    }

    pub fn edit(&self, id: MessageId, body: String, handle: &Handle) {
        self.call(
            handle,
            move |api| async move { api.edit(id, body.trim()).await },
            |e, m: Message| {
                if let Some(log) = e.session.as_mut().and_then(|s| s.chats.get_mut(&m.channel_id)) {
                    log.push(LogOp::Updated(m));
                }
                e.mark_chat();
            },
        );
    }

    pub fn delete(&self, id: MessageId, handle: &Handle) {
        self.call(handle, move |api| async move { api.delete_message(id).await }, |_, _| {});
    }

    /// An attachment saved in full where the interface can open it with another app.
    pub fn file(&self, hash: String, name: String, size: Option<u64>, handle: &Handle) {
        let Some(dir) = PICTURES.get().and_then(|p| p.parent()).map(|d| d.join("files").join(&hash)) else {
            return;
        };
        if !crate::core::cache::is_hash(&hash) {
            return;
        }
        let name = name.replace(['/', '\\'], "_");
        let name = if name.is_empty() { hash.clone() } else { name };
        let path = dir.join(&name);
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = if path.exists() {
                Ok(0)
            } else {
                let _ = std::fs::create_dir_all(&dir);
                api.download_to(&hash, &path, size, &crate::core::upload::Progress::default()).await
            };
            h.update(move |e| match got {
                Ok(_) => e.send(json!({ "type": "file", "hash": hash, "path": path.to_string_lossy(), "name": name })),
                Err(err) => e.toast(err.message),
            });
        });
    }

    /// A picture by its upload hash, put in a file for the interface to draw: `server` is the
    /// server's own picture, anything else an upload (an attachment, an avatar, an emoji).
    pub fn picture(&self, hash: String, kind: String, cache: Cache, handle: &Handle) {
        let Some(dir) = PICTURES.get().cloned() else {
            return;
        };
        if !crate::core::cache::is_hash(&hash) {
            return;
        }
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let path = dir.join(&hash);
            let got = if path.exists() {
                Ok(())
            } else {
                let fetched = if kind == "server" {
                    cache.fetch(&hash, || api.server_icon(&hash)).await
                } else {
                    cache.fetch(&hash, || api.download_picture(&hash)).await
                };
                fetched.and_then(|(bytes, _)| {
                    let tmp = dir.join(format!("{hash}.part"));
                    std::fs::write(&tmp, &*bytes).and_then(|_| std::fs::rename(&tmp, &path)).map_err(|err| ApiError {
                        status: 0,
                        code: ErrorCode::Unknown,
                        message: err.to_string(),
                        body: Value::Null,
                    })
                })
            };
            h.update(move |e| match got {
                Ok(()) => e.send(json!({ "type": "picture", "hash": hash, "path": path.to_string_lossy() })),
                Err(err) => {
                    log::info!("picture {hash}: {}", err.message);
                    e.send(json!({ "type": "picture", "hash": hash, "error": err.message }));
                }
            });
        });
    }
}

fn list_of<T: serde::de::DeserializeOwned>(v: Value) -> Option<Vec<T>> {
    let Value::Array(items) = v else { return None };
    Some(items.into_iter().filter_map(|i| serde_json::from_value(i).ok()).collect())
}

impl Engine {
    /// A message of yours the server took: shown at once, without waiting for its push.
    fn added(&mut self, m: Message) {
        if let Some(log) = self.session.as_mut().and_then(|s| s.chats.get_mut(&m.channel_id)) {
            log.push(LogOp::Added(m));
        }
        self.mark_chat();
    }

    pub(super) fn on_socket(&mut self, socket: u64, ev: realtime::Event) {
        let Some(s) = self.session.as_mut() else {
            return;
        };
        if socket != s.socket {
            return;
        }
        match ev {
            realtime::Event::Up(snap) => {
                let was_down = s.link == Link::Reconnecting && s.ever_up;
                let first = !s.ever_up;
                if let Some(snap) = snap {
                    s.apply_snapshot(*snap);
                }
                s.link = Link::Up;
                s.ever_up = true;
                if first && s.open_channel.is_none() {
                    let text = |c: &&Channel| c.kind == ChannelKind::Text;
                    let pick = s
                        .last_channel
                        .and_then(|id| s.channel(id).filter(text).map(|c| c.id))
                        .or_else(|| s.channels.iter().find(text).map(|c| c.id));
                    if let Some(id) = pick {
                        s.open(id, &self.handle);
                        self.mark_chat();
                    }
                }
                if was_down {
                    let s = self.session.as_mut().expect("checked above");
                    s.refresh_lists(&self.handle);
                    s.catch_up(&self.handle);
                    if let Some(c) = self.call.as_mut() {
                        c.reconnected(self.session.as_ref(), &self.handle);
                        self.mark_voice();
                    }
                }
                if let Some(c) = self.call.as_mut() {
                    c.on_roster(self.session.as_ref(), &self.handle, None);
                }
            }
            realtime::Event::Down => {
                s.link = Link::Reconnecting;
                if let Some(c) = self.call.as_mut() {
                    c.socket_down();
                    self.mark_voice();
                }
            }
            realtime::Event::Rejected => {
                let why = tr!("This sign-in is no longer valid. Sign in again.", "Este login expirou. Entre de novo.");
                self.signed_out(why.into());
                return;
            }
            realtime::Event::Push(v) => {
                if !self.on_push(v) {
                    return;
                }
            }
        }
        self.mark();
    }

    /// Whether the push changed anything drawn.
    fn on_push(&mut self, v: Value) -> bool {
        let kind = v.get("type").and_then(Value::as_str).unwrap_or("").to_string();
        let parse = |key: &str| v.get(key).cloned().unwrap_or(Value::Null);
        let cues = self.voice_sounds();
        let Some(s) = self.session.as_mut() else {
            return false;
        };
        match kind.as_str() {
            "presence" => {
                if let Some(online) = list_of::<UserId>(parse("online")) {
                    s.online = online.into_iter().collect();
                }
            }
            "channels" => {
                if let Some(c) = list_of(parse("channels")) {
                    s.channels = c;
                }
                if let Some(g) = list_of(parse("groups")) {
                    s.groups = g;
                }
            }
            "voice:roster" => {
                let id = v.get("channelId").and_then(Value::as_i64).unwrap_or(-1);
                if let Some(roster) = list_of::<Member>(parse("roster")) {
                    s.rosters.insert(id, roster);
                    if let Some(c) = self.call.as_mut().filter(|c| c.channel == id) {
                        c.on_roster(self.session.as_ref(), &self.handle, cues);
                        self.mark_voice();
                    }
                }
            }
            "message" => {
                if let Ok(m) = serde_json::from_value::<Message>(parse("message")) {
                    let mine = m.user_id == Some(s.me.id);
                    let for_me = m.mentions.contains(&s.me.id) || m.mentions_everyone;
                    let away = s.viewing != Some(m.channel_id);
                    if !mine && away {
                        s.unread.insert(m.channel_id);
                    }
                    if for_me && !mine && away {
                        *s.mentioned.entry(m.channel_id).or_default() += 1;
                    }
                    if !mine {
                        let channel = s.channel(m.channel_id).map(|c| c.name.clone()).unwrap_or_default();
                        let author = s.display_name(m.user_id, m.nickname.as_deref());
                        let body = if m.body.is_empty() { m.attachment_name.clone().unwrap_or_default() } else { m.body.clone() };
                        let note = json!({
                            "type": "message",
                            "channelId": m.channel_id,
                            "channel": channel,
                            "server": s.server_name,
                            "author": author,
                            "body": body,
                            "mention": for_me,
                            "viewing": !away,
                        });
                        self.send(note);
                    }
                    let id = m.channel_id;
                    if let Some(log) = self.session.as_mut().and_then(|s| s.chats.get_mut(&id)) {
                        log.push(LogOp::Added(m));
                        self.mark_chat();
                    }
                }
            }
            "message:updated" => {
                if let Ok(m) = serde_json::from_value::<Message>(parse("message"))
                    && let Some(log) = s.chats.get_mut(&m.channel_id)
                {
                    log.push(LogOp::Updated(m));
                    self.mark_chat();
                }
                return false;
            }
            "message:deleted" => {
                let id = v.get("id").and_then(Value::as_i64).unwrap_or(-1);
                let ch = v.get("channelId").and_then(Value::as_i64).unwrap_or(-1);
                if let Some(log) = s.chats.get_mut(&ch) {
                    log.push(LogOp::Deleted(id));
                    self.mark_chat();
                }
                return false;
            }
            "message:reactions" => {
                let id = v.get("id").and_then(Value::as_i64).unwrap_or(-1);
                let ch = v.get("channelId").and_then(Value::as_i64).unwrap_or(-1);
                if let (Some(log), Some(r)) = (s.chats.get_mut(&ch), list_of::<Reaction>(parse("reactions"))) {
                    log.push(LogOp::Reactions(id, r));
                    self.mark_chat();
                }
                return false;
            }
            "emojis" => {
                if let Some(list) = list_of(parse("emojis")) {
                    s.emojis = list;
                }
            }
            "server" => {
                if let Ok(info) = serde_json::from_value::<ServerInfo>(parse("server")) {
                    if let Some(name) = Some(info.name.clone()).filter(|n| !n.is_empty()) {
                        s.server_name = name;
                    }
                    s.api.note_file_limits(&info);
                    s.server_info = Some(info);
                }
            }
            "user:updated" => {
                if let Ok(u) = serde_json::from_value::<User>(parse("user")) {
                    if u.id == s.me.id {
                        s.me = u.clone();
                    }
                    s.users.insert(u.id, u);
                }
            }
            "accounts" => {
                if let Some(users) = list_of::<User>(parse("users")) {
                    s.users = users.into_iter().map(|u| (u.id, u)).collect();
                }
            }
            "kicked" => {
                let why = tr!("Your account was removed from this server.", "Sua conta foi removida deste servidor.");
                self.signed_out(why.into());
                return false;
            }
            "voice:moved" => {
                let to = v.get("channelId").and_then(Value::as_i64);
                let by = v.get("by").and_then(Value::as_str).unwrap_or(tr!("an admin", "um admin")).to_string();
                self.toast(match to {
                    Some(_) => trf!("{} moved you to another channel.", "{} moveu você para outro canal.", by),
                    None => trf!("{} disconnected you from voice.", "{} desconectou você da voz.", by),
                });
                match to {
                    Some(id) => {
                        let (muted, deafened) = self.call.as_ref().map(|c| (c.muted, c.deafened)).unwrap_or_default();
                        self.leave();
                        self.join_as(id, None, muted, deafened);
                    }
                    None => self.leave(),
                }
                return false;
            }
            _ => return false,
        }
        true
    }
}
