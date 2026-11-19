//! Private conversations, as the desktop's `session/private.rs` keeps them, without GPUI: the key
//! state and the conversation list kept current, what arrives opened, what goes out sealed. The
//! cryptography is the desktop's own `core::crypto`, the bookkeeping of keys its `dm::Vault`.
//! Private calls are left out: their lines in a conversation show, nothing rings.

use super::{ChatLog, LogOp, PICTURES, Session, list_of};
use crate::core::api::{Api, ApiError, Sealed};
use crate::core::crypto;
use crate::core::keystore::KeyFile;
use crate::core::types::*;
use crate::dm::{KeyState, Next, Payload, SealError, Vault};
use crate::engine::{Engine, Handle};
use futures::future::BoxFuture;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

/// How many conversations keep their history: the open one and those opened just before it.
const KEEP_LOGS: usize = 3;
/// The biggest file sent in a private conversation: it is sealed whole, in memory, as the desktop does.
const MAX_FILE: u64 = 25 << 20;

/// One private conversation as the list shows it.
#[derive(Clone, Debug)]
pub struct Dm {
    pub info: Conversation,
    pub peer: UserId,
    /// The last message, opened, as one line.
    pub preview: String,
    pub preview_mine: bool,
}

pub struct Private {
    pub vault: Vault,
    /// A key being made: answers about keys meanwhile only teach the others', or a second key
    /// (and a second recovery key) would be made.
    creating_key: bool,
    pub dms: HashMap<ConversationId, Dm>,
    loaded: bool,
    pub blocked: HashSet<UserId>,
    pub logs: HashMap<ConversationId, ChatLog>,
    recent: Vec<ConversationId>,
    /// The conversation on screen, if one is.
    pub open: Option<ConversationId>,
    /// A message not sent because the other person's key changed: sent once the change is
    /// accepted, if the box still says the same.
    held: Option<(ConversationId, String, Option<MessageId>)>,
}

impl Private {
    pub fn new(api: &Api, me: &User) -> Private {
        Private {
            vault: Vault::new(KeyFile::open(&api.base(), &me.nickname), me.id),
            creating_key: false,
            dms: HashMap::new(),
            loaded: false,
            blocked: HashSet::new(),
            logs: HashMap::new(),
            recent: Vec::new(),
            open: None,
            held: None,
        }
    }

    pub fn peer_of(&self, conversation: ConversationId) -> Option<UserId> {
        self.dms.get(&conversation).map(|d| d.peer)
    }

    fn dm_with(&self, user: UserId) -> Option<ConversationId> {
        self.dms.values().find(|d| d.peer == user).map(|d| d.info.id)
    }

    pub fn unread(&self) -> u32 {
        self.dms.values().map(|d| d.info.unread).sum()
    }

    /// The vault forgets what it saw of messages no longer kept here, so a session left running
    /// for weeks doesn't keep every text it opened. Replays and swaps are still told among all
    /// that is loaded, which is all that is shown.
    fn prune(&mut self) {
        let mut keep: HashSet<MessageId> = self.dms.values().filter_map(|d| d.info.last.as_ref().map(|m| m.id)).collect();
        for log in self.logs.values() {
            keep.extend(log.messages.iter().map(|m| m.id));
            keep.extend(log.pending.iter().filter_map(|op| match op {
                LogOp::Added(m) | LogOp::Updated(m) => Some(m.id),
                _ => None,
            }));
        }
        self.vault.forget_all_but(&keep);
    }

    /// Whatever was opened under the old key state shows again as it now opens.
    fn reopen(&mut self) {
        self.logs.clear();
        self.prune();
    }

    fn forget(&mut self, conversation: ConversationId) {
        self.dms.remove(&conversation);
        self.logs.remove(&conversation);
        if self.open == Some(conversation) {
            self.open = None;
        }
        self.prune();
    }
}

/// What seals a message (or a set of reactions) with the keys as they are now.
type SealWith = Arc<dyn Fn(&mut Vault) -> Result<Sealed, SealError> + Send + Sync>;
/// What sends it, once sealed.
type SendWith<T> = Arc<dyn Fn(Api, Sealed) -> BoxFuture<'static, Result<T, ApiError>> + Send + Sync>;
type Done<T> = Box<dyn FnOnce(&mut Engine, Result<T, ApiError>) + Send>;

fn seal_failed(e: SealError) -> ApiError {
    let code = e.code();
    let message = e.message().map(str::to_string).or_else(|| crate::core::api::friendly(code, &Value::Null)).unwrap_or_default();
    ApiError { status: 0, code, message, body: Value::Null }
}

fn key_state(state: &KeyState) -> (&'static str, Option<&str>) {
    match state {
        KeyState::Loading => ("loading", None),
        KeyState::Ready => ("ready", None),
        KeyState::Locked => ("locked", None),
        KeyState::Failed(why) => ("failed", Some(why.as_str())),
        KeyState::Unsupported => ("unsupported", None),
    }
}

