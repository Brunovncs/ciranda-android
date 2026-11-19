//! Private calls, as the desktop's `media/call.rs` and `session/private.rs` follow them: ringing
//! the other person of a conversation, being rung (on the server on screen, or on one listened to
//! in the background, see `watch`), answering, declining, and the call ending. Once picked up, a
//! call is the voice channel's `call::Call` in another place, its frames sealed with a key the
//! caller makes and seals to the conversation as a message is: the server passes it on and cannot
//! open it.

use super::Engine;
use super::call::{self, Call};
use super::session::Link;
use super::session::private::seal_failed;
use crate::core::crypto;
use crate::core::realtime::RequestError;
use crate::core::settings::same_server;
use crate::core::types::*;
use crate::dm::KeyState;
use crate::media::audio::{self, Cue};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::time::{Duration, Instant, SystemTime};

/// How long an answer waits for its server to be up with the call in hand: one signed in to just
/// for it, or the one on screen woken from the background. The server rings for 45 s in all.
const ANSWER_WITHIN: Duration = Duration::from_secs(25);

/// A call ringing for you, wherever it rings.
#[derive(Clone, Debug, PartialEq)]
pub struct Ring {
    pub url: String,
    pub username: String,
    /// The server's name, to say where it rings when that is not the one on screen.
    pub server: String,
    pub conversation: ConversationId,
    pub caller: UserId,
    pub name: String,
    pub avatar: Option<String>,
}

impl Ring {
    fn is(&self, url: &str, username: &str, conversation: ConversationId) -> bool {
        self.conversation == conversation && same_server(&self.url, url) && self.username.eq_ignore_ascii_case(username)
    }

    pub fn state(&self, answering: bool) -> Value {
        json!({
            "server": self.url,
            "username": self.username,
            "serverName": self.server,
            "conversationId": self.conversation,
            "callerId": self.caller,
            "name": self.name,
            "avatar": self.avatar,
            "answering": answering,
        })
    }
}

/// An answer given before the call could be picked up: its server signing in, or waking.
pub struct Answering {
    url: String,
    username: String,
    conversation: ConversationId,
    until: Instant,
    /// The keys the call was sealed with were asked for once already.
    asked_keys: bool,
}

/// A server listened to in the background, as a call there is told of.
pub struct Elsewhere<'a> {
    pub url: &'a str,
    pub username: &'a str,
    pub server: &'a str,
    pub me: Option<UserId>,
    pub names: &'a HashMap<UserId, String>,
}

fn ended() -> String {
    tr!("That call has ended.", "Essa chamada já terminou.").into()
}

fn unopenable() -> String {
    tr!("That call couldn't be opened on this phone.", "Essa chamada não pôde ser aberta neste celular.").into()
}

impl Engine {
    /// Whether the server on screen is this one.
    fn here(&self, url: &str, username: &str) -> bool {
        self.session.as_ref().is_some_and(|s| same_server(&s.api.base(), url) && s.me.nickname.eq_ignore_ascii_case(username))
    }

    /// The ring, if it is for `conversation` on the server on screen.
    fn ring_here(&self, conversation: ConversationId) -> bool {
        let Some(s) = self.session.as_ref() else { return false };
        self.ring.as_ref().is_some_and(|r| r.is(&s.api.base(), &s.me.nickname, conversation))
    }

    /// Whether the ring is being picked up.
    pub(super) fn ring_answering(&self) -> bool {
        match (&self.ring, &self.answering) {
            (Some(r), Some(a)) => r.is(&a.url, &a.username, a.conversation),
            _ => false,
        }
    }

    /// The commands about private calls; false if it is another.
    pub(super) fn call_command(&mut self, kind: &str, cmd: &Value) -> bool {
        let int = |k: &str| cmd.get(k).and_then(Value::as_i64);
        let text = |k: &str| cmd.get(k).and_then(Value::as_str).map(str::to_string);
        // A ring's server and account; without them, the server on screen.
        let at = || text("server").zip(text("username")).or_else(|| self.session.as_ref().map(|s| (s.api.base(), s.me.nickname.clone())));
        match kind {
            "callDial" => {
                if let Some(id) = int("conversationId") {
                    self.dial(id);
                }
            }
            "callAnswer" => {
                if let (Some(id), Some((url, username))) = (int("conversationId"), at()) {
                    self.answer(url, username, id);
                }
            }
            "callDecline" => {
                if let (Some(id), Some((url, username))) = (int("conversationId"), at()) {
                    self.decline(&url, &username, id);
                }
            }
            _ => return false,
        }
        true
    }

