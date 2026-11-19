package app.ciranda.android

import app.ciranda.android.ui.tr
import java.time.LocalDate

/**
 * What changed in each version, for the "What's new" dialog (`ui/News.kt`) that greets an update,
 * as the desktop's `src/news.rs` does. The notes ship in the app, in both languages, so they show
 * offline and always match the version installed. Whoever skipped versions gets every one they
 * missed, newest first.
 *
 * A release adds its entry at the top of `ENTRIES`, with the version in rust/Cargo.toml: the build
 * fails until it does (`checkNews` in app/build.gradle.kts). Plain sentences for people using
 * Ciranda, not commit messages.
 */
object News {
    val ENTRIES: List<Entry> = listOf(
        Entry(
            "0.3.2", "2026-11-19",
            new(
                "After an update, this window shows what changed, every version you skipped included.",
                "Depois de cada atualização, esta janela mostra o que mudou, incluindo as versões que você pulou.",
            ),
            new(
                "The whole history is in Settings, under Updates: \"What's new\".",
                "O histórico completo fica em Configurações, em Atualizações: \"Novidades\".",
            ),
        ),
        Entry(
            "0.3.1", "2026-11-16",
            new(
                "Password fields have an eye to see what you typed.",
                "Os campos de senha têm um olhinho para ver o que foi digitado.",
            ),
            new(
                "The server's name at the top of the side bar lists your other servers and switches straight to one. In a call, it asks first.",
                "O nome do servidor no topo da barra lateral lista seus outros servidores e troca direto. Em chamada, pergunta antes.",
            ),
        ),
        Entry(
            "0.3.0", "2026-11-16",
            new(
                "A button takes you back to the newest message when you scroll up in a chat.",
                "Um botão leva de volta à mensagem mais recente quando você rola o chat para cima.",
            ),
            better(
                "Smooth transitions between screens and channels, and the spinner only shows when the wait lasts.",
                "Transições suaves entre telas e canais, e o indicador de carregamento só aparece se a espera durar.",
            ),
            better(
                "Light theme: no dark flash on opening, and names and pictures are easier to read.",
                "Tema claro: sem o flash escuro ao abrir, e nomes e avatares mais legíveis.",
            ),
            better(
                "Voice buttons have names for screen readers, and every button is easier to hit.",
                "Os botões da voz têm nome para leitores de tela, e todos os botões ficaram mais fáceis de tocar.",
            ),
            fixed(
                "The bar beside quotes follows the text's size, and two channel groups with the same name no longer crash the app.",
                "A barra das citações acompanha o tamanho do texto, e dois grupos de canais com o mesmo nome não derrubam mais o app.",
            ),
        ),
        Entry(
            "0.2.2", "2026-11-09",
            better(
                "A clearer description for the mentions option.",
                "Texto mais claro na opção de menções.",
            ),
        ),
        Entry(
            "0.2.1", "2026-11-09",
            better(
                "No more fixed notice for mentions.",
                "Sem o aviso fixo de menções.",
            ),
        ),
        Entry(
            "0.2.0", "2026-11-09",
            new(
                "Mentions arrive as notifications even with the app closed: @you, @everyone, @todos, @all, @here and replies, from any server you signed in to.",
                "Menções viram notificação mesmo com o app fechado: @você, @everyone, @todos, @all, @here e respostas, de qualquer servidor em que você entrou.",
            ),
            new(
                "Lock Ciranda with your fingerprint, face or the phone's PIN, in Settings under Security.",
                "Bloqueio com digital, rosto ou o PIN do celular, em Configurações, na parte de Segurança.",
            ),
            new(
                "Change your account's password in Settings under Account.",
                "Troque a senha da conta em Configurações, na parte de Conta.",
            ),
            better(
                "Without internet on opening, the app tries again by itself instead of going to the sign-in screen.",
                "Sem internet ao abrir, o app tenta de novo sozinho em vez de cair na tela de entrar.",
            ),
            better(
                "Switching servers keeps you signed in to the one before, and asks before leaving a call.",
                "Trocar de servidor não te desconecta mais do anterior, e pergunta antes de sair de uma chamada.",
            ),
            fixed(
                "A wrong account password no longer clears the server's password or the invite.",
                "Errar a senha da conta não apaga mais a senha do servidor nem o convite.",
            ),
            fixed(
                "Several chat fixes: a failed file no longer blocks sending, a message that didn't go back to the box, and the chat updates again after a network failure.",
                "Várias correções no chat: arquivo que falha não trava mais o envio, mensagem que não foi volta para a caixa de texto, e o chat volta a se atualizar depois de uma falha de rede.",
            ),
        ),
        Entry(
            "0.1.1", "2026-11-09",
            new(
                "Invite friends with the button at the top of the channel list. The link opens a page to download Ciranda and fills in the server and its password.",
                "Convide amigos pelo botão no topo da lista de canais. O link abre uma página para baixar o Ciranda e já preenche o servidor e a senha.",
            ),
        ),
        Entry(
            "0.1.0", "2026-11-09",
            new(
                "The first Ciranda for Android, with the same servers and accounts as the desktop: text channels, voice channels and mention notifications.",
                "O primeiro Ciranda para Android, com os mesmos servidores e contas do desktop: canais de texto, canais de voz e notificação de menções.",
            ),
        ),
    )

    /** The last version from before this dialog: whoever updates from it, or older, has no record of what they saw. */
    const val BEFORE_NEWS = "0.3.1"

    enum class Kind { New, Better, Fixed }

    class Note(val kind: Kind, private val en: String, private val pt: String) {
        val text: String get() = tr(en, pt)
    }

    class Entry(val version: String, date: String, vararg notes: Note) {
        val date: LocalDate = LocalDate.parse(date)
        val notes: List<Note> = notes.toList()
    }

    private fun new(en: String, pt: String) = Note(Kind.New, en, pt)
    private fun better(en: String, pt: String) = Note(Kind.Better, en, pt)
    private fun fixed(en: String, pt: String) = Note(Kind.Fixed, en, pt)

    val current: String get() = BuildConfig.VERSION_NAME

    /** The entries after [seen] up to [current], newest first. */
    fun since(seen: String, current: String = this.current): List<Entry> =
        ENTRIES.filter { Updater.isNewer(it.version, seen) && !Updater.isNewer(it.version, current) }

    /** Every entry up to [current], newest first: the whole history, from Settings. */
    fun upTo(current: String = this.current): List<Entry> = ENTRIES.filter { !Updater.isNewer(it.version, current) }

    /**
     * The version whose news was last shown: none for a new install, which has nothing to catch up
     * on, and [BEFORE_NEWS] for one updated from before the setting.
     */
    fun seen(setting: String, updated: Boolean): String? = when {
        setting.isNotBlank() -> setting
        updated -> BEFORE_NEWS
        else -> null
    }

    /** What this start has to show, with the version last seen. */
    fun pending(setting: String, updated: Boolean): Pair<String, List<Entry>>? {
        val seen = seen(setting, updated) ?: return null
        return since(seen).takeIf { it.isNotEmpty() }?.let { seen to it }
    }
}
