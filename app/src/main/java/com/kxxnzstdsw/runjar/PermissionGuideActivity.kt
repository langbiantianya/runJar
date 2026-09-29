package com.kxxnzstdsw.runjar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kxxnzstdsw.runjar.ui.PermissionGuide
import com.kxxnzstdsw.runjar.ui.PermissionGuideScreen
import com.kxxnzstdsw.runjar.ui.theme.RunJarTheme

/**
 * The permission guide, as an activity of its own.
 *
 * This is a real window rather than another state of the run screen, and that is
 * what makes the back gesture here a cross-activity back: the platform knows the
 * gesture lands on the run screen, so it previews exactly that — the run screen
 * sliding in as the swipe is made. Nothing in this app draws that preview, and
 * there is no close button; the platform's own back is the way out.
 */
class PermissionGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shown once, when it opens by itself: the guide has to be seen, not
        // acknowledged, to stop appearing on launch.
        PermissionGuide.markSeen(this)
        setContent {
            RunJarTheme {
                PermissionGuideScreen(onDone = { finish() })
            }
        }
    }
}