    /// Rings the other person of a private conversation, and takes a slot in the call straight
    /// away, so the media is ready the moment they answer.
    fn dial(&mut self, conversation: ConversationId) {
        if self.call.as_ref().is_some_and(|c| c.conversation() == Some(conversation) && !c.left) {
            return;
        }
        let Some(s) = self.session.as_mut() else { return };
        let Some(peer) = s.private.peer_of(conversation) else { return };
        // The call's key would be sealed to their current key: one this phone hasn't accepted yet
        // is what somebody in the middle would hand out, so the change is confirmed first, where
        // the conversation shows it.
        if s.private.vault.ready() && s.private.vault.key_changed_for(peer) {
            self.toast(tr!(
                "Their security key changed. Confirm it in the conversation before calling.",
                "A chave de segurança dessa pessoa mudou. Confirme na conversa antes de ligar."
            ));
            return;
        }
        let key = crypto::random::<32>();
        let sealed = match s.private.vault.seal_call_key(conversation, peer, &key) {
            Ok(sealed) => sealed,
            Err(err) => {
                self.toast(seal_failed(err).message);
                return;
            }
        };
        if s.parked {
            s.unpark(&self.handle);
        }
        let (muted, deafened) = self.call.as_ref().map(|c| (c.muted, c.deafened)).unwrap_or_default();
        self.leave();
        let Some(s) = self.session.as_ref() else { return };
        let ring = s.request(
            "call:start",
            json!({
                "conversationId": conversation,
                "sealedKey": sealed.text,
                "senderKey": sealed.sender_key,
                "recipientKey": sealed.recipient_key,
            }),
        );
        let mut c = Call::new(Place::Call(conversation), None, Some(key), muted, deafened);
        c.peer = Some(peer);
        let id = c.id;
        self.begin_call(c);
        let h = self.handle.clone();
        crate::core::runtime().spawn(async move {
            let reply = ring.await;
            h.update(move |e| e.dialed(id, conversation, reply));
        });
    }

    /// The server's answer to a ring.
    fn dialed(&mut self, id: u64, conversation: ConversationId, reply: Result<Value, RequestError>) {
        let Some(s) = self.session.as_mut() else { return };
        let Some(c) = self.call.as_mut().filter(|c| c.id == id && !c.left) else { return };
        match reply {
            Ok(v) => {
                if let Some(info) = v.get("call").and_then(|c| serde_json::from_value::<CallInfo>(c.clone()).ok()) {
                    // Two people calling each other at once: the server made it one call, under
                    // the other person's key. Theirs is used.
                    if info.caller_id != s.me.id {
                        match s.private.vault.open_call_key(&info) {
                            Some(key) => c.key = Some(crate::media::rtc::FrameKey::new(&key)),
                            None => {
                                self.leave();
                                self.toast(unopenable());
                                return;
                            }
                        }
                    }
                    s.calls.insert(conversation, info);
                }
                c.send_join(s, &self.handle);
            }
            Err(err) => {
                // Refused: there is no call to hang up.
                c.left = true;
                let why = crate::core::api::friendly(err.code, &err.reply)
                    .unwrap_or_else(|| trf!("Could not call ({}).", "Não foi possível ligar ({}).", err));
                self.leave();
                self.toast(why);
            }
        }
        self.mark_voice();
    }

    /// Picks up a call ringing for you: there and then on the server on screen, or once the one it
    /// rings on is signed in to (or woken from the background) with the call in hand.
    fn answer(&mut self, url: String, username: String, conversation: ConversationId) {
        let here = self.here(&url, &username);
        if here && self.call.as_ref().is_some_and(|c| c.conversation() == Some(conversation) && !c.left) {
            return;
        }
        self.answering = Some(Answering {
            url: url.clone(),
            username: username.clone(),
            conversation,
            until: Instant::now() + ANSWER_WITHIN,
            asked_keys: false,
        });
        self.mark();
        if !here {
            log::info!("answering a call on {url}: going there");
            self.resume(Some(&url), Some(&username));
            return;
        }
        if let Some(s) = self.session.as_mut().filter(|s| s.parked) {
            s.unpark(&self.handle);
        }
        self.try_answer();
    }

