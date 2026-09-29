package com.kxxnzstdsw.runjar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kxxnzstdsw.runjar.ui.PermissionGuide
import com.kxxnzstdsw.runjar.ui.RunJarScreen
import com.kxxnzstdsw.runjar.ui.theme.RunJarTheme

/**
 * The run screen — the app itself.
 *
 * The two screens that explain the app are activities rather than states of this
 * one, so that back from them is a cross-activity back: the run screen is what
 * the platform has behind them, and it is what the back gesture previews and
 * returns to. See [PermissionGuideActivity] and [AboutActivity].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The guide opens by itself the first time the app is run — before there
        // is a JAR in flight and a run that depends on the answer. It is a
        // screen above this one, so backing out of it just returns here.
        if (!PermissionGuide.isSeen(this)) {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }

        setContent {
            RunJarTheme {
                RunJarScreen(
                    onShowPermissionGuide = {
                        startActivity(Intent(this, PermissionGuideActivity::class.java))
                    },
                    onShowAbout = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    },
                )
            }
        }
    }
}
