package app.sundown

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sundown.ui.MainViewModel
import app.sundown.ui.SundownNav
import app.sundown.ui.theme.NocturneTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** A tab asked for by a notification tap; consumed by the nav host. */
    private var requestedTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTab = intent?.getStringExtra(EXTRA_TAB)
        setContent {
            NocturneTheme {
                SundownNav(vm, requestedTab, onTabConsumed = { requestedTab = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = intent.getStringExtra(EXTRA_TAB)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
