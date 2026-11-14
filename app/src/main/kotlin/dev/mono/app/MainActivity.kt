package dev.mono.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.mono.app.session.AppContainer
import dev.mono.app.ui.MonoRoot
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MonoApplication).container
        setContent {
            MonoRoot(container)
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush pending edits when the user leaves the app. Writes are tiny
        // (tmp + rename); blocking here guarantees they land before the
        // process can go away.
        runCatching { runBlocking { (application as MonoApplication).container.saveAll() } }
    }
}
