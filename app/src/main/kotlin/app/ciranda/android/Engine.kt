package app.ciranda.android

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: Long,
    val nickname: String,
    val displayName: String?,
    val role: String,
    val avatarHash: String?,
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: nickname
    val admin: Boolean get() = role == "owner" || role == "admin"
}

data class Channel(
    val id: Long,
    val kind: String,
    val name: String,
    val position: Long,
    val groupId: Long?,
    val locked: Boolean,
    val micLocked: Boolean,
) {
    val voice: Boolean get() = kind == "voice"
}

data class Group(val id: Long, val name: String, val position: Long)

data class Member(
    val mid: Long,
    val userId: Long,
    val nickname: String,
    val muted: Boolean,
    val deafened: Boolean,
    val forceMuted: Boolean,
    val publishing: List<String>,
)

data class Emoji(val name: String, val hash: String)

data class SavedServer(val url: String, val host: String, val username: String, val name: String, val signedIn: Boolean)

data class Settings(
    val noiseSuppression: Boolean = true,
    val neural: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGain: Boolean = true,
    val voiceSounds: Boolean = true,
    val checkUpdates: Boolean = true,
    val micGain: Int = 100,
    val micSensitivity: Int = 0,
    val soundVolume: Int = 100,
    val language: String = "",
)

data class SessionState(
    val url: String,
    val name: String,
    val icon: String?,
    val up: Boolean,
    val me: User,
    val users: Map<Long, User>,
    val online: Set<Long>,
    val channels: List<Channel>,
    val groups: List<Group>,
    val rosters: Map<Long, List<Member>>,
    val unread: Set<Long>,
    val mentioned: Map<Long, Int>,
    val openChannel: Long?,
    val maxFileBytes: Long?,
    val emojis: List<Emoji>,
) {
    fun user(id: Long?): User? = id?.let { users[it] }
    fun channel(id: Long?): Channel? = channels.firstOrNull { it.id == id }
}

data class AppState(
    val phase: String = "loading",
    val signInError: String? = null,
    val needsServerPassword: Boolean = false,
    /** The sign-in under way was typed in the form (which stays on screen for it). */
    val signInViaForm: Boolean = false,
    /** Failed sign-ins so far, and whether the last was the account's password. */
    val signInFailures: Long = 0,
    val wrongPassword: Boolean = false,
    /** A kept sign-in found no network, and is being tried again. */
    val signInRetrying: Boolean = false,
    val formServer: String = "",
    val formNickname: String = "",
    val formRegister: Boolean = false,
    /** Filled in by an invite link: its server password, where it leads, and which invite it was. */
    val formServerPassword: String = "",
    val formInvite: String? = null,
    val formSeq: Long = 0,
    val saved: List<SavedServer> = emptyList(),
    val session: SessionState? = null,
    val settings: Settings = Settings(),
    val version: String = "",
)

data class VoiceState(
    val channelId: Long,
    val channel: String?,
    val state: String,
    val muted: Boolean,
    val deafened: Boolean,
    val forceMuted: Boolean,
    val speaking: Set<Long>,
    val pingMs: Int?,
    val micFailed: Boolean,
    val volumes: Map<Long, Float>,
)

data class Reaction(val emoji: String, val count: Int, val userIds: List<Long>)

data class Reply(val id: Long, val userId: Long?, val body: String, val attachmentName: String?)

/** A span of a message body, as `rust/src/engine/render.rs` makes them. */
data class Span(
    val text: String? = null,
    val code: String? = null,
    val url: String? = null,
    val custom: String? = null,
    val emojiName: String? = null,
    val mention: String? = null,
    val mentionsMe: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
)

data class TextBlock(val kind: String, val level: Int, val ordered: Boolean, val lines: List<List<Span>>, val code: String?)