/// One line for the conversation list.
fn preview_of(m: &Message, sender: &str, mine: bool) -> String {
    let p = m.private.clone().unwrap_or_default();
    let text = if let Some(call) = &p.call {
        crate::dm::call_line(call, sender, mine)
    } else if p.out_of_place {
        tr!("Message refused: it isn't where it was sent", "Mensagem recusada: ela não está onde foi enviada").into()
    } else if p.unreadable {
        tr!("Message protected with a key this phone doesn't have", "Mensagem protegida com uma chave que este celular não tem").into()
    } else if m.body.trim().is_empty() {
        p.file.map(|f| format!("📎 {}", f.name)).unwrap_or_default()
    } else {
        m.body.lines().find(|l| !l.trim().is_empty()).unwrap_or("").trim().chars().take(80).collect()
    };
    if mine && p.call.is_none() { trf!("You: {}", "Você: {}", text) } else { text }
}

impl Session {
    fn dm_preview(&mut self, dm: &mut Dm, last: &SealedMessage) {
        let m = self.private.vault.open_message(last, dm.peer);
        dm.preview_mine = last.user_id == self.me.id;
        dm.preview = preview_of(&m, &self.display_name(Some(last.user_id), None), dm.preview_mine);
    }

    /// What the interface draws of private conversations: the key's state and the list.
    pub(super) fn private_state(&self) -> Value {
        let p = &self.private;
        let (state, error) = key_state(&p.vault.state);
        let mut list: Vec<&Dm> = p.dms.values().collect();
        list.sort_by_key(|d| std::cmp::Reverse((d.info.last_at, d.info.id)));
        let conversations: Vec<Value> = list
            .iter()
            .map(|d| {
                json!({
                    "id": d.info.id,
                    "peer": d.peer,
                    "preview": d.preview,
                    "previewMine": d.preview_mine,
                    "lastAt": d.info.last_at,
                    "unread": d.info.unread,
                })
            })
            .collect();
        let open = p.open.and_then(|id| {
            let peer = p.peer_of(id)?;
            Some(json!({
                "id": id,
                "peer": peer,
                "peerHasKey": p.vault.peer_has_key(peer),
                "keyChanged": p.vault.ready() && p.vault.key_changed_for(peer),
                "verified": p.vault.verified(peer),
                "blocked": p.blocked.contains(&peer),
                "held": p.held.as_ref().filter(|(c, ..)| *c == id).map(|(_, body, _)| body.clone()),
            }))
        });
        json!({
            "state": state,
            "error": error,
            "needsBackup": p.vault.needs_backup(),
            "hasRecovery": p.vault.has_recovery(),
            "conversations": conversations,
            "blocked": p.blocked,
            "unread": p.unread(),
            "open": open,
        })
    }

    /// The conversation on screen, for the interface: the channel chat's shape, with what only
    /// a private message has.
    pub fn private_chat(&self) -> Option<Value> {
        let id = self.private.open?;
        let log = self.private.logs.get(&id)?;
        let names = crate::engine::render::Names { emojis: &self.emojis, users: &self.users, me: self.me.id };
        let messages: Vec<Value> = log
            .messages
            .iter()
            .map(|m| {
                let mut v = serde_json::to_value(m).unwrap_or_default();
                v["rendered"] = crate::engine::render::body(&m.body, &names);
                v["author"] = json!(self.display_name(m.user_id, m.nickname.as_deref()));
                v["avatar"] = json!(m.user_id.and_then(|u| self.users.get(&u)).and_then(|u| u.avatar_hash.clone()));
                if let Some(p) = &m.private {
                    let mine = m.user_id == Some(self.me.id);
                    v["private"] = json!({
                        "call": p.call.as_ref().map(|c| crate::dm::call_line(c, &self.display_name(m.user_id, None), mine)),
                        "unreadable": p.unreadable,
                        "outOfPlace": p.out_of_place,
                        "unconfirmed": p.unconfirmed,
                    });
                }
                v
            })
            .collect();
        Some(json!({
            "type": "dmChat",
            "conversationId": id,
            "messages": messages,
            "loaded": log.loaded,
            "moreBefore": log.more_before,
            "loadingOlder": log.loading_older,
            "error": log.error,
        }))
    }

    // Keys.

