//! Message bodies as the desktop draws them (`markdown.rs`): blocks of styled spans, with
//! `:shortcode:` emoji and `@mentions` resolved here, so the interface only lays them out.

use crate::core::types::*;
use crate::markdown::{self, Block, Kind, Line, Span};
use serde_json::{Value, json};
use std::collections::HashMap;

pub struct Names<'a> {
    pub emojis: &'a [CustomEmoji],
    pub users: &'a HashMap<UserId, User>,
    pub me: UserId,
}

pub fn body(text: &str, names: &Names) -> Value {
    let custom = |name: &str| names.emojis.iter().any(|e| e.name == name);
    let blocks: Vec<Value> = markdown::parse(text).iter().map(|b| block(b, names)).collect();
    json!({ "blocks": blocks, "emojiOnly": markdown::is_emoji_only(text, custom) })
}

fn block(b: &Block, names: &Names) -> Value {
    let lines = |ls: &[Line]| -> Vec<Value> { ls.iter().map(|l| line(l, names)).collect() };
    match b {
        Block::Paragraph(ls) => json!({ "t": "p", "lines": lines(ls) }),
        Block::Heading { level, line: l } => {
            json!({ "t": "h", "level": level, "lines": [line(l, names)] })
        }
        Block::Quote(ls) => json!({ "t": "q", "lines": lines(ls) }),
        Block::List { ordered, items } => {
            json!({ "t": "l", "ordered": ordered, "lines": lines(items) })
        }
        Block::Code { text, .. } => json!({ "t": "code", "text": text }),
    }
}

fn line(l: &Line, names: &Names) -> Value {
    Value::Array(l.iter().map(|s| span(s, names)).collect())
}

fn span(s: &Span, names: &Names) -> Value {
    let mut v = match &s.kind {
        Kind::Text(t) => json!({ "text": t }),
        Kind::Code(t) => json!({ "code": t }),
        Kind::Link { label, url } => json!({ "text": label, "url": url }),
        Kind::Shortcode { name, raw } => match names.emojis.iter().find(|e| &e.name == name) {
            Some(e) => json!({ "custom": e.hash, "name": e.name }),
            None => match crate::emoji::by_shortcode(name) {
                Some(emoji) => json!({ "text": emoji }),
                None => json!({ "text": raw }),
            },
        },
        Kind::Mention { name, raw } => {
            if crate::markdown::calls_everyone(name) {
                json!({ "mention": raw.to_lowercase(), "me": true })
            } else {
                match names.users.values().find(|u| u.nickname.to_lowercase() == *name) {
                    Some(u) => {
                        json!({ "mention": format!("@{}", u.name()), "me": u.id == names.me })
                    }
                    None => json!({ "text": raw }),
                }
            }
        }
    };
    if s.style.bold {
        v["b"] = json!(true);
    }
    if s.style.italic {
        v["i"] = json!(true);
    }
    if s.style.strike {
        v["s"] = json!(true);
    }
    v
}
