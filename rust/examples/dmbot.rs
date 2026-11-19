//! The other side of a private conversation, with no window, for trying the app's private
//! messages end to end: signs in (making the account if it has none), makes its key, writes to
//! someone, and answers whatever they write back, printing it as it opened. Uses the desktop's
//! own `dm::Vault` and `core::crypto`, as the app does.
//!
//! cargo run --example dmbot -- http://127.0.0.1:8590 robo robo1234 <their nickname> [seconds]

use ciranda::core::api::Api;
use ciranda::core::keystore::KeyFile;
use ciranda::core::realtime::{Event, Realtime, Scope};
use ciranda::core::types::*;
use ciranda::dm::{Next, Payload, Vault};
use serde_json::Value;
use std::time::{Duration, Instant};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (base, nick, pass, peer_nick) = (a[1].clone(), a[2].clone(), a[3].clone(), a[4].clone());
    let seconds: u64 = a.get(5).and_then(|s| s.parse().ok()).unwrap_or(120);
    // Its own key file, apart from any client's.
    let dir = std::env::temp_dir().join(format!("ciranda-dmbot-{nick}"));
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
        let mut vault = Vault::new(KeyFile::open(&base, &nick), me);
        if vault.on_keys(api.keys().await.expect("keys")) == Next::Create {
            let (identity, recovery, wrapped) = Vault::create();
            let key = api.publish_key(&identity.public_b64(), &wrapped).await.expect("publish key");
            vault.adopt(key, identity, &recovery, true);
        }
        println!("{nick}: key ready");
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
        vault.pin(peer);
        let send = |vault: &mut Vault, body: String| {
            let sealed = vault.seal_message(conversation, peer, &Payload { body, ..Default::default() }).expect("seal");
            let api = api.clone();
            async move { api.send_dm(conversation, &sealed, None).await.expect("send") }
        };
        let first = send(&mut vault, "Oi! Esta mensagem foi selada no robô e só o seu celular abre.".into()).await;
        println!("sent message {}", first.id);
        // A picture too, sealed the way the apps seal one, when one is given.
        if let Some(path) = a.get(6) {
            let bytes = std::fs::read(path).expect("picture");
            let (sealed, key) = ciranda::core::crypto::seal_file(&bytes);
            let up = api.upload_sealed(conversation, sealed).await.expect("upload");
            let name = std::path::Path::new(path).file_name().unwrap_or_default().to_string_lossy().to_string();
            let file =
                SealedFile { key: ciranda::core::crypto::b64(key.as_slice()), name, mime: "image/png".into(), size: bytes.len() as u64 };
            let payload = Payload { body: "Uma foto selada".into(), file: Some(file), ..Default::default() };
            let sealed = vault.seal_message(conversation, peer, &payload).expect("seal");
            let m = api.send_dm(conversation, &sealed, Some(&up.hash)).await.expect("send picture");
            println!("sent picture {}", m.id);
        }
        let (tx, rx) = async_channel::unbounded();
        let socket = Realtime::start(ciranda::core::runtime().handle(), api.clone(), Scope::Full, tx);
        let until = Instant::now() + Duration::from_secs(seconds);
        while Instant::now() < until {
            let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_secs(1), rx.recv()).await else { continue };
            let Event::Push(v) = ev else { continue };
            match v.get("type").and_then(Value::as_str) {
                Some("dm:message") => {
                    let Ok(m) = serde_json::from_value::<SealedMessage>(v["message"].clone()) else { continue };
                    if m.user_id == me {
                        continue;
                    }
                    vault.learn(api.lookup_keys(&vault.unknown(m.sender_key.into_iter().chain(m.recipient_key))).await.unwrap_or_default());
                    let opened = vault.open_message(&m, peer);
                    let p = opened.private.clone().unwrap_or_default();
                    println!(
                        "got {}: {:?}{}{}",
                        m.id,
                        opened.body,
                        p.file.map(|f| format!(" + file {} ({} bytes, {})", f.name, f.size, f.mime)).unwrap_or_default(),
                        if p.unreadable { " [UNREADABLE]" } else { "" },
                    );
                    if !p.unreadable {
                        let answer = send(&mut vault, format!("Recebi: {}", opened.body)).await;
                        println!("answered with {}", answer.id);
                    }
                }
                Some("dm:reactions") => println!("reactions changed on {}", v["id"]),
                Some("dm:updated") => println!("message {} edited", v["message"]["id"]),
                Some("dm:deleted") => println!("message {} deleted", v["id"]),
                _ => {}
            }
        }
        socket.stop();
    });
}