    /// Asks the server what keys there are, makes this account's first one if it has none, then
    /// loads the conversations. Again after every reconnect: a key another device of yours made
    /// meanwhile is learned this way too.
    pub(super) fn start_private(&mut self, handle: &Handle) {
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.keys().await;
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                match got {
                    Ok(reply) if s.private.creating_key => s.private.vault.learn(reply.keys),
                    Ok(reply) => {
                        if s.private.vault.on_keys(reply) == Next::Create {
                            s.create_key(&e.handle);
                        }
                    }
                    Err(err) if err.status == 404 => s.private.vault.state = KeyState::Unsupported,
                    Err(err) if !s.private.vault.ready() => s.private.vault.state = KeyState::Failed(err.message),
                    Err(_) => {}
                }
                let s = e.session.as_mut().expect("checked above");
                s.load_dms(&e.handle);
                e.mark();
            });
        });
    }

    /// Makes and publishes this account's identity key. The recovery key stays here, sealed,
    /// until the person says they kept it somewhere safe.
    fn create_key(&mut self, handle: &Handle) {
        let (identity, recovery, wrapped) = Vault::create();
        let (api, public) = (self.api.clone(), identity.public_b64());
        self.private.vault.state = KeyState::Loading;
        self.private.creating_key = true;
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.publish_key(&public, &wrapped).await;
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                s.private.creating_key = false;
                match got {
                    Ok(key) => {
                        s.private.vault.adopt(key, identity, &recovery, false);
                        s.private.reopen();
                        s.reload_open_dm(&e.handle);
                        let s = e.session.as_mut().expect("checked above");
                        s.load_dms(&e.handle);
                    }
                    Err(err) => s.private.vault.state = KeyState::Failed(err.message),
                }
                e.mark();
                e.mark_chat();
            });
        });
    }

    fn reload_open_dm(&mut self, handle: &Handle) {
        if let Some(id) = self.private.open {
            self.fetch_dm(id, handle);
        }
    }

    // Conversations.

    /// The conversation list, again; tried again shortly while none has arrived yet.
    pub(super) fn load_dms(&self, handle: &Handle) {
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let mut wait = Duration::from_secs(2);
            let got = loop {
                let got = api.dms().await;
                let retry = matches!(&got, Err(e) if e.status != 404 && e.status != 401) && wait <= Duration::from_secs(16);
                if !retry {
                    break got;
                }
                tokio::time::sleep(wait).await;
                wait *= 2;
            };
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                let Ok(list) = got else { return };
                s.private.vault.learn(list.keys);
                s.private.blocked = list.blocked.into_iter().collect();
                let me = s.me.id;
                let mut dms = HashMap::new();
                for info in list.conversations {
                    let peer = info.peer(me);
                    let mut dm = Dm { peer, preview: String::new(), preview_mine: false, info };
                    if let Some(last) = dm.info.last.clone() {
                        s.dm_preview(&mut dm, &last);
                    }
                    // The one on screen has nothing unread, whatever the list says.
                    if s.private.open == Some(dm.info.id) {
                        dm.info.unread = 0;
                    }
                    dms.insert(dm.info.id, dm);
                }
                // A conversation opened here but not written in yet is not on the server's list.
                for (id, dm) in std::mem::take(&mut s.private.dms) {
                    dms.entry(id).or_insert(dm);
                }
                s.private.dms = dms;
                s.private.loaded = true;
                s.private.prune();
                // A notification's conversation, from before this server was on screen.
                let here = |(url, name, _): &(String, String, ConversationId)| {
                    crate::core::settings::same_server(url, &s.api.base()) && name.eq_ignore_ascii_case(&s.me.nickname)
                };
                if let Some((_, _, id)) = e.pending_dm.take_if(|p| here(p)) {
                    s.open_dm(id, &e.handle);
                    e.mark_chat();
                }
                e.mark();
            });
        });
    }

    /// The conversation with `user` on screen, opened on the server first if there is none.
    pub fn open_dm_with(&mut self, user: UserId, handle: &Handle) {
        if let Some(id) = self.private.dm_with(user) {
            self.open_dm(id, handle);
            return;
        }
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.open_dm(user).await;
            h.update(move |e| match got {
                Ok(info) => {
                    let Some(s) = e.session.as_mut() else { return };
                    let id = info.id;
                    let peer = info.peer(s.me.id);
                    s.private.dms.entry(id).or_insert(Dm { info, peer, preview: String::new(), preview_mine: false });
                    s.private.vault.pin(peer);
                    s.open_dm(id, &e.handle);
                    e.mark();
                    e.mark_chat();
                }
                Err(err) => e.toast(err.message),
            });
        });
    }

    pub fn open_dm(&mut self, id: ConversationId, handle: &Handle) {
        self.private.open = Some(id);
        self.private.recent.retain(|c| *c != id);
        self.private.recent.push(id);
        if self.private.recent.len() > KEEP_LOGS {
            let gone: Vec<ConversationId> = self.private.recent.drain(..self.private.recent.len() - KEEP_LOGS).collect();
            self.private.logs.retain(|c, _| !gone.contains(c));
            self.private.prune();
        }
        let log = self.private.logs.entry(id).or_insert_with(ChatLog::fresh);
        if (!log.loaded || log.stale) && !log.fetching {
            self.fetch_dm(id, handle);
        }
        self.mark_dm_read(id);
    }

    pub fn close_dm(&mut self) {
        self.private.open = None;
    }

    fn fetch_dm(&mut self, id: ConversationId, handle: &Handle) {
        let log = self.private.logs.entry(id).or_insert_with(ChatLog::fresh);
        log.fetching = true;
        log.stale = false;
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.dm_messages(id, None).await;
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                match got {
                    Ok(page) => {
                        let page = s.open_history(id, page);
                        if let Some(log) = s.private.logs.get_mut(&id) {
                            log.land(page);
                        }
                        s.mark_dm_read(id);
                        s.private.prune();
                    }
                    Err(err) => {
                        if let Some(log) = s.private.logs.get_mut(&id) {
                            log.fetch_failed(err.message);
                        }
                    }
                }
                e.mark();
                e.mark_chat();
            });
        });
    }

    pub fn dm_older(&mut self, handle: &Handle) {
        let Some(id) = self.private.open else { return };
        let Some((first, generation)) = self.private.logs.get_mut(&id).and_then(ChatLog::begin_older) else {
            return;
        };
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got = api.dm_messages(id, Some(first)).await;
            h.update(move |e| {
                let Some(s) = e.session.as_mut() else { return };
                let page = got.ok().map(|page| s.open_history(id, page));
                if let Some(log) = s.private.logs.get_mut(&id) {
                    log.older_landed(generation, page);
                }
                e.mark_chat();
            });
        });
    }

    /// History as the server pages it, opened.
    fn open_history(&mut self, conversation: ConversationId, page: DmHistory) -> History {
        let p = &mut self.private;
        p.vault.learn(page.keys);
        let me = self.me.id;
        let peer = p.peer_of(conversation).or_else(|| page.messages.iter().map(|m| m.user_id).find(|u| *u != me)).unwrap_or(me);
        p.vault.pin(peer);
        History { messages: page.messages.iter().map(|m| p.vault.open_message(m, peer)).collect(), pinned: Vec::new() }
    }

    /// Moves your read mark to the newest message here, on the server too (so your other devices
    /// clear their badge).
    fn mark_dm_read(&mut self, conversation: ConversationId) {
        let p = &mut self.private;
        let newest = p
            .logs
            .get(&conversation)
            .and_then(|l| l.messages.last().map(|m| m.id))
            .into_iter()
            .chain(p.dms.get(&conversation).and_then(|d| d.info.last.as_ref().map(|m| m.id)))
            .max();
        let Some(dm) = p.dms.get_mut(&conversation) else { return };
        dm.info.unread = 0;
        let Some(newest) = newest.filter(|n| *n > dm.info.last_read_id) else { return };
        dm.info.last_read_id = newest;
        let api = self.api.clone();
        crate::core::runtime().spawn(async move {
            let _ = api.read_dm(conversation, newest).await;
        });
    }

    /// Counts again what is unread after the read mark moved on another device.
    fn recount(&mut self, conversation: ConversationId) {
        let me = self.me.id;
        let p = &mut self.private;
        let Some(dm) = p.dms.get_mut(&conversation) else { return };
        let read = dm.info.last_read_id;
        let counted = p.logs.get(&conversation).filter(|l| l.loaded && !l.stale).map(|l| {
            l.messages
                .iter()
                .filter(|m| m.id > read && m.user_id != Some(me))
                .filter(|m| !m.private.as_ref().and_then(|p| p.call.as_ref()).is_some_and(|c| c.outcome == "ended"))
                .count() as u32
        });
        dm.info.unread = match counted {
            Some(n) => n,
            None if dm.info.last.as_ref().is_none_or(|m| m.id <= read) => 0,
            None => dm.info.unread,
        };
    }

    /// After a reconnect: the open conversation catches up now, the others when opened.
    pub(super) fn dm_catch_up(&mut self, handle: &Handle) {
        let open = self.private.open;
        for (id, log) in self.private.logs.iter_mut() {
            if Some(*id) != open && log.loaded {
                log.stale = true;
            }
        }
        if let Some(id) = open
            && self.private.logs.get(&id).is_some_and(|l| !l.fetching)
        {
            self.fetch_dm(id, handle);
        }
    }

    // Recovery key.

    /// The recovery key typed on a phone that does not have this account's key yet.
    pub fn unlock(&mut self, typed: &str, handle: &Handle) -> bool {
        if !self.private.vault.unlock(typed) {
            return false;
        }
        self.private.reopen();
        self.reload_open_dm(handle);
        self.load_dms(handle);
        true
    }

    /// Starts over with a new key, for someone who lost their recovery key and every device that
    /// had theirs. What was sealed to the old key stays unreadable for them.
    pub fn reset_keys(&mut self, handle: &Handle) {
        self.create_key(handle);
    }
}