data class Message(
    val id: Long,
    val channelId: Long,
    val userId: Long?,
    val author: String,
    val avatar: String?,
    val body: String,
    val blocks: List<TextBlock>,
    val emojiOnly: Boolean,
    val attachmentHash: String?,
    val attachmentName: String?,
    val attachmentBytes: Long?,
    val mediaType: String?,
    val edited: Boolean,
    val reply: Reply?,
    val reactions: List<Reaction>,
    val createdAt: Long,
    val mentionsMe: Boolean,
)

data class ChatState(
    val channelId: Long,
    val messages: List<Message>,
    val loaded: Boolean,
    val moreBefore: Boolean,
    val loadingOlder: Boolean,
    val error: String?,
)

sealed interface Event {
    data class Toast(val text: String) : Event
    data class Picture(val hash: String, val path: String?) : Event
    data class NeedsPassword(val channelId: Long, val wrong: Boolean) : Event
    /** A message that calls you, from any server signed in to, for a notification. */
    data class Mention(
        val server: String,
        val username: String,
        val serverName: String,
        val channelId: Long,
        val channel: String,
        val messageId: Long,
        val author: String,
        val body: String,
    ) : Event
    data class InCall(val on: Boolean) : Event
    data class File(val path: String, val name: String) : Event
    data class Uploading(val on: Boolean) : Event
    /** A message or a file's caption that did not go: its text, back for the composer. */
    data class SendFailed(val channelId: Long, val body: String) : Event
    data class InviteLink(val link: String, val name: String) : Event

    /** Signed in with the form (not a kept sign-in): when the lock is offered. */
    data object SignedIn : Event
    data class PasswordChanged(val ok: Boolean, val error: String?) : Event
}

/** The app's state as the Rust engine last said it, and its one-off events. */
object Engine {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val _voice = MutableStateFlow<VoiceState?>(null)
    val voice: StateFlow<VoiceState?> = _voice
    private val _chat = MutableStateFlow<ChatState?>(null)
    val chat: StateFlow<ChatState?> = _chat
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events

    fun send(cmd: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("cmd", cmd)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        Native.command(o.toString())
    }

    fun onEvent(json: String) {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        when (o.optString("type")) {
            "state" -> _state.value = parseState(o)
            "voice" -> _voice.value = o.optJSONObject("voice")?.let(::parseVoice)
            "chat" -> _chat.value = parseChat(o)
            "toast" -> _events.tryEmit(Event.Toast(o.optString("text")))
            "picture" -> _events.tryEmit(Event.Picture(o.optString("hash"), o.str("path")))
            "needsPassword" -> _events.tryEmit(Event.NeedsPassword(o.optLong("channelId"), o.optBoolean("wrong")))
            "file" -> _events.tryEmit(Event.File(o.optString("path"), o.optString("name")))
            "call" -> _events.tryEmit(Event.InCall(o.optBoolean("on")))
            "signedIn" -> _events.tryEmit(Event.SignedIn)
            "passwordChanged" -> _events.tryEmit(Event.PasswordChanged(o.optBoolean("ok"), o.str("error")))
            "inviteLink" -> _events.tryEmit(Event.InviteLink(o.optString("link"), o.optString("name")))
            "sendFailed" -> _events.tryEmit(Event.SendFailed(o.optLong("channelId"), o.optString("body")))
            "uploading" -> _events.tryEmit(Event.Uploading(o.optBoolean("on")))
            "mention" -> _events.tryEmit(
                Event.Mention(
                    o.optString("server"), o.optString("username"), o.optString("serverName"), o.optLong("channelId"),
                    o.optString("channel"), o.optLong("messageId"), o.optString("author"), o.optString("body"),
                ),
            )
        }
    }
}

