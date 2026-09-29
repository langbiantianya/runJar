package com.kxxnzstdsw.runjar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Where the guest runtime comes from, for the credit that names it. */
private const val MOJOLAUNCHER =
    "https://github.com/MojoLauncher/android-openjdk-build-17-25"

/**
 * What the app is for, how it works, and what it is built out of.
 *
 * The licence list is deliberately grouped rather than exhaustive: the shipped
 * classpath is well over a hundred AndroidX artifacts, all under the same
 * licence, and a screen nobody can read is not an attribution. Each row names
 * the project a licence actually covers, and the note under the table accounts
 * for the rest.
 *
 * Nothing on the screen closes it. It stands alone as an activity, so the back
 * gesture is the platform's cross-activity back: the run screen is what the
 * gesture previews and returns to, and the system is what draws and performs
 * it. See AboutActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("About") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Section("What it does") { Purpose() } }
            item { Section("How it works") { Method() } }
            item { Section("Credits") { Credits() } }
            item { Section("Components and licences") { Licences() } }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** A paragraph of body text. */
@Composable
private fun Paragraph(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Purpose() {
    Paragraph(
        "Runs an arbitrary Java JAR on Android, unmodified. Android's own runtime is " +
            "ART, which executes DEX bytecode and cannot run a standard JAR, so this " +
            "app does not try to make it: it starts a complete OpenJDK virtual machine " +
            "inside the app and hands the JAR to that VM.",
    )
}

@Composable
private fun Method() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bullet(
            "The guest runtime is fetched once into the app's own storage — about " +
                "100 MB — and unpacked there. Binaries cannot be executed from shared " +
                "storage, and the guest's libraries are loaded by absolute path anyway.",
        )
        Bullet(
            "A small native bridge makes that runtime's libraries reachable, then " +
                "dlopen()s libjvm.so from it. The VM that comes up is independent of " +
                "ART: its classes are the runtime's own, under its own class loader.",
        )
        Bullet(
            "The guest runs in a process of its own (the \":jvm\" process) as a " +
                "foreground service, so a JAR that serves keeps serving after you " +
                "leave the app, and a fault in the guest takes down only that process " +
                "— not the screen.",
        )
        Bullet(
            "A JVM allows one VM per process and its shutdown is a no-op here, so the " +
                "process is what gets reused: every run starts a fresh one, which is " +
                "what `java -jar` gives each invocation too.",
        )
        Bullet(
            "Everything the JAR prints is redirected into one stream and shown in the " +
                "console, with the run's own directory as its working directory — " +
                "inside the app's storage, so nothing needs a storage permission.",
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodySmall)
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Credits() {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Paragraph(
            "Runtime acquisition and the on-device startup recipe came from " +
                "MojoLauncher: its rolling release publishes the runtime archives, and " +
                "the Android fixes that make them start are the ones its ports — and " +
                "PojavLauncher's before them — worked out. The linker search path, the " +
                "library preload, the freetype rename and the reader for the xz " +
                "archives are all theirs.",
        )
        Text(
            text = MOJOLAUNCHER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { uriHandler.openUri(MOJOLAUNCHER) },
        )
        Paragraph(
            "The runtime inside those archives is OpenJDK, built for Android from the " +
                "OpenJDK Mobile port. The repository that builds them declares no " +
                "licence of its own; the Java runtime it packages is OpenJDK's.",
        )
    }
}

/**
 * A component the app ships or fetches, and the licence it comes under.
 *
 * The runtime is fetched rather than shipped, but it is still a component of
 * what the user ends up running, so it is listed with the rest.
 */
private data class Component(val name: String, val licence: String)

private val COMPONENTS = listOf(
    Component("OpenJDK", "GPLv2 with Classpath Exception"),
    Component("XZ for Java (org.tukaani:xz)", "Public domain"),
    Component("AndroidX / Jetpack Compose", "Apache-2.0"),
    Component("Kotlin standard library", "Apache-2.0"),
    Component("kotlinx.coroutines", "Apache-2.0"),
)

@Composable
private fun Licences() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        COMPONENTS.forEach { component ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = component.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = component.licence,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Paragraph(
            "OpenJDK is the guest runtime, fetched on first run rather than shipped. " +
                "The rest of the classpath is the transitive closure of the other rows " +
                "— the AndroidX modules, JSpecify, and Guava's empty ListenableFuture " +
                "placeholder — and is Apache-2.0.",
        )
    }
}
