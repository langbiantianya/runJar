package com.kxxnzstdsw.runjar.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kxxnzstdsw.runjar.R

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_about)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Section(stringResource(R.string.about_section_purpose)) { Purpose() }
            }
            item {
                Section(stringResource(R.string.about_section_method)) { Method() }
            }
            item {
                Section(stringResource(R.string.about_section_credits)) { Credits() }
            }
            item {
                Section(stringResource(R.string.about_section_licences)) { Licences() }
            }
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
    Paragraph(stringResource(R.string.about_purpose_body))
}

@Composable
private fun Method() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bullet(stringResource(R.string.about_method_runtime))
        Bullet(stringResource(R.string.about_method_bridge))
        Bullet(stringResource(R.string.about_method_service))
        Bullet(stringResource(R.string.about_method_process))
        Bullet(stringResource(R.string.about_method_console))
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
        Paragraph(stringResource(R.string.about_credits_body))
        Text(
            text = MOJOLAUNCHER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { uriHandler.openUri(MOJOLAUNCHER) },
        )
        Paragraph(stringResource(R.string.about_credits_runtime_body))
    }
}

/**
 * A component the app ships or fetches, and the licence it comes under.
 *
 * The runtime is fetched rather than shipped, but it is still a component of
 * what the user ends up running, so it is listed with the rest.
 *
 * [name] is a proper noun or an artifact coordinate — OpenJDK, kotlinx.coroutines
 * — and reads the same in every language, so it is text. [licenceRes] is a
 * resource because one of the licences is a phrase rather than an identifier.
 */
private data class Component(val name: String, @param:StringRes val licenceRes: Int)

private val COMPONENTS = listOf(
    Component("OpenJDK", R.string.licence_gpl_classpath),
    Component("XZ for Java (org.tukaani:xz)", R.string.licence_public_domain),
    Component("AndroidX / Jetpack Compose", R.string.licence_apache),
    Component("Kotlin standard library", R.string.licence_apache),
    Component("kotlinx.coroutines", R.string.licence_apache),
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
                    text = stringResource(component.licenceRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Paragraph(stringResource(R.string.about_licences_note))
    }
}
