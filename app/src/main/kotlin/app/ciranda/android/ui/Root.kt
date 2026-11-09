package app.ciranda.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity

@Composable
fun Root(activity: MainActivity) {
    val state by Engine.state.collectAsState()
    LaunchedEffect(Unit) {
        Engine.events.collect { e ->
            when (e) {
                is Event.Toast -> Toast.makeText(activity, e.text, Toast.LENGTH_LONG).show()
                is Event.File -> activity.openFile(e)
                is Event.InviteLink -> activity.shareInvite(e)
                else -> {}
            }
        }
    }
    val session = state.session
    when {
        state.phase == "in" && session != null -> ServerScreen(activity, state, session)
        state.phase == "signedOut" -> SignInScreen(state)
        state.phase == "signingIn" && state.saved.none { it.signedIn } -> SignInScreen(state)
        else -> Box(Modifier.fillMaxSize().background(LocalPalette.current.bg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