impl Engine {
    fn session_mut(&mut self) -> Option<&mut Session> {
        self.session.as_mut()
    }

    /// The commands about private conversations; false if it is another.
    pub fn private_command(&mut self, kind: &str, cmd: &Value) -> bool {
        let int = |k: &str| cmd.get(k).and_then(Value::as_i64);
        let text = |k: &str| cmd.get(k).and_then(Value::as_str).unwrap_or_default().to_string();
        let flag = |k: &str| cmd.get(k).and_then(Value::as_bool).unwrap_or(false);
        let h = self.handle.clone();
        match kind {
            "dmWith" => {
                if let (Some(user), Some(s)) = (int("userId"), self.session_mut()) {
                    s.open_dm_with(user, &h);
                }
            }
            "dmOpen" => {
                if let (Some(id), Some(s)) = (int("id"), self.session_mut()) {
                    s.open_dm(id, &h);
                }
            }
            "dmClose" => {
                if let Some(s) = self.session_mut() {
                    s.close_dm();
                }
            }
            "dmOlder" => {
                if let Some(s) = self.session_mut() {
                    s.dm_older(&h);
                }
            }
            "dmSend" => {
                if let Some(id) = int("id") {
                    self.send_dm(id, text("body"), int("replyTo"), None);
                }
            }
            "dmUpload" => {
                if let Some(id) = int("id") {
                    let file = (PathBuf::from(text("path")), text("name"), text("mime"));
                    self.send_dm(id, text("body"), None, Some(file));
                }
            }
            "dmEdit" => {
                if let Some(id) = int("id") {
                    self.edit_dm(id, text("body"));
                }
            }
            "dmDelete" => {
                if let (Some(id), Some(s)) = (int("id"), self.session.as_ref()) {
                    s.call(&h, move |api| async move { api.delete_dm(id).await }, |_, _| {});
                }
            }
            "dmReact" => {
                if let Some(id) = int("id") {
                    self.react_dm(id, text("emoji"));
                }
            }
            "dmPicture" => {
                if let (Some(conv), Some(s)) = (int("conversationId"), self.session.as_ref()) {
                    s.dm_attachment(conv, text("hash"), true, &h);
                }
            }
            "dmFile" => {
                if let (Some(conv), Some(s)) = (int("conversationId"), self.session.as_ref()) {
                    s.dm_attachment(conv, text("hash"), false, &h);
                }
            }
            "dmUnlock" => {
                let ok = self.session.as_mut().is_some_and(|s| s.unlock(&text("key"), &h));
                self.send(json!({ "type": "unlocked", "ok": ok }));
                self.mark_chat();
            }
            "dmReset" => {
                if let Some(s) = self.session_mut() {
                    s.reset_keys(&h);
                }
            }
            "dmRecovery" => {
                let key = self.session.as_ref().and_then(|s| s.private.vault.recovery()).map(|k| k.to_string());
                self.send(json!({ "type": "recoveryKey", "key": key }));
            }
            "dmNewRecovery" => self.new_recovery(),
            "dmBackedUp" => {
                if let Some(s) = self.session_mut() {
                    s.private.vault.mark_backed_up();
                }
            }
            "dmSafety" => {
                if let (Some(peer), Some(s)) = (int("userId"), self.session.as_ref()) {
                    let v = &s.private.vault;
                    let number = v.safety_number(peer);
                    self.send(json!({ "type": "safetyNumber", "userId": peer, "number": number, "verified": v.verified(peer) }));
                }
            }
            "dmVerify" => {
                if let (Some(peer), Some(s)) = (int("userId"), self.session_mut()) {
                    s.private.vault.set_verified(peer, flag("on"));
                }
            }
            "dmAccept" => {
                let Some(peer) = int("userId") else { return true };
                let Some(s) = self.session_mut() else { return true };
                s.private.vault.accept_key(peer);
                // Held back by the change: sent now, as it was.
                let held = s.private.held.take().filter(|(c, ..)| s.private.peer_of(*c) == Some(peer));
                self.mark_chat();
                if let Some((conv, body, reply)) = held.filter(|_| flag("send")) {
                    self.send_dm(conv, body, reply, None);
                }
            }
            "dmBlock" => {
                if let (Some(user), Some(s)) = (int("userId"), self.session.as_ref()) {
                    let on = flag("on");
                    s.call(
                        &h,
                        move |api| async move { api.set_blocked(user, on).await },
                        |e, list| {
                            if let Some(s) = e.session.as_mut() {
                                s.private.blocked = list.into_iter().collect();
                            }
                        },
                    );
                }
            }
            "dmClear" => {
                if let (Some(id), Some(s)) = (int("id"), self.session.as_ref()) {
                    s.call(
                        &h,
                        move |api| async move { api.clear_dm(id).await },
                        move |e, _| {
                            if let Some(s) = e.session.as_mut() {
                                s.private.forget(id);
                            }
                            e.mark_chat();
                        },
                    );
                }
            }
            _ => return false,
        }
        self.mark();
        true
    }

