package app.ciranda.android

import android.content.Context

/** The Rust half (`rust/src/bridge.rs`): JSON commands in, JSON events out. */
object Native {
    init {
        System.loadLibrary("ciranda")
    }

    @JvmStatic
    external fun start(context: Context, dataDir: String, cacheDir: String)

    @JvmStatic
    external fun command(json: String)

    /** Called from Rust's threads. */
    @JvmStatic
    fun onEvent(json: String) {
        Engine.onEvent(json)
    }
}
