//! Signing in to a server, as the desktop's sign-in screen does it (`ui/connect.rs`): the
//! server's health and password, the account (a kept sign-in, or a name and password), and the
//! session claim that brings the ICE servers voice needs.

use crate::core::api::{Api, ApiError};
use crate::core::types::*;

#[derive(Clone, Debug)]
pub struct Form {
    pub server: String,
    pub server_password: String,
    pub nickname: String,
    pub password: String,
    pub register: bool,
    /// A kept sign-in, tried instead of the password.
    pub token: Option<String>,
}

pub struct Done {
    pub user: User,
    pub token: String,
    pub server_name: String,
    pub server_icon: Option<String>,
    pub ice_servers: Vec<IceServer>,
    pub health: Health,
    pub notes: Vec<&'static str>,
}

pub struct Failure {
    pub message: String,
    /// The kept sign-in was refused: it is forgotten, and the password asked for.
    pub expired: bool,
    /// The server has a password of its own and it was missing or wrong.
    pub server_password: bool,
}

impl From<ApiError> for Failure {
    fn from(e: ApiError) -> Self {
        Failure { message: e.message, expired: false, server_password: false }
    }
}

fn fail(message: impl Into<String>) -> Failure {
    Failure { message: message.into(), expired: false, server_password: false }
}

pub async fn sign_in(api: &Api, form: &Form) -> Result<Done, Failure> {
    if api.base().is_empty() {
        return Err(fail(tr!("Type the server's address.", "Digite o endereço do servidor.")));
    }
    if form.nickname.is_empty() {
        return Err(fail(tr!("Type your name.", "Digite seu nome.")));
    }
    let health = api.health().await?;
    if health.password_required && !health.authenticated {
        let message = if form.server_password.is_empty() {
            tr!("This server has a password. Type it.", "Este servidor tem senha. Digite-a.")
        } else {
            tr!("That is not this server's password.", "Essa não é a senha do servidor.")
        };
        return Err(Failure { server_password: true, ..fail(message) });
    }
    if health.has_accounts.is_none() {
        return Err(fail(tr!(
            "This server is older than accounts. Update it to use this version of Ciranda.",
            "Este servidor é de uma versão sem contas. Atualize-o para usar esta versão de Ciranda."
        )));
    }
    let mut notes = Vec::new();
    if health.mediamtx.as_deref() == Some("down") {
        notes.push(tr!(
            "The server's media relay is not answering, so voice won't work until it is back.",
            "O servidor de mídia não está respondendo. A voz só vai funcionar quando ele voltar."
        ));
    }
    let (user, token) = match &form.token {
        Some(t) => {
            api.set_token(t);
            match api.me().await {
                Ok(u) if u.nickname.eq_ignore_ascii_case(&form.nickname) => (u, t.clone()),
                Err(e) if e.status != 401 => return Err(e.into()),
                _ => {
                    api.set_token("");
                    let server = health.name.clone().filter(|n| !n.is_empty()).unwrap_or_else(|| api.base());
                    return Err(Failure {
                        message: trf!(
                            "Your saved sign-in to {} expired. Type your password to sign in again.",
                            "Seu login salvo em {} expirou. Digite sua senha para entrar de novo.",
                            server
                        ),
                        expired: true,
                        server_password: false,
                    });
                }
            }
        }
        None => {
            if form.password.is_empty() {
                return Err(fail(tr!("Type your password.", "Digite sua senha.")));
            }
            let reply = if form.register {
                api.register(&form.nickname, &form.password, None).await
            } else {
                api.login(&form.nickname, &form.password, None).await
            }
            .map_err(Failure::from)?;
            api.set_token(&reply.token);
            if reply.owner_claimed {
                notes.push(tr!("You are this server's owner now.", "Agora você é o dono deste servidor."));
            }
            (reply.user, reply.token)
        }
    };
    let ice_servers = match api.session(&form.nickname, false).await {
        Ok(s) if !s.ice_servers.is_empty() => s.ice_servers,
        _ => api.streams().await.map(|(_, ice)| ice).unwrap_or_default(),
    };
    let server_name = health.name.clone().filter(|n| !n.is_empty()).unwrap_or_else(|| "Harmony".into());
    let server_icon = health.icon_hash().cloned();
    Ok(Done { user, token, server_name, server_icon, ice_servers, health, notes })
}