    /// The answer given, once the call is there to pick up; on every tick until then.
    pub(super) fn try_answer(&mut self) {
        let Some(a) = self.answering.as_ref() else { return };
        if Instant::now() > a.until {
            self.answer_failed(ended());
            return;
        }
        let (url, username, id, asked_keys) = (a.url.clone(), a.username.clone(), a.conversation, a.asked_keys);
        if !self.here(&url, &username) {
            return;
        }
        let Some(s) = self.session.as_mut().filter(|s| !s.parked && s.link == Link::Up) else { return };
        let me = s.me.id;
        let Some(info) = s.calls.get(&id).cloned().filter(|c| c.state == CallPhase::Ringing && c.callee_id == me) else {
            // Up, and the call is not among yours any more: it ended meanwhile, or another device
            // of yours answered it.
            self.answer_failed(ended());
            return;
        };
        let vault = &mut s.private.vault;
        if !vault.ready() {
            // Just signed in: the keys are on their way.
            if vault.state != KeyState::Loading {
                self.answer_failed(unopenable());
            }
            return;
        }
        let unknown = vault.unknown([info.sender_key, info.recipient_key]);
        if !unknown.is_empty() {
            if asked_keys {
                self.answer_failed(unopenable());
                return;
            }
            if let Some(a) = self.answering.as_mut() {
                a.asked_keys = true;
            }
            let api = s.api.clone();
            self.spawn(async move { api.lookup_keys(&unknown).await }, |e, got| {
                if let (Ok(keys), Some(s)) = (got, e.session.as_mut()) {
                    s.private.vault.learn(keys);
                }
                e.try_answer();
            });
            return;
        }
        let Some(key) = vault.open_call_key(&info) else {
            self.answer_failed(unopenable());
            return;
        };
        self.answering = None;
        self.ring = None;
        self.mark();
        let (muted, deafened) = self.call.as_ref().map(|c| (c.muted, c.deafened)).unwrap_or_default();
        self.leave();
        let Some(s) = self.session.as_ref() else { return };
        let reply = s.request("call:answer", json!({ "conversationId": id }));
        let mut c = Call::new(Place::Call(id), None, Some(key), muted, deafened);
        c.peer = Some(info.caller_id);
        let cid = c.id;
        self.begin_call(c);
        let h = self.handle.clone();
        crate::core::runtime().spawn(async move {
            let reply = reply.await;
            h.update(move |e| e.answered(cid, reply));
        });
    }

    fn answer_failed(&mut self, why: String) {
        if self.ring_answering() {
            self.ring = None;
        }
        self.answering = None;
        self.toast(why);
        self.mark();
    }

    /// The server's answer to picking up.
    fn answered(&mut self, id: u64, reply: Result<Value, RequestError>) {
        let Some(c) = self.call.as_mut().filter(|c| c.id == id && !c.left) else { return };
        match reply {
            Ok(_) => {
                c.since.get_or_insert_with(SystemTime::now);
                if let Some(s) = self.session.as_ref() {
                    c.send_join(s, &self.handle);
                }
            }
            Err(err) => {
                log::info!("answering: {err}");
                c.left = true;
                self.leave();
                self.toast(ended());
            }
        }
        self.mark_voice();
    }

    /// Says no to a ring: on the server on screen's socket, or the background one it rang on.
    fn decline(&mut self, url: &str, username: &str, conversation: ConversationId) {
        if self.ring.as_ref().is_some_and(|r| r.is(url, username, conversation)) {
            self.ring = None;
            self.mark();
        }
        if self.answering.as_ref().is_some_and(|a| a.conversation == conversation && same_server(&a.url, url)) {
            self.answering = None;
        }
        let payload = json!({ "conversationId": conversation });
        let said = |reply: Result<Value, RequestError>| {
            if let Err(err) = reply {
                log::info!("declining: {err}");
            }
        };
        if let Some(s) = self.session.as_ref().filter(|s| self.here(url, username) && s.link == Link::Up) {
            let reply = s.request("call:hang-up", payload);
            crate::core::runtime().spawn(async move { said(reply.await) });
        } else if let Some(reply) = self.watch_request(url, username, "call:hang-up", payload) {
            crate::core::runtime().spawn(async move { said(reply.await) });
        } else {
            log::info!("declining a call on {url}: no socket to say it on");
        }
    }

    /// Every couple of seconds while a call you made rings: the tone that says so.
    pub(super) fn ring_back(&self) {
        let Some(c) = self.call.as_ref().filter(|c| !c.left) else { return };
        let Some(id) = c.conversation() else { return };
        let Some(s) = self.session.as_ref() else { return };
        if s.calls.get(&id).is_some_and(|i| i.state == CallPhase::Ringing && i.caller_id == s.me.id) {
            call::start_audio();
            audio::cue(Cue::RingBack, self.store.values.sound_volume as f32 / 100.);
        }
    }