    fn new_recovery(&mut self) {
        let Some(s) = self.session.as_ref() else { return };
        let Some((id, recovery, wrapped)) = s.private.vault.new_recovery() else {
            self.toast(seal_failed(SealError::NoKey).message);
            return;
        };
        let api = s.api.clone();
        self.spawn(async move { api.rewrap_key(id, &wrapped).await }, move |e, got| match got {
            Ok(_) => {
                let shown = recovery.display();
                if let Some(s) = e.session.as_mut() {
                    s.private.vault.set_recovery(&recovery, false);
                }
                e.send(json!({ "type": "recoveryKey", "key": shown }));
                e.mark();
            }
            Err(err) => e.toast(err.message),
        });
    }

    /// Seals with the current keys and sends; if a key changed meanwhile, learns the new one from
    /// the refusal and seals again, once. Only to a key accepted here: a changed one nobody here
    /// accepted comes back as `KeyNotConfirmed`, with nothing sent.
    fn send_sealed<T: Send + 'static>(&mut self, seal: SealWith, send: SendWith<T>, attempt: u8, done: Done<T>) {
        let Some(s) = self.session.as_mut() else { return };
        let sealed = match seal(&mut s.private.vault) {
            Ok(sealed) => sealed,
            Err(err) => {
                done(self, Err(seal_failed(err)));
                return;
            }
        };
        let api = s.api.clone();
        let job = send.clone();
        self.spawn(async move { job(api, sealed).await }, move |e, got| match got {
            Err(err) if err.code == ErrorCode::StaleKey && attempt == 0 => {
                let keys = err.body.get("keys").cloned().and_then(list_of::<PublicKeyInfo>).unwrap_or_default();
                if let Some(s) = e.session.as_mut() {
                    keys.into_iter().for_each(|k| s.private.vault.key_changed(k));
                }
                e.send_sealed(seal, send, 1, done);
            }
            Err(err) if err.code == ErrorCode::StaleKey => done(e, Err(seal_failed(SealError::PeerHasNoKey))),
            got => done(e, got),
        });
    }

    /// Sends a message, sealing its file first if it has one. The server's answer is shown at
    /// once; its echo on the socket, if it comes, is the same message and is not shown twice.
    fn send_dm(
        &mut self,
        conversation: ConversationId,
        body: String,
        reply_to: Option<MessageId>,
        file: Option<(PathBuf, String, String)>,
    ) {
        let body = body.trim().to_string();
        if body.is_empty() && file.is_none() {
            return;
        }
        let Some(s) = self.session.as_ref() else { return };
        let Some(peer) = s.private.peer_of(conversation) else { return };
        // What it answers travels sealed, with a preview the server can't make.
        let reply = reply_to.and_then(|id| s.private.logs.get(&conversation)?.messages.iter().find(|m| m.id == id)).map(|m| ReplyTo {
            id: m.id,
            user_id: m.user_id,
            body: m.body.chars().take(200).collect(),
            attachment_name: m.attachment_name.clone(),
            deleted: false,
        });
        let api = s.api.clone();
        let uploading = file.is_some();
        if uploading {
            self.send(json!({ "type": "uploading", "on": true }));
        }
        let caption = body.clone();
        // The file sealed and uploaded first (off the engine), then the message sealed here.
        self.spawn(
            async move {
                let Some((path, name, mime)) = file else { return Ok(None) };
                let read = tokio::task::spawn_blocking(move || {
                    let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                    let bytes = if size > MAX_FILE { None } else { std::fs::read(&path).ok() };
                    let _ = std::fs::remove_file(&path);
                    bytes
                })
                .await
                .ok()
                .flatten();
                let Some(bytes) = read else {
                    return Err(ApiError {
                        status: 0,
                        code: ErrorCode::Unknown,
                        message: tr!("Files in private conversations go up to 25 MB.", "Arquivos em conversas privadas vão até 25 MB.")
                            .into(),
                        body: Value::Null,
                    });
                };
                let size = bytes.len() as u64;
                let (sealed, key) = crypto::seal_file(&bytes);
                let up = api.upload_sealed(conversation, sealed).await?;
                let mime = if mime.is_empty() { "application/octet-stream".to_string() } else { mime };
                Ok(Some((up.hash, SealedFile { key: crypto::b64(key.as_slice()), name, mime, size })))
            },
            move |e, got: Result<Option<(String, SealedFile)>, ApiError>| {
                let attachment = match got {
                    Ok(a) => a,
                    Err(err) => {
                        e.send(json!({ "type": "uploading", "on": false }));
                        e.toast(err.message);
                        return;
                    }
                };
                let hash = attachment.as_ref().map(|(h, _)| h.clone());
                let payload = Payload { body: body.clone(), file: attachment.map(|(_, f)| f), reply, ..Default::default() };
                e.send_sealed(
                    Arc::new(move |v| v.seal_message(conversation, peer, &payload)),
                    Arc::new(move |api, sealed| {
                        let hash = hash.clone();
                        Box::pin(async move { api.send_dm(conversation, &sealed, hash.as_deref()).await })
                    }),
                    0,
                    Box::new(move |e, got| {
                        if uploading {
                            e.send(json!({ "type": "uploading", "on": false }));
                        }
                        match got {
                            Ok(m) => e.dm_sent(m),
                            Err(err) => {
                                // Held for the other person's key change: the box keeps the text, and
                                // the conversation offers to accept the new key and send it.
                                if err.code == ErrorCode::KeyNotConfirmed
                                    && let Some(s) = e.session.as_mut()
                                {
                                    s.private.held = Some((conversation, caption.clone(), reply_to));
                                    e.mark();
                                }
                                e.toast(err.message);
                                if !caption.is_empty() {
                                    e.send(json!({ "type": "sendFailed", "channelId": conversation, "private": true, "body": caption }));
                                }
                            }
                        }
                    }),
                );
            },
        );
    }

    /// What the server took of yours, shown without waiting for its echo.
    fn dm_sent(&mut self, m: SealedMessage) {
        let Some(info) = self.session.as_ref().and_then(|s| s.private.dms.get(&m.conversation_id)).map(|d| d.info.clone()) else {
            return;
        };
        self.dm_arrived(m, info);
    }

    /// Replaces what one of your messages says, keeping its file and what it answers.
    fn edit_dm(&mut self, id: MessageId, body: String) {
        let Some(s) = self.session.as_ref() else { return };
        let Some((conversation, message)) =
            s.private.logs.iter().find_map(|(c, l)| l.messages.iter().find(|m| m.id == id).map(|m| (*c, m.clone())))
        else {
            return;
        };
        let Some(peer) = s.private.peer_of(conversation) else { return };
        let private = message.private.clone().unwrap_or_default();
        let payload = Payload {
            body: body.trim().to_string(),
            file: private.file,
            reply: message.reply.clone(),
            mid: private.mid,
            rev: private.rev + 1,
            of: Some(id),
        };
        self.send_sealed(
            Arc::new(move |v| v.seal_message(conversation, peer, &payload)),
            Arc::new(move |api, sealed| Box::pin(async move { api.edit_dm(id, &sealed).await })),
            0,
            Box::new(|e, got| match got {
                Ok(m) => e.dm_updated(m),
                Err(err) => e.toast(err.message),
            }),
        );
    }

    /// Adds your `emoji` to a message, or takes it off if it is there.
    fn react_dm(&mut self, id: MessageId, emoji: String) {
        let Some(s) = self.session.as_ref() else { return };
        let me = s.me.id;
        let Some((conversation, message)) =
            s.private.logs.iter().find_map(|(c, l)| l.messages.iter().find(|m| m.id == id).map(|m| (*c, m.clone())))
        else {
            return;
        };
        let Some(peer) = s.private.peer_of(conversation) else { return };
        let mut mine: Vec<String> = message.reactions.iter().filter(|r| r.user_ids.contains(&me)).map(|r| r.emoji.clone()).collect();
        match mine.iter().position(|e| *e == emoji) {
            Some(i) => {
                mine.remove(i);
            }
            None => mine.push(emoji),
        }
        if mine.is_empty() {
            let h = self.handle.clone();
            s.call(&h, move |api| async move { api.react_dm(id, None).await }, |_, _| {});
            return;
        }
        self.send_sealed(
            Arc::new(move |v| v.seal_reactions(conversation, peer, id, &mine)),
            Arc::new(move |api, sealed| Box::pin(async move { api.react_dm(id, Some(&sealed)).await })),
            0,
            Box::new(|e, got| {
                if let Err(err) = got {
                    e.toast(err.message);
                }
            }),
        );
    }

    fn dm_updated(&mut self, m: SealedMessage) {
        let Some(s) = self.session.as_mut() else { return };
        let Some(peer) = s.private.peer_of(m.conversation_id) else { return };
        let opened = s.private.vault.open_message(&m, peer);
        if let Some(dm) = s.private.dms.get(&m.conversation_id).cloned()
            && dm.info.last.as_ref().is_some_and(|l| l.id == m.id)
        {
            let mut dm = dm;
            dm.info.last = Some(m.clone());
            s.dm_preview(&mut dm, &m);
            s.private.dms.insert(m.conversation_id, dm);
        }
        if let Some(log) = s.private.logs.get_mut(&m.conversation_id) {
            log.push(LogOp::Updated(opened));
        }
        self.mark();
        self.mark_chat();
    }

    fn dm_arrived(&mut self, m: SealedMessage, info: Conversation) {
        let visible = self.visible;
        let notify = self.notify;
        let Some(s) = self.session.as_mut() else { return };
        let me = s.me.id;
        let id = info.id;
        let peer = info.peer(me);
        let mine = m.user_id == me;
        let away = !visible || s.private.open != Some(id);
        let mut dm = s.private.dms.remove(&id).unwrap_or(Dm { peer, preview: String::new(), preview_mine: false, info: info.clone() });
        // Heard twice: the server's answer to a send, then its echo on the socket.
        let again = dm.info.last.as_ref().is_some_and(|l| l.id == m.id) || s.private.logs.get(&id).is_some_and(|l| l.find(m.id).is_ok());
        dm.info.last_at = dm.info.last_at.max(info.last_at).max(m.created_at);
        s.private.vault.pin(peer);
        // A message that waited for its key can arrive after a newer one: it is not the last.
        if dm.info.last.as_ref().is_none_or(|l| l.id <= m.id) {
            dm.info.last = Some(m.clone());
            s.dm_preview(&mut dm, &m);
        }
        // A call both of you were on is not news to either; a missed one is.
        let answered = m.meta.as_ref().is_some_and(|c| c.outcome == "ended");
        if !mine && away && !answered && !again {
            dm.info.unread += 1;
        }
        s.private.dms.insert(id, dm);
        let opened = s.private.vault.open_message(&m, peer);
        if let Some(log) = s.private.logs.get_mut(&id) {
            log.push(LogOp::Added(opened.clone()));
            s.private.prune();
        }
        if !mine && !away {
            s.mark_dm_read(id);
        }
        if !mine && !again && !visible && notify && !answered {
            let author = s.display_name(Some(m.user_id), None);
            let (url, username, server) = (s.api.base(), s.me.nickname.clone(), s.server_name.clone());
            self.send(json!({
                "type": "privateMessage",
                "server": url,
                "username": username,
                "serverName": server,
                "conversationId": id,
                "messageId": m.id,
                "author": author,
            }));
        }
        self.mark();
        self.mark_chat();
    }

    /// Handles a push about private conversations; false if it is about something else.
    pub(super) fn on_private_push(&mut self, kind: &str, v: &Value) -> bool {
        let field = |key: &str| v.get(key).cloned().unwrap_or(Value::Null);
        let int = |key: &str| v.get(key).and_then(Value::as_i64).unwrap_or(-1);
        let Some(s) = self.session.as_mut() else { return false };
        match kind {
            "keys:changed" => {
                if let Ok(key) = serde_json::from_value::<PublicKeyInfo>(field("key")) {
                    let mine = key.user_id == s.me.id;
                    s.private.vault.key_changed(key);
                    if mine && !s.private.vault.ready() {
                        // Made on another device: ask for it, so the recovery key can open it here.
                        s.start_private(&self.handle);
                    }
                }
            }
            "dm:message" => {
                let (Ok(m), Ok(info)) = (
                    serde_json::from_value::<SealedMessage>(field("message")),
                    serde_json::from_value::<Conversation>(field("conversation")),
                ) else {
                    return true;
                };
                let unknown = s.private.vault.unknown(m.sender_key.into_iter().chain(m.recipient_key));
                if unknown.is_empty() {
                    self.dm_arrived(m, info);
                } else {
                    // Sealed to a key not seen yet: fetch it, then open the message. A failed fetch
                    // is tried again a couple of times, or the message would stay unreadable here.
                    let api = s.api.clone();
                    self.spawn(
                        async move {
                            for wait in [0, 1, 3] {
                                tokio::time::sleep(Duration::from_secs(wait)).await;
                                if let Ok(got) = api.lookup_keys(&unknown).await {
                                    return got;
                                }
                            }
                            Vec::new()
                        },
                        move |e, keys| {
                            if let Some(s) = e.session.as_mut() {
                                s.private.vault.learn(keys);
                            }
                            e.dm_arrived(m, info);
                        },
                    );
                }
                return true;
            }
            "dm:updated" => {
                if let Ok(m) = serde_json::from_value::<SealedMessage>(field("message")) {
                    self.dm_updated(m);
                }
                return true;
            }
            "dm:deleted" => {
                if let Some(log) = s.private.logs.get_mut(&int("conversationId")) {
                    log.push(LogOp::Deleted(int("id")));
                }
                self.mark_chat();
            }
            "dm:reactions" => {
                let (id, conversation) = (int("id"), int("conversationId"));
                if let (Some(peer), Some(sealed)) = (s.private.peer_of(conversation), list_of::<SealedReaction>(field("reactions"))) {
                    let reactions = s.private.vault.open_reactions(conversation, peer, id, &sealed);
                    if let Some(log) = s.private.logs.get_mut(&conversation) {
                        log.push(LogOp::Reactions(id, reactions));
                    }
                }
                self.mark_chat();
            }
            "dm:read" => {
                let conversation = int("conversationId");
                if let Some(dm) = s.private.dms.get_mut(&conversation) {
                    dm.info.last_read_id = dm.info.last_read_id.max(int("lastReadId"));
                    s.recount(conversation);
                }
            }
            // Deleted here or on another device of yours.
            "dm:cleared" => {
                s.private.forget(int("conversationId"));
                self.mark_chat();
            }
            "dm:blocks" => {
                if let Some(list) = list_of::<UserId>(field("blocked")) {
                    s.private.blocked = list.into_iter().collect();
                }
            }
            // Private calls aren't on the phone yet: their lines show in the conversation, and a
            // ring is left to the computer.
            "call:state" | "call:roster" => return true,
            _ => return false,
        }
        self.mark();
        true
    }
}

