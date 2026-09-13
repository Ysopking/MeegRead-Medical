package de.meegread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.meegread.app.ui.MeegReadApp
import de.meegread.app.ui.MeegReadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeegReadTheme { MeegReadApp() } }
    }
}