    // What the server says of calls.

    /// `call:state` and `call:roster`, from the socket of the server on screen (or the background
    /// one listening to it while it is put down).
    pub(super) fn on_call_push(&mut self, kind: &str, v: &Value) {
        let Some(s) = self.session.as_mut() else { return };
        match kind {
            "call:state" => {
                let Ok(info) = serde_json::from_value::<CallInfo>(v.get("call").cloned().unwrap_or_default()) else { return };
                let id = info.conversation_id;
                if info.state == CallPhase::Ended {
                    let outcome = info.outcome.clone().unwrap_or_default();
                    s.call_outcomes.insert(id, outcome.clone());
                    s.calls.remove(&id);
                    s.call_rosters.remove(&id);
                    self.call_gone(id, Some(&outcome));
                } else {
                    let fresh = !s.calls.contains_key(&id);
                    s.calls.insert(id, info);
                    self.call_changed(id, fresh);
                }
                self.try_answer();
            }
            "call:roster" => {
                let id = v.get("conversationId").and_then(Value::as_i64).unwrap_or(-1);
                let Ok(roster) = serde_json::from_value::<Vec<Member>>(v.get("roster").cloned().unwrap_or_default()) else { return };
                if s.calls.contains_key(&id) || !roster.is_empty() {
                    s.call_rosters.insert(id, roster);
                } else {
                    s.call_rosters.remove(&id);
                }
                let cues = self.voice_sounds();
                if let Some(c) = self.call.as_mut().filter(|c| c.place == Place::Call(id)) {
                    c.on_roster(self.session.as_ref(), &self.handle, cues);
                    self.mark_voice();
                }
            }
            _ => {}
        }
    }

    /// After a hello: calls that ended meanwhile, and rings that started.
    pub(super) fn calls_reconciled(&mut self, before: HashMap<ConversationId, CallInfo>) {
        let Some(s) = self.session.as_ref() else { return };
        let gone: Vec<ConversationId> = before.keys().filter(|id| !s.calls.contains_key(id)).copied().collect();
        let changed: Vec<(ConversationId, bool)> =
            s.calls.iter().filter(|(id, c)| before.get(id) != Some(c)).map(|(id, _)| (*id, !before.contains_key(id))).collect();
        for id in gone {
            self.call_gone(id, None);
        }
        for (id, fresh) in changed {
            self.call_changed(id, fresh);
        }
        // A ring here the hello no longer has: over while nothing listened.
        if let Some(s) = self.session.as_ref()
            && let Some(r) = self.ring.clone()
            && r.is(&s.api.base(), &s.me.nickname, r.conversation)
            && !s.calls.contains_key(&r.conversation)
            && !self.ring_answering()
        {
            self.ring = None;
            self.mark();
        }
        self.try_answer();
    }

    /// The calls a background socket of the server on screen heard in its hello, while that
    /// server's own socket is down.
    pub(super) fn calls_heard(&mut self, calls: Vec<CallInfo>) {
        let Some(s) = self.session.as_mut() else { return };
        let before = std::mem::replace(&mut s.calls, calls.into_iter().map(|c| (c.conversation_id, c)).collect());
        self.calls_reconciled(before);
    }

    /// A call of the server on screen is new, or changed: answered, most likely.
    fn call_changed(&mut self, id: ConversationId, fresh: bool) {
        let Some(s) = self.session.as_ref() else { return };
        let Some(info) = s.calls.get(&id).cloned() else { return };
        let me = s.me.id;
        if fresh && info.state == CallPhase::Ringing && info.callee_id == me {
            let caller = info.caller_id;
            self.ring = Some(Ring {
                url: s.api.base(),
                username: s.me.nickname.clone(),
                server: s.server_name.clone(),
                conversation: id,
                caller,
                name: s.display_name(Some(caller), None),
                avatar: s.users.get(&caller).and_then(|u| u.avatar_hash.clone()),
            });
            log::info!("call ringing in conversation {id}");
            self.mark();
        }
        if info.state == CallPhase::Active {
            // Answered: here, or on another device of yours.
            if self.ring_here(id) && !self.ring_answering() {
                self.ring = None;
                self.mark();
            }
            if let Some(c) = self.call.as_mut().filter(|c| c.conversation() == Some(id)) {
                c.since.get_or_insert_with(SystemTime::now);
            }
        }
        self.mark_voice();
    }