impl Session {
    /// An attachment of a private conversation, opened: a picture for the chat to draw, or a file
    /// for another app. Its key is in the message, sealed; the server has only ciphertext. What is
    /// opened goes in the app's own storage, which nothing else on the phone can read, and is
    /// cleared when the app starts.
    pub fn dm_attachment(&self, conversation: ConversationId, hash: String, picture: bool, handle: &Handle) {
        if !crate::core::cache::is_hash(&hash) {
            return;
        }
        let Some(file) = self
            .private
            .logs
            .get(&conversation)
            .and_then(|l| l.messages.iter().find(|m| m.attachment_hash.as_deref() == Some(hash.as_str())))
            .and_then(|m| m.private.as_ref()?.file.clone())
        else {
            return;
        };
        let Some(dir) = PICTURES.get().and_then(|p| p.parent()).map(|d| d.join("private")) else { return };
        let name = file.name.replace(['/', '\\'], "_");
        let path = if picture { dir.join(&hash) } else { dir.join("files").join(&hash).join(if name.is_empty() { &hash } else { &name }) };
        let api = self.api.clone();
        let h = handle.clone();
        crate::core::runtime().spawn(async move {
            let got: Result<(), String> = async {
                if path.exists() {
                    return Ok(());
                }
                let key: [u8; 32] = crypto::unb64(&file.key).and_then(|k| k.try_into().ok()).ok_or("bad key")?;
                let (sealed, _) = if picture {
                    api.download_sealed_picture(conversation, &hash).await
                } else {
                    api.download_sealed(conversation, &hash).await
                }
                .map_err(|e| e.message)?;
                let plain = crypto::open_file(&key, &sealed).ok_or_else(|| {
                    tr!("The file didn't open: it isn't the one that was sent.", "O arquivo não abriu: não é o que foi enviado.")
                        .to_string()
                })?;
                let parent = path.parent().map(PathBuf::from).unwrap_or_default();
                std::fs::create_dir_all(&parent).map_err(|e| e.to_string())?;
                let tmp = parent.join(format!("{hash}.part"));
                std::fs::write(&tmp, plain).and_then(|_| std::fs::rename(&tmp, &path)).map_err(|e| e.to_string())
            }
            .await;
            h.update(move |e| match (got, picture) {
                (Ok(()), true) => e.send(json!({ "type": "picture", "hash": hash, "path": path.to_string_lossy() })),
                (Ok(()), false) => e.send(json!({ "type": "file", "hash": hash, "path": path.to_string_lossy(), "name": name })),
                (Err(err), true) => e.send(json!({ "type": "picture", "hash": hash, "error": err })),
                (Err(err), false) => e.toast(err),
            });
        });
    }
}

/// Opened attachments of private conversations don't outlive the run that opened them.
pub fn clear_opened(cache_dir: &std::path::Path) {
    let _ = std::fs::remove_dir_all(cache_dir.join("private"));
}
