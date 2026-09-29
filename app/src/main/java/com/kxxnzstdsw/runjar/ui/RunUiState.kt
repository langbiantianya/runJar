package com.kxxnzstdsw.runjar.ui

import android.net.Uri
import com.kxxnzstdsw.runjar.jvm.JreRelease

/** What the UI is currently doing. */
sealed interface RunState {
    /** Nothing selected yet. */
    data object Idle : RunState

    /** Fetching and unpacking the guest runtime. */
    data class Preparing(val progress: Float) : RunState

    /** The JAR is executing. */
    data object Running : RunState

    /** The run finished; [message] is the outcome line shown in the console. */
    data class Finished(val message: String, val success: Boolean) : RunState

    /** The run could not be started. */
    data class Failed(val message: String) : RunState
}

/** Everything the screen renders. */
data class RunUiState(
    val jarUri: Uri? = null,
    val jarName: String? = null,
    val mainClass: String = "",
    val argsInput: String = "",
    val heapMb: Int = 256,
    val release: JreReleaseOption = JreReleaseOption.DEFAULT,
    val installedReleases: Set<String> = emptySet(),
    val console: List<String> = emptyList(),
    val state: RunState = RunState.Idle,
    /** Whether a guest JVM still exists for this app, whether or not it is busy. */
    val guestRunning: Boolean = false,
) {
    val canRun: Boolean
        get() = jarUri != null && mainClass.isNotBlank() && state !is RunState.Running &&
                state !is RunState.Preparing
}

/** A runtime choice in the picker. */
data class JreReleaseOption(
    val id: String,
    val label: String,
    val installed: Boolean = false,
) {
    companion object {
        val DEFAULT = of(JreRelease.DEFAULT)

        fun of(release: JreRelease) =
            JreReleaseOption(release.id, "Java ${release.majorVersion}")
    }
}
