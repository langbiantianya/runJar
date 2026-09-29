package com.kxxnzstdsw.runjar.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** The heap sizes the picker offers, in mebibytes. */
private val HEAP_CHOICES_MB = listOf(128, 256, 512, 768)

/**
 * The screen: a compact form for the run, and the guest's console.
 *
 * The console is the point of the screen, so the form is kept tight — the main
 * class and the arguments share a row — and the console takes everything left
 * over.
 *
 * Nothing is allowed to depend on the window being tall enough. The form
 * scrolls if it is ever given less room than it needs, and it is capped so the
 * console can never be squeezed away entirely: with the keyboard up, or on a
 * small device, the form scrolls rather than the log disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunJarScreen(viewModel: RunViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickJar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onJarPicked) }

    // Asked for when a run starts, because that is when they matter: the
    // notification is what the foreground service is shown with, local network
    // access is what lets another device reach a JAR that listens, and "All
    // files access" is what lets the run write where the user can find its
    // files. A refusal only costs the thing refused — the run starts either way.
    val context = LocalContext.current
    val requestRunPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    val requestAllFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Run JAR") }) },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Reserves the keyboard's space before the regions are sized, so
                // the fields stay reachable instead of being covered by it.
                .imePadding(),
        ) {
            // Read here, where BoxWithConstraints' scope is the implicit
            // receiver: the nested Column below shadows it.
            val formMaxHeight = (maxHeight - CONSOLE_FLOOR).coerceAtLeast(0.dp)

            Column(modifier = Modifier.fillMaxSize()) {
                // No weight: a weight would split the window evenly and push Run
                // below the fold on an ordinary phone. Capped only so the console
                // keeps its floor, and scrollable so the cap is not a hard edge.
                Column(
                    modifier = Modifier
                        .heightIn(max = formMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    JarSelector(
                        jarName = state.jarName,
                        onPick = {
                            pickJar.launch(
                                arrayOf("application/java-archive", "application/zip", "*/*"),
                            )
                        },
                        onUseSample = viewModel::onUseSampleJar,
                    )

                    RuntimeSelector(
                        selected = state.release,
                        installed = state.installedReleases,
                        onSelect = viewModel::onReleaseChange,
                    )

                    // Side by side: both are short single-line values, and the
                    // height they save goes to the console.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = state.mainClass,
                            onValueChange = viewModel::onMainClassChange,
                            label = { Text("Main class") },
                            placeholder = { Text("hello.Hello") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.argsInput,
                            onValueChange = viewModel::onArgsChange,
                            label = { Text("Arguments") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    HeapSelector(
                        heapMb = state.heapMb,
                        onSelect = viewModel::onHeapChange,
                    )

                    RunControls(
                        canRun = state.canRun,
                        // A guest that outlived its `main` is still running the
                        // JAR: a server whose main returned keeps serving. The
                        // label follows the guest rather than the call on the
                        // stack, so a run that is still going is not offered as
                        // one to start again.
                        busy = state.state is RunState.Running || state.guestRunning,
                        // A run can be over while the guest is still up: a server
                        // whose main returned keeps running until it is stopped.
                        guestAlive = state.guestRunning,
                        onRun = {
                            val missing = missingRunPermissions(context)
                            if (missing.isNotEmpty()) requestRunPermissions.launch(missing)
                            if (needsAllFilesAccess()) {
                                requestAllFilesAccess.launch(allFilesAccessSettings(context))
                            }
                            viewModel.run()
                        },
                        onStop = viewModel::stop,
                    )
                }

                // Progress and outcome sit outside the scrolling form, so they
                // stay visible next to the console they describe.
                RunStatus(state.state)

                Console(
                    lines = state.console,
                    onClear = viewModel::clearConsole,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * The least of the window the console is allowed to keep, counting the status
 * line pinned above it.
 */
private val CONSOLE_FLOOR = 240.dp

/**
 * The permissions a run needs that the user has not granted yet.
 *
 * `POST_NOTIFICATIONS` shows the foreground service's notification. From
 * Android 17 (API 37) the platform also gates an app's local-network traffic:
 * without `ACCESS_LOCAL_NETWORK`, connections from another device are dropped
 * while the phone's own — loopback, and its own LAN address, which the kernel
 * delivers locally — keep working, so a JAR that serves looks fine from the
 * phone and is unreachable to the rest of the network.
 */
private fun missingRunPermissions(context: Context): Array<String> {
    val wanted = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    return wanted
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}

/**
 * Whether a run would have to fall back to the app's own directory.
 *
 * "All files access" is what lets a run keep its working directory under the
 * Download folder, where the files it writes are the user's; see
 * `JvmService.runDirectory`, which falls back when this is false.
 */
private fun needsAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

/**
 * Where "All files access" is granted.
 *
 * It is a switch in Settings rather than a dialog, so this is the screen that
 * says what the access is for; the per-app screen is preferred, with the list
 * as a fallback for devices that do not route it.
 */
private fun allFilesAccessSettings(context: Context): Intent {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    return if (perApp.resolveActivity(context.packageManager) != null) {
        perApp
    } else {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}

@Composable
private fun JarSelector(jarName: String?, onPick: () -> Unit, onUseSample: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = jarName ?: "No JAR selected",
                style = MaterialTheme.typography.bodyMedium,
                // A long file name must not wrap and steal height from the console.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Weighted so the pair always fits the width, however narrow the
                // window is or however long the labels grow.
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text("Choose file", maxLines = 1)
                }
                OutlinedButton(onClick = onUseSample, modifier = Modifier.weight(1f)) {
                    Text("Sample JAR", maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeSelector(
    selected: JreReleaseOption,
    installed: Set<String>,
    onSelect: (JreReleaseOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label + if (selected.id in installed) " (installed)" else "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Guest runtime") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RunViewModel.RELEASE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option.label + if (option.id in installed) " (installed)" else "")
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HeapSelector(heapMb: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Guest heap: $heapMb MB", style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Four choices sharing the width. They used to be laid out in a
            // horizontally scrolling row, which put the last one off the edge.
            HEAP_CHOICES_MB.forEach { mb ->
                OutlinedButton(
                    onClick = { onSelect(mb) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "${mb}M",
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunControls(
    canRun: Boolean,
    busy: Boolean,
    guestAlive: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onRun,
            enabled = canRun,
            modifier = Modifier.weight(1f),
        ) {
            // Busy for as long as the JAR is: while its `main` runs, and while a
            // guest left behind by it still serves.
            Text(if (busy) "Running…" else "Run")
        }
        OutlinedButton(
            onClick = onStop,
            // Stopping ends the guest's process, which is meaningful whenever
            // one exists — including after main returned and left a server up.
            enabled = guestAlive,
            modifier = Modifier.weight(1f),
        ) {
            Text("Stop")
        }
    }
}

/** What the current run is doing, if anything. Nothing is shown when idle. */
@Composable
private fun RunStatus(state: RunState) {
    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
    when (state) {
        is RunState.Preparing -> Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Fetching Java ${state.progress.times(100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        is RunState.Failed -> Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(padding),
        )

        is RunState.Finished -> Text(
            text = state.message,
            color = if (state.success) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(padding),
        )

        RunState.Idle, RunState.Running -> Unit
    }
}

@Composable
private fun Console(lines: List<String>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            // Fills the height the caller gave the card, so the log itself gets
            // everything the header does not use.
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Console", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
            if (lines.isEmpty()) {
                Text(
                    "Output from the JAR's main() appears here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