private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
private fun JSONObject.long(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
private inline fun <T> JSONArray?.list(f: (Int) -> T): List<T> = if (this == null) emptyList() else List(length()) { f(it) }
private fun JSONArray?.longs(): List<Long> = list { this!!.optLong(it) }

private fun parseUser(o: JSONObject) = User(
    o.optLong("id"), o.optString("nickname"), o.str("displayName"), o.optString("role", "member"), o.str("avatarHash"),
)

private fun parseMember(o: JSONObject) = Member(
    o.optLong("mid"), o.optLong("userId"), o.optString("nickname"), o.optBoolean("muted"), o.optBoolean("deafened"),
    o.optBoolean("forceMuted") || o.optBoolean("micLocked"), o.optJSONArray("publishing").list { i -> o.optJSONArray("publishing")!!.optString(i) },
)

private fun parseState(o: JSONObject): AppState {
    val signIn = o.optJSONObject("signIn") ?: JSONObject()
    val form = signIn.optJSONObject("form") ?: JSONObject()
    val saved = signIn.optJSONArray("saved").list { i ->
        val s = signIn.optJSONArray("saved")!!.getJSONObject(i)
        SavedServer(s.optString("url"), s.optString("host"), s.optString("username"), s.optString("name"), s.optBoolean("signedIn"))
    }
    val st = o.optJSONObject("settings") ?: JSONObject()
    val settings = Settings(
        st.optBoolean("noiseSuppression", true), st.optBoolean("neural", true), st.optBoolean("echoCancellation", true),
        st.optBoolean("autoGain", true), st.optBoolean("voiceSounds", true), st.optBoolean("checkUpdates", true),
        st.optInt("micGain", 100), st.optInt("micSensitivity", 0), st.optInt("soundVolume", 100), st.optString("language"),
    )
    return AppState(
        phase = o.optString("phase"),
        signInError = signIn.str("error"),
        needsServerPassword = signIn.optBoolean("serverPassword"),
        signInViaForm = signIn.optBoolean("viaForm"),
        signInFailures = signIn.optLong("failures"),
        wrongPassword = signIn.optBoolean("wrongPassword"),
        signInRetrying = signIn.optBoolean("retrying"),
        formServer = form.optString("server"),
        formNickname = form.optString("nickname"),
        formRegister = form.optBoolean("register"),
        formServerPassword = form.optString("serverPassword"),
        formInvite = form.str("invite"),
        formSeq = form.optLong("seq"),
        saved = saved,
        session = o.optJSONObject("session")?.let(::parseSession),
        settings = settings,
        version = o.optString("version"),
    )
}

private fun parseSession(o: JSONObject): SessionState {
    val users = o.optJSONArray("users").list { parseUser(o.optJSONArray("users")!!.getJSONObject(it)) }.associateBy { it.id }
    val channels = o.optJSONArray("channels").list { i ->
        val c = o.optJSONArray("channels")!!.getJSONObject(i)
        Channel(c.optLong("id"), c.optString("kind"), c.optString("name"), c.optLong("position"), c.long("groupId"), c.optBoolean("locked"), c.optBoolean("micLocked"))
    }
    val groups = o.optJSONArray("groups").list { i ->
        val g = o.optJSONArray("groups")!!.getJSONObject(i)
        Group(g.optLong("id"), g.optString("name"), g.optLong("position"))
    }
    val rosters = mutableMapOf<Long, List<Member>>()
    o.optJSONObject("rosters")?.let { r ->
        for (k in r.keys()) {
            val a = r.optJSONArray(k)
            rosters[k.toLong()] = a.list { parseMember(a!!.getJSONObject(it)) }
        }
    }
    val mentioned = mutableMapOf<Long, Int>()
    o.optJSONObject("mentioned")?.let { m -> for (k in m.keys()) mentioned[k.toLong()] = m.optInt(k) }
    val emojis = o.optJSONArray("emojis").list { i ->
        val e = o.optJSONArray("emojis")!!.getJSONObject(i)
        Emoji(e.optString("name"), e.optString("hash"))
    }
    return SessionState(
        url = o.optString("url"),
        name = o.optString("name"),
        icon = o.str("icon"),
        up = o.optString("link") == "up",
        me = parseUser(o.getJSONObject("me")),
        users = users,
        online = o.optJSONArray("online").longs().toSet(),
        channels = channels,
        groups = groups,
        rosters = rosters,
        unread = o.optJSONArray("unread").longs().toSet(),
        mentioned = mentioned,
        openChannel = o.long("openChannel"),
        maxFileBytes = o.long("maxFileBytes"),
        emojis = emojis,
    )
}

private fun parseVoice(o: JSONObject): VoiceState {
    val volumes = mutableMapOf<Long, Float>()
    o.optJSONObject("volumes")?.let { v -> for (k in v.keys()) volumes[k.toLong()] = v.optDouble(k, 1.0).toFloat() }
    return VoiceState(
        channelId = o.optLong("channelId"),
        channel = o.str("channel"),
        state = o.optString("state"),
        muted = o.optBoolean("muted"),
        deafened = o.optBoolean("deafened"),
        forceMuted = o.optBoolean("forceMuted"),
        speaking = o.optJSONArray("speaking").longs().toSet(),
        pingMs = if (o.isNull("pingMs")) null else o.optInt("pingMs"),
        micFailed = o.optBoolean("micFailed"),
        volumes = volumes,
    )
}

private fun parseSpan(o: JSONObject) = Span(
    text = o.str("text"),
    code = o.str("code"),
    url = o.str("url"),
    custom = o.str("custom"),
    emojiName = o.str("name"),
    mention = o.str("mention"),
    mentionsMe = o.optBoolean("me"),
    bold = o.optBoolean("b"),
    italic = o.optBoolean("i"),
    strike = o.optBoolean("s"),
)

private fun parseMessage(o: JSONObject): Message {
    val rendered = o.optJSONObject("rendered") ?: JSONObject()
    val blocks = rendered.optJSONArray("blocks").list { i ->
        val b = rendered.optJSONArray("blocks")!!.getJSONObject(i)
        val lines = b.optJSONArray("lines").list { j ->
            val l = b.optJSONArray("lines")!!.getJSONArray(j)
            l.list { k -> parseSpan(l.getJSONObject(k)) }
        }
        TextBlock(b.optString("t"), b.optInt("level"), b.optBoolean("ordered"), lines, b.str("text"))
    }
    val reactions = o.optJSONArray("reactions").list { i ->
        val r = o.optJSONArray("reactions")!!.getJSONObject(i)
        Reaction(r.optString("emoji"), r.optInt("count"), r.optJSONArray("userIds").longs())
    }
    val reply = o.optJSONObject("reply")?.let { r -> Reply(r.optLong("id"), r.long("userId"), r.optString("body"), r.str("attachmentName")) }
    return Message(
        id = o.optLong("id"),
        channelId = o.optLong("channelId"),
        userId = o.long("userId"),
        author = o.optString("author"),
        avatar = o.str("avatar"),
        body = o.optString("body"),
        blocks = blocks,
        emojiOnly = rendered.optBoolean("emojiOnly"),
        attachmentHash = o.str("attachmentHash"),
        attachmentName = o.str("attachmentName"),
        attachmentBytes = o.long("attachmentBytes"),
        mediaType = o.str("mediaType"),
        edited = !o.isNull("editedAt") && o.has("editedAt"),
        reply = reply,
        reactions = reactions,
        createdAt = o.optLong("createdAt"),
        mentionsMe = false,
    )
}

private fun parseChat(o: JSONObject): ChatState {
    val a = o.optJSONArray("messages")
    return ChatState(
        channelId = o.optLong("channelId"),
        messages = a.list { parseMessage(a!!.getJSONObject(it)) },
        loaded = o.optBoolean("loaded"),
        moreBefore = o.optBoolean("moreBefore"),
        loadingOlder = o.optBoolean("loadingOlder"),
        error = o.str("error"),
    )
}
