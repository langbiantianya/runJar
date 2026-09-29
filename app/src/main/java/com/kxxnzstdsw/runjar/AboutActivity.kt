package com.kxxnzstdsw.runjar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kxxnzstdsw.runjar.ui.AboutScreen
import com.kxxnzstdsw.runjar.ui.theme.RunJarTheme

/**
 * About, as an activity of its own.
 *
 * Like the permission guide, being a real window is the point: back from here is
 * a cross-activity back, which the platform previews by sliding the run screen
 * in — the truth about where the gesture lands, drawn by the system rather than
 * by anything in this app. The screen has no close button for the same reason.
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunJarTheme {
                AboutScreen()
            }
        }
    }
}
