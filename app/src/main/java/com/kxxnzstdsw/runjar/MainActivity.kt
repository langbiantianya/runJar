package com.kxxnzstdsw.runjar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kxxnzstdsw.runjar.ui.RunJarScreen
import com.kxxnzstdsw.runjar.ui.theme.RunJarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunJarTheme {
                RunJarScreen()
            }
        }
    }
}
