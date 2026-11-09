//! The servers listened to in the background for the mentions that call you. Each server signed
//! in to gets a `private` socket, which the server tells of a mention (`mentions` in its health
//! check) without counting you online; the server on screen gets one too once the app has been
//! away long enough for its own socket to be put down (`Session::park`). What one hears becomes a
//! notification for the interface to post.

use super::Engine;
use crate::core::api::Api;
use crate::core::realtime::{self, Realtime, Scope};
use crate::core::settings::{SavedServer, same_server};
use crate::core::types::*;
use serde_json::{Value, json};
use std::collections::HashMap;

pub struct Watch {
    url: String,
    username: String,
    /// The server's name, to say where a mention was.
    name: String,
    realtime: Realtime,
    socket: u64,
    /// Display names by id, so a mention says who as the server shows it.
    names: HashMap<UserId, String>,
}

impl Watch {
    fn is(&self, url: &str, username: &str) -> bool {
        same_server(&self.url, url) && self.username.eq_ignore_ascii_case(username)
    }
}

#[derive(Default)]
pub struct Watching {
    list: Vec<Watch>,
    /// Servers that cannot tell a background socket of mentions, or refused its sign-in: not
    /// asked again until the app starts over.
    skip: Vec<(String, String)>,
    sockets: u64,
}

impl Engine {
    /// A socket for every server signed in to but the one on screen with its own socket up, while
    /// notifications are on; none otherwise.
    pub(super) fn sync_watch(&mut self) {
        // Mid sign-in, the server being signed in to is about to be the one on screen.
        if self.phase == super::Phase::SigningIn {
            return;
        }
        let awake = self.session.as_ref().filter(|s| !s.parked).map(|s| (s.api.base(), s.me.nickname.clone()));
        let same = |url: &str, name: &str, s: &SavedServer| same_server(url, &s.url) && name.eq_ignore_ascii_case(&s.username);
        let wanted: Vec<SavedServer> = if self.notify {
            self.store
                .values
                .saved_servers
                .iter()
                .filter(|s| !s.session_token.is_empty())
                .filter(|s| !awake.as_ref().is_some_and(|(u, n)| same(u, n, s)))
                .filter(|s| !self.watching.skip.iter().any(|(u, n)| same(u, n, s)))
                .cloned()
                .collect()
        } else {
            Vec::new()
        };
        self.watching.list.retain(|w| {
            let keep = wanted.iter().any(|s| w.is(&s.url, &s.username));
            if !keep {
                log::info!("no longer listening on {}", w.url);
                w.realtime.stop();
            }
            keep
        });
        for s in wanted {
            if !self.watching.list.iter().any(|w| w.is(&s.url, &s.username)) {
                self.start_watch(s);
            }
        }
    }

    fn start_watch(&mut self, s: SavedServer) {
        let api = Api::new();
        api.set_server(&s.url, &s.password);
        api.set_token(&s.session_token);
        self.watching.sockets += 1;
        let socket = self.watching.sockets;
        let (tx, rx) = async_channel::unbounded();
        let realtime = Realtime::start(crate::core::runtime().handle(), api.clone(), Scope::Private, tx);
        let h = self.handle.clone();
        crate::core::runtime().spawn(async move {
            while let Ok(ev) = rx.recv().await {
                h.update(move |e| e.on_watch(socket, ev));
            }
        });
        let name = if s.name.is_empty() { s.host() } else { s.name.clone() };
        log::info!("listening for mentions on {}", s.url);
        self.watching.list.push(Watch { url: s.url, username: s.username, name, realtime, socket, names: HashMap::new() });
        // Whether it can, and who is who there. Offline now, the socket keeps trying on its own.
        self.spawn(async move { tokio::join!(api.health(), api.users()) }, move |e, (health, users)| {
            let Some(at) = e.watching.list.iter().position(|w| w.socket == socket) else { return };
            if let Ok(health) = health
                && !health.has("mentions")
            {
                let w = e.watching.list.remove(at);
                log::info!("{} does not tell a background socket of mentions", w.url);
                w.realtime.stop();
                e.watching.skip.push((w.url, w.username));
                return;
            }
            if let Ok(users) = users {
                e.watching.list[at].names = users.into_iter().map(|u| (u.id, u.name().to_string())).collect();
            }
        });
    }

    fn on_watch(&mut self, socket: u64, ev: realtime::Event) {
        let Some(at) = self.watching.list.iter().position(|w| w.socket == socket) else { return };
        match ev {
            realtime::Event::Rejected => {
                let w = self.watching.list.remove(at);
                log::info!("{} refused the background sign-in", w.url);
                w.realtime.stop();
                self.watching.skip.push((w.url, w.username));
            }
            realtime::Event::Push(v) if v.get("type").and_then(Value::as_str) == Some("mention") => {
                let Ok(m) = serde_json::from_value::<Message>(v.get("message").cloned().unwrap_or(Value::Null)) else { return };
                let channel = v.pointer("/channel/name").and_then(Value::as_str).unwrap_or_default().to_string();
                let w = &self.watching.list[at];
                let author = m.user_id.and_then(|id| w.names.get(&id).cloned()).or_else(|| m.nickname.clone());
                let (url, username, server) = (w.url.clone(), w.username.clone(), w.name.clone());
                // The server on screen, its own socket put down: its badges count it as that
                // socket would have.
                if let Some(s) =
                    self.session.as_mut().filter(|s| same_server(&s.api.base(), &url) && s.me.nickname.eq_ignore_ascii_case(&username))
                {
                    s.unread.insert(m.channel_id);
                    *s.mentioned.entry(m.channel_id).or_default() += 1;
                    self.mark();
                }
                let author = author.unwrap_or_else(|| tr!("someone", "alguém").into());
                self.notify_mention(Mention {
                    url: &url,
                    username: &username,
                    server: &server,
                    channel: &channel,
                    author: &author,
                    message: &m,
                });
            }
            _ => {}
        }
    }

    /// A message that calls you, for the interface to put in a notification.
    pub(super) fn notify_mention(&self, m: Mention) {
        let body = if m.message.body.is_empty() { m.message.attachment_name.clone().unwrap_or_default() } else { m.message.body.clone() };
        self.send(json!({
            "type": "mention",
            "server": m.url,
            "username": m.username,
            "serverName": m.server,
            "channelId": m.message.channel_id,
            "channel": m.channel,
            "messageId": m.message.id,
            "author": m.author,
            "body": body,
        }));
    }
}

pub(super) struct Mention<'a> {
    pub url: &'a str,
    pub username: &'a str,
    pub server: &'a str,
    pub channel: &'a str,
    pub author: &'a str,
    pub message: &'a Message,
}
