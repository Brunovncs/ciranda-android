# Ciranda para Android

Voz e chat do [Ciranda](https://github.com/Brunovncs/ciranda) no celular: os mesmos servidores, as mesmas contas, os mesmos canais.

## Instalar

1. No celular, abra a [última versão](https://github.com/Brunovncs/ciranda-android/releases/latest) e baixe o arquivo `ciranda-X.Y.Z-android.apk`.
2. Abra o arquivo baixado. Na primeira vez, o Android pede para permitir que o navegador instale apps: permita.
3. Entre com o endereço do servidor (por exemplo `bloquinho.duckdns.org:8444`), seu nome e sua senha, os mesmos do computador.

Daí em diante, Ciranda avisa quando sai uma versão nova e se atualiza sozinha: **Configurações → Atualizações → Atualizar**. Na primeira atualização, o Android pede para permitir que Ciranda instale apps.

## O que tem

- Canais de texto: mensagens com a mesma formatação do desktop (negrito, código, citações, listas, `:emoji:`, menções), imagens, arquivos, reações, respostas, edição e exclusão.
- Canais de voz: entrar e sair, mutar, ensurdecer, ver quem está falando, volume de cada pessoa (até 350%), cancelamento de eco e supressão de ruído (DeepFilterNet, a mesma do desktop).
- A voz continua com a tela apagada ou com outro app na frente, com mutar e sair na notificação.
- Bloqueio com digital, rosto ou o PIN do celular (oferecido depois de entrar; em Configurações → Segurança, com quanto tempo fora antes de pedir de novo). Voltar de algo que o próprio app abriu (escolher arquivo, compartilhar, PIN) não pede de novo.
- Trocar a senha da conta em Configurações → Conta. Esqueceu a senha? Quem cuida do servidor define uma nova no painel.
- Notificação quando alguém te menciona (`@você`, `@everyone`, `@todos`, `@all`, `@here` ou uma resposta), em qualquer servidor em que você entrou, mesmo com o app fechado. Uma conexão leve fica aberta para isso, sem te mostrar online; o Android exige um aviso fixo, silencioso, que dá para esconder. Precisa de servidor com a versão que avisa menções em segundo plano (`mentions` em `/api/health`).
- Atualização pelo próprio app, como no desktop.

Ainda não tem: conversas privadas e chamadas privadas, assistir telas e câmeras, compartilhar a tela ou a câmera, Cirandinha e soundpad.

## Como é feito

O app não reescreve o Ciranda. O desktop está aqui como submódulo (`ciranda/`) e o crate em `rust/` compila os próprios arquivos dele para Android: a API e o socket (`core/`), o áudio (cpal, que no Android usa AAudio), o processamento do microfone e o DeepFilterNet, as conexões WebRTC com o MediaMTX (`media/rtc.rs`), o markdown e os emojis. O que no desktop depende do GPUI (manter a sessão e a chamada) foi reescrito em `rust/src/engine/`, sem janela: um ator único no runtime tokio que recebe comandos e devolve o estado em JSON.

A interface é Kotlin com Jetpack Compose (`app/`), e conversa com o Rust por duas funções JNI (`rust/src/bridge.rs`): comandos em JSON entram, eventos em JSON saem.

```
app/                 Kotlin + Compose: telas, serviço de voz, atualizador
rust/src/engine/     sessão, chat e chamada, sem GPUI
rust/src/bridge.rs   JNI
ciranda/             o desktop (submódulo), de onde vêm core/, media/, markdown e emojis
```

## Desenvolver

Precisa de: Android SDK com NDK `30.0.16248370`, JDK 17, Rust (a versão de `rust-toolchain.toml`) com `rustup target add aarch64-linux-android x86_64-linux-android`, e `cargo install cargo-ndk`.

```sh
git clone --recursive https://github.com/Brunovncs/ciranda-android
./gradlew assembleDebug                                # celular (arm64)
./gradlew assembleDebug -Pabis=arm64-v8a,x86_64        # celular e emulador
```

O Gradle chama `cargo ndk` antes de compilar o app, e copia o `libwebrtc.jar` do LiveKit.

Para testar a voz sem outro celular, `rust/examples/voicebot.rs` entra num canal de voz, toca um tom de 440 Hz e mostra o volume que chega de cada pessoa:

```sh
cd rust && cargo run --example voicebot -- http://127.0.0.1:8580 gabriel harmony1 <id do canal> 60
```

Para atualizar o código do desktop, mova o submódulo (`git -C ciranda pull`) e confirme que tudo ainda compila.

## Lançar uma versão

1. Mude `version` em `rust/Cargo.toml` (o `versionName` e o `versionCode` do app vêm dali) e rode `cargo update -p ciranda-mobile --offline` em `rust/`.
2. Commit `X.Y.Z: resumo`, com as notas da versão no corpo da mensagem: elas aparecem no app.
3. `git tag vX.Y.Z && git push origin main vX.Y.Z`.

O workflow `release` compila o APK assinado e publica o release com o APK e o SHA-256 dele. Ele precisa dos secrets `CIRANDA_KEYSTORE_BASE64`, `CIRANDA_KEYSTORE_PASSWORD`, `CIRANDA_KEY_ALIAS` e `CIRANDA_KEY_PASSWORD`. **A chave de assinatura não pode mudar nem se perder**: o Android só instala uma atualização assinada com a mesma chave.

## Licença

MIT, como o Ciranda.
