//! The app's own engine (`engine/`), driven the way the Kotlin interface drives it but from here,
//! for trying private calls without a phone: JSON commands in, JSON events out, printed. It signs
//! in (making the account if it has none), then rings someone, or waits to be rung and answers or
//! declines. Waiting, it starts as the app does from a notification: nothing on screen, its
//! server's socket put down, a background one listening; `answer-visible` waits on screen instead.
//! The sound is this computer's own microphone and speakers. Pair it with `callbot`.
//!
//! cargo run --example enginecall -- http://127.0.0.1:8580 fone fone1234 dial <their nickname> [seconds]
//! cargo run --example enginecall -- http://127.0.0.1:8580 fone fone1234 answer|answer-visible|decline|ignore [seconds]

use parking_lot::Mutex;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn send(cmd: Value) {
    if let Some(h) = ciranda::engine::handle() {
        h.update(move |e| e.command(cmd));
    }
}

/// Waits until `f` finds what it looks for in the last state, or gives up.
fn wait_for<T>(state: &Mutex<Value>, limit: Duration, what: &str, f: impl Fn(&Value) -> Option<T>) -> T {
    let start = Instant::now();
    loop {
        if let Some(v) = f(&state.lock()) {
            return v;
        }
        assert!(start.elapsed() < limit, "gave up waiting for {what}");
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (base, nick, pass, mode) = (a[1].clone(), a[2].clone(), a[3].clone(), a[4].clone());
    let peer_nick = (mode == "dial").then(|| a[5].clone());
    let rest = if mode == "dial" { 6 } else { 5 };
    let seconds: u64 = a.get(rest).and_then(|s| s.parse().ok()).unwrap_or(30);
    let dir = std::env::temp_dir().join(format!("ciranda-enginecall-{nick}"));
    let _ = std::fs::create_dir_all(&dir);
    // SAFETY: before any thread reads the environment.
    unsafe { std::env::set_var("CIRANDA_DATA_DIR", &dir) };

    let state = Arc::new(Mutex::new(Value::Null));
    let voice = Arc::new(Mutex::new(Value::Null));
    let (st, vo) = (state.clone(), voice.clone());
    let last_ring = Arc::new(Mutex::new(Value::Null));
    let lr = last_ring.clone();
    let emit = Arc::new(move |text: String| {
        let Ok(v) = serde_json::from_str::<Value>(&text) else { return };
        match v["type"].as_str() {
            Some("state") => {
                if *lr.lock() != v["ring"] {
                    println!("ring: {}", v["ring"]);
                    *lr.lock() = v["ring"].clone();
                }
                *st.lock() = v;
            }
            Some("voice") => {
                let c = &v["voice"];
                if !c.is_null() {
                    let roster: Vec<String> =
                        c["roster"].as_array().into_iter().flatten().map(|m| format!("{}{}", m["nickname"], m["publishing"])).collect();
                    println!(
                        "voice: {} {} ringing={} since={} muted={} speaking={} roster=[{}]",
                        c["channel"],
                        c["state"],
                        c["ringing"],
                        c["since"],
                        c["muted"],
                        c["speaking"],
                        roster.join(", ")
                    );
                } else if !vo.lock().is_null() {
                    println!("voice: none");
                }
                *vo.lock() = v["voice"].clone();
            }
            Some("toast") => println!("toast: {}", v["text"]),
            Some("missedCall") | Some("call") => println!("event: {v}"),
            _ => {}
        }
    });
    ciranda::engine::session::set_pictures_dir(dir.join("pictures"));
    ciranda::engine::start(dir.clone(), emit);

    let visible = mode == "dial" || mode == "answer-visible";
    send(json!({ "cmd": "hello", "locale": "pt" }));
    send(json!({ "cmd": "visible", "on": visible }));
    send(json!({ "cmd": "notifications", "on": true }));
    send(json!({ "cmd": "signIn", "server": base, "nickname": nick, "password": pass, "register": true }));
    let settled = |s: &Value| match (s["phase"].as_str(), s["signIn"]["failures"].as_u64()) {
        (Some("in"), _) => Some(true),
        (Some("signedOut"), Some(n)) if n > 0 => Some(false),
        _ => None,
    };
    if !wait_for(&state, Duration::from_secs(15), "sign-in", settled) {
        // The account exists already: signed in to instead.
        send(json!({ "cmd": "signIn", "server": base, "nickname": nick, "password": pass, "register": false }));
        wait_for(&state, Duration::from_secs(15), "sign-in", |s| (s["phase"] == "in").then_some(()));
    }
    // Put down at once with nothing on screen, its socket never comes up: the keys come with it,
    // once a call is answered.
    if visible {
        wait_for(&state, Duration::from_secs(20), "the private key", |s| (s["session"]["private"]["state"] == "ready").then_some(()));
    }
    let me = state.lock()["session"]["me"]["id"].as_i64().unwrap_or_default();
    println!("{nick} (user {me}): signed in, on screen: {visible}");

    match peer_nick {
        Some(peer_nick) => {
            let peer = wait_for(&state, Duration::from_secs(60), "the other person to exist", |s| {
                s["session"]["users"]
                    .as_array()?
                    .iter()
                    .find(|u| u["nickname"].as_str().is_some_and(|n| n.eq_ignore_ascii_case(&peer_nick)))?["id"]
                    .as_i64()
            });
            send(json!({ "cmd": "dmWith", "userId": peer }));
            let conversation =
                wait_for(&state, Duration::from_secs(15), "the conversation", |s| s["session"]["private"]["open"]["id"].as_i64());
            // ROUNDS=2 calls again after the first: the second has to be heard as well as the first.
            let rounds: usize = std::env::var("ROUNDS").ok().and_then(|r| r.parse().ok()).unwrap_or(1);
            for round in 1..rounds {
                println!("calling {peer_nick} in conversation {conversation} (round {round})");
                send(json!({ "cmd": "callDial", "conversationId": conversation }));
                talk(&voice, me, seconds);
                std::thread::sleep(Duration::from_secs(4));
            }
            println!("calling {peer_nick} in conversation {conversation}");
            send(json!({ "cmd": "callDial", "conversationId": conversation }));
        }
        None => {
            println!("waiting to be rung…");
            let ring = wait_for(&state, Duration::from_secs(120), "a ring", |s| Some(s["ring"].clone()).filter(|r| !r.is_null()));
            println!("rung by {} on {}", ring["name"], ring["serverName"]);
            if mode == "ignore" {
                wait_for(&state, Duration::from_secs(60), "the ring to stop", |s| s["ring"].is_null().then_some(()));
                println!("the ring stopped");
                std::thread::sleep(Duration::from_secs(1));
                return;
            }
            std::thread::sleep(Duration::from_secs(2));
            let cmd = if mode == "decline" { "callDecline" } else { "callAnswer" };
            send(json!({ "cmd": cmd, "server": ring["server"], "username": ring["username"], "conversationId": ring["conversationId"] }));
            if mode == "decline" {
                std::thread::sleep(Duration::from_secs(3));
                return;
            }
        }
    }
    talk(&voice, me, seconds);
}

/// The call under way, until it ends or `seconds` pass; then hung up. Whether the other person
/// was heard.
fn talk(voice: &Mutex<Value>, me: i64, seconds: u64) -> bool {
    let start = Instant::now();
    let mut heard = false;
    while start.elapsed() < Duration::from_secs(seconds) {
        std::thread::sleep(Duration::from_millis(250));
        let v = voice.lock().clone();
        if v.is_null() && start.elapsed() > Duration::from_secs(5) {
            println!("the call is over");
            break;
        }
        let them_speaking = v["speaking"].as_array().is_some_and(|s| s.iter().any(|u| u.as_i64() != Some(me)));
        if them_speaking && !heard {
            heard = true;
            println!("hearing the other person (their sound opened here)");
        }
    }
    if !voice.lock().is_null() {
        send(json!({ "cmd": "leave" }));
        println!("hung up");
        std::thread::sleep(Duration::from_secs(2));
    }
    println!("heard them: {heard}");
    heard
}
