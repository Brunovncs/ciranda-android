# Ciranda para Android

Voz e chat do [Ciranda](https://github.com/Brunovncs/ciranda) no celular: os mesmos servidores, as mesmas contas, os mesmos canais.

## Instalar

1. No celular, abra a [última versão](https://github.com/Brunovncs/ciranda-android/releases/latest) e baixe o arquivo `ciranda-X.Y.Z-android.apk`.
2. Abra o arquivo baixado. Na primeira vez, o Android pede para permitir que o navegador instale apps: permita.
3. Entre com o endereço do servidor (por exemplo `bloquinho.duckdns.org:8444`), seu nome e sua senha, os mesmos do computador.

Daí em diante, Ciranda avisa quando sai uma versão nova e se atualiza sozinha: **Configurações → Atualizações → Atualizar**. Na primeira atualização, o Android pede para permitir que Ciranda instale apps.

## O que tem

- Uma barra embaixo com o servidor (toque na foto para trocar de servidor), os canais, as pessoas, a voz e os ajustes. Tocar em alguém, no chat ou numa sala de voz, abre o perfil; tocar numa sala mostra quem está nela antes de entrar.
- Canais de texto: mensagens com a mesma formatação do desktop (negrito, código, citações, listas, `:emoji:`, menções), imagens (que dá para salvar na galeria ou compartilhar), arquivos, reações, respostas, edição e exclusão.
- Mensagens privadas com criptografia de ponta a ponta, as mesmas do desktop: texto, imagens, arquivos, reações, respostas, edição, código de segurança e bloqueio. A chave fica neste celular, selada pelo cofre de chaves do Android; num celular novo, a chave de recuperação destrava as conversas.
- Canais de voz: entrar e sair, mutar, ensurdecer, ver quem está falando, volume de cada pessoa (até 350%), cancelamento de eco e supressão de ruído (DeepFilterNet, a mesma do desktop).
- Chamadas privadas, com a mesma criptografia de ponta a ponta das mensagens: o botão de ligar fica no alto da conversa. A chamada tem uma tela própria, com a foto de quem está do outro lado (o anel quando fala), o tempo de chamada, mutar, ensurdecer e desligar; na conversa ficam as linhas "Chamada · 3 min", "Chamada perdida de…" e "Sua chamada foi recusada", como no desktop.
- Receber chamada com o app fechado ou o celular bloqueado: a mesma conexão leve das menções faz tocar (com o toque e a vibração do celular, respeitando o modo silencioso), com Atender e Recusar na notificação e a tela da chamada por cima da tela de bloqueio. No Android 14 ou mais novo, a tela inteira depende de uma permissão (Configurações → Notificações → Mostrar chamadas na tela inteira); sem ela, toca como notificação no alto da tela. Atender uma chamada de outro servidor entra nele e atende lá. Uma chamada que tocou sem ninguém atender vira notificação de chamada perdida.
- A voz continua com a tela apagada ou com outro app na frente, com mutar e sair (ou desligar, numa chamada privada) na notificação.
- Bloqueio com digital, rosto ou o PIN do celular (oferecido depois de entrar; em Configurações → Segurança, com quanto tempo fora antes de pedir de novo). Voltar de algo que o próprio app abriu (escolher arquivo, compartilhar, PIN) não pede de novo.
- Trocar a senha da conta em Configurações → Conta. Esqueceu a senha? Quem cuida do servidor define uma nova no painel.
- Notificação quando alguém te menciona (`@você`, `@everyone`, `@todos`, `@all`, `@here` ou uma resposta), em qualquer servidor em que você entrou, mesmo com o app fechado. Uma conexão leve fica aberta para isso, sem te mostrar online; o Android exige um aviso fixo, silencioso, que dá para esconder. Precisa de servidor com a versão que avisa menções em segundo plano (`mentions` em `/api/health`).
- Atualização pelo próprio app, como no desktop. Depois de atualizar, uma janela mostra o que mudou em cada versão que você pulou; o histórico fica em Configurações → Atualizações → Novidades. As notas ficam em `app/src/main/kotlin/app/ciranda/android/News.kt`, e o build falha se a versão de `rust/Cargo.toml` não tiver a sua.

Ainda não tem: assistir telas e câmeras, compartilhar a tela ou a câmera (nem numa chamada privada), Cirandinha e soundpad.

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

Para as chamadas privadas, `rust/examples/callbot.rs` é o outro lado: entra (criando a conta se não existir), espera tocar e atende (`answer`), recusa (`decline`) ou liga para alguém (`dial`), toca um tom de 660 Hz selado com a chave da chamada e mostra o volume que chega, já aberto. E `rust/examples/enginecall.rs` roda o próprio motor do app, sem celular (comandos JSON entrando, eventos saindo), com o microfone e o alto-falante do computador: liga (`dial`), ou espera tocar com o app "fechado" (servidor posto para dormir, só a conexão leve ouvindo) e atende, recusa ou deixa tocar (`answer`, `decline`, `ignore`).

```sh
cd rust
cargo run --example callbot -- http://127.0.0.1:8580 robo robo12345 answer 30
cargo run --example enginecall -- http://127.0.0.1:8580 fone fone12345 dial robo 20
```

Para atualizar o código do desktop, mova o submódulo (`git -C ciranda pull`) e confirme que tudo ainda compila.

## Lançar uma versão

1. Mude `version` em `rust/Cargo.toml` (o `versionName` e o `versionCode` do app vêm dali) e rode `cargo update -p ciranda-mobile --offline` em `rust/`.
2. Commit `X.Y.Z: resumo`, com as notas da versão no corpo da mensagem: elas aparecem no app.
3. `git tag vX.Y.Z && git push origin main vX.Y.Z`.

O workflow `release` compila o APK assinado e publica o release com o APK e o SHA-256 dele. Ele precisa dos secrets `CIRANDA_KEYSTORE_BASE64`, `CIRANDA_KEYSTORE_PASSWORD`, `CIRANDA_KEY_ALIAS` e `CIRANDA_KEY_PASSWORD`. **A chave de assinatura não pode mudar nem se perder**: o Android só instala uma atualização assinada com a mesma chave.

## Licença

MIT, como o Ciranda.