    /// A call of the server on screen is over, with `outcome` when the server said how.
    fn call_gone(&mut self, id: ConversationId, outcome: Option<&str>) {
        let Some(s) = self.session.as_ref() else { return };
        if let Some(c) = self.call.as_mut().filter(|c| c.conversation() == Some(id) && !c.left) {
            let name = s.display_name(c.peer, None);
            let why = match outcome {
                Some("declined") if c.joined_once() => Some(trf!("{} declined the call.", "{} recusou a chamada.", name)),
                Some("missed") if c.joined_once() => Some(trf!("{} didn't answer.", "{} não atendeu.", name)),
                _ => None,
            };
            // Already over on the server: nothing to hang up there.
            c.left = true;
            self.leave();
            if let Some(why) = why {
                self.toast(why);
            }
        }
        if self.ring_here(id) {
            let ring = self.ring.take();
            if outcome == Some("missed")
                && let Some(r) = ring
            {
                self.missed(&r);
            }
            self.mark();
        }
    }

    /// A call that rang and nobody here picked up, for a notification while away.
    fn missed(&self, r: &Ring) {
        if self.visible || !self.notify {
            return;
        }
        self.send(json!({
            "type": "missedCall",
            "server": r.url,
            "username": r.username,
            "serverName": r.server,
            "conversationId": r.conversation,
            "author": r.name,
        }));
    }

    // Calls on servers listened to in the background.

    /// A `call:state` heard on another server's background socket.
    pub(super) fn call_elsewhere(&mut self, at: &Elsewhere, info: CallInfo) {
        let id = info.conversation_id;
        let ours = self.ring.as_ref().is_some_and(|r| r.is(at.url, at.username, id));
        if info.state == CallPhase::Ringing && Some(info.callee_id) == at.me {
            if !ours {
                self.ring = Some(ring_elsewhere(at, &info));
                log::info!("call ringing in conversation {id} on {}", at.url);
                self.mark();
            }
            return;
        }
        if !ours || self.ring_answering() {
            return;
        }
        let ring = self.ring.take();
        if info.state == CallPhase::Ended
            && info.outcome.as_deref() == Some("missed")
            && let Some(r) = ring
        {
            self.missed(&r);
        }
        self.mark();
    }

    /// What another server's background socket found in its hello: a ring that ended while it
    /// was down, or one already ringing.
    pub(super) fn calls_elsewhere(&mut self, at: &Elsewhere, calls: &[CallInfo]) {
        let ringing = |c: &&CallInfo| c.state == CallPhase::Ringing && Some(c.callee_id) == at.me;
        if let Some(r) = self.ring.clone()
            && same_server(&r.url, at.url)
            && r.username.eq_ignore_ascii_case(at.username)
            && !calls.iter().filter(ringing).any(|c| c.conversation_id == r.conversation)
            && !self.ring_answering()
        {
            self.ring = None;
            self.mark();
        }
        if self.ring.is_none()
            && let Some(c) = calls.iter().find(ringing)
        {
            self.ring = Some(ring_elsewhere(at, c));
            self.mark();
        }
    }
}

fn ring_elsewhere(at: &Elsewhere, info: &CallInfo) -> Ring {
    Ring {
        url: at.url.to_string(),
        username: at.username.to_string(),
        server: at.server.to_string(),
        conversation: info.conversation_id,
        caller: info.caller_id,
        name: at.names.get(&info.caller_id).cloned().unwrap_or_else(|| tr!("someone", "alguém").into()),
        avatar: None,
    }
}

#[cfg(test)]
mod tests {
    use super::Ring;

    fn ring() -> Ring {
        Ring {
            url: "http://127.0.0.1:8580".into(),
            username: "Ana".into(),
            server: "Casa".into(),
            conversation: 7,
            caller: 2,
            name: "Bia".into(),
            avatar: None,
        }
    }

    #[test]
    fn a_ring_is_its_server_account_and_conversation() {
        let r = ring();
        assert!(r.is("http://127.0.0.1:8580/", "ana", 7));
        assert!(!r.is("http://127.0.0.1:8580", "ana", 8));
        assert!(!r.is("http://127.0.0.1:8580", "bia", 7));
        assert!(!r.is("http://127.0.0.1:8590", "ana", 7));
    }

    #[test]
    fn a_ring_says_what_the_interface_needs() {
        let v = ring().state(true);
        assert_eq!(v["conversationId"], 7);
        assert_eq!(v["callerId"], 2);
        assert_eq!(v["name"], "Bia");
        assert_eq!(v["serverName"], "Casa");
        assert_eq!(v["answering"], true);
    }
}
