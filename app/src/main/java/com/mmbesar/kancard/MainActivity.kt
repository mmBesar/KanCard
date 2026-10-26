package com.mmbesar.kancard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mmbesar.kancard.security.Mode
import com.mmbesar.kancard.ui.KanCardTheme
import androidx.compose.ui.platform.LocalContext
import com.mmbesar.kancard.ui.BoardScreen
import com.mmbesar.kancard.ui.BoardViewModel
import com.mmbesar.kancard.ui.SetupScreen
import com.mmbesar.kancard.ui.ThemePrefs

/** The one and only screen container; Compose draws everything inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs = (application as KanCardApplication).themePrefs
            val settings by themePrefs.settings.collectAsStateWithLifecycle()
            KanCardTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KanCardRoot(themePrefs)
                }
            }
        }
    }
}

/** First start -> setup screen; afterwards -> the board. */
@Composable
private fun KanCardRoot(themePrefs: ThemePrefs, viewModel: BoardViewModel = viewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val editing by viewModel.editingConnection.collectAsStateWithLifecycle()
    // The setup screen is also used to edit the saved server address / user.
    if (mode == Mode.UNSET || editing) {
        SetupScreen(viewModel)
    } else {
        BoardScreen(
            viewModel,
            themePrefs,
            (LocalContext.current.applicationContext as KanCardApplication).viewPrefs,
        )
    }
}
