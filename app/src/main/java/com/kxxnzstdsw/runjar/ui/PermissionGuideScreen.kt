package com.kxxnzstdsw.runjar.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kxxnzstdsw.runjar.R

/**
 * The permission guide: every permission the app declares, what it does with it,
 * and whether the device has granted it.
 *
 * It opens on the first launch, because that is when the question is worth
 * asking — before the user has a JAR in flight and a run that depends on the
 * answer — and afterwards it stays reachable from the run screen. It is an
 * activity of its own, so the back gesture leaves it for the run screen: that is
 * the platform's cross-activity back, previewed and performed by the system.
 *
 * A JAR arrives with its own permissions in its manifest, and none of them come
 * with it: the guest JVM runs here, under this app's UID, so what a JAR needs is
 * what this app has to hold. That is the whole reason the screen exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    // Grant state lives in the platform, so it is read rather than kept: the
    // reads are keyed on a counter that moves at the two moments an answer can
    // have changed — when a dialog this screen opened has closed, and on every
    // resume, which is also how a grant made in the system settings is picked up
    // when the user comes back.
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }

    val askForMissing = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { revision++ }

    val missing = remember(revision) { RunPermissions.missing(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_permissions)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Introduction() }

                items(RunPermissions.declared()) { permission ->
                    PermissionCard(permission, revision)
                }

                item { Closing() }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                // Offered only while there is something to ask for: on a device
                // that has answered already, it would do nothing.
                if (missing.isNotEmpty()) {
                    Button(
                        onClick = { askForMissing.launch(missing) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_grant_now))
                    }
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        }
    }
}

/**
 * Whether the guide has been seen.
 *
 * The first launch is the only time it appears on its own; it is in the
 * platform's own storage rather than in a state object so it survives the
 * process, which the screen does not.
 */
object PermissionGuide {
    private const val FILE = "runjar"
    private const val KEY_SEEN = "permission_guide_seen"

    fun isSeen(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_SEEN, true) }
    }
}

@Composable
private fun Introduction() {
    Text(
        text = stringResource(R.string.permission_guide_intro),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Closing() {
    Text(
        text = stringResource(R.string.permission_guide_closing),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionCard(permission: RunPermission, revision: Int) {
    val context = LocalContext.current
    val state = remember(revision) { RunPermissions.stateOf(context, permission) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(permission.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.label(),
                    color = state.color(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = permission.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(permission.purposeRes),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** How the platform's answer reads to the user. */
@Composable
private fun PermissionState.label(): String = stringResource(
    when (this) {
        PermissionState.InstallTime -> R.string.permission_state_install_time
        PermissionState.NotOnThisVersion -> R.string.permission_state_not_on_version
        PermissionState.Granted -> R.string.permission_state_granted
        PermissionState.Denied -> R.string.permission_state_denied
    },
)

@Composable
private fun PermissionState.color() = when (this) {
    PermissionState.Granted -> MaterialTheme.colorScheme.primary
    PermissionState.Denied -> MaterialTheme.colorScheme.error
    // Granted at install, or not asked for here: neither is something the user
    // has to act on.
    PermissionState.InstallTime, PermissionState.NotOnThisVersion ->
        MaterialTheme.colorScheme.onSurfaceVariant
}
