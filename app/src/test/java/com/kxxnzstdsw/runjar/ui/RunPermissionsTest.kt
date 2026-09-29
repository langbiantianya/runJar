package com.kxxnzstdsw.runjar.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide tells the user what this app asks for, so it has to be the app's
 * real permission set: a name it lists that the manifest does not declare is a
 * promise the APK cannot keep, and a declared permission it omits is one the
 * user can see in the system's app info with nothing to explain it.
 *
 * The API gates matter for the same reason — asking for `POST_NOTIFICATIONS`
 * below Android 13 or `ACCESS_LOCAL_NETWORK` below Android 17 asks for nothing,
 * and a card shown as "not granted" on such a version would describe a decision
 * the user never made.
 */
class RunPermissionsTest {

    @Test
    fun nothingIsAskedForBelowTheVersionThatHasIt() {
        // API 29 (Android 10, the app's floor): neither runtime permission exists.
        assertEquals(emptyList<String>(), RunPermissions.requestableOn(29))

        // API 33 (Android 13) adds POST_NOTIFICATIONS.
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            RunPermissions.requestableOn(33),
        )
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            RunPermissions.requestableOn(36),
        )

        // API 37 (Android 17) adds ACCESS_LOCAL_NETWORK. The request follows the
        // order the guide explains them in, which is the declaration order.
        assertEquals(
            listOf(
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.POST_NOTIFICATIONS",
            ),
            RunPermissions.requestableOn(37),
        )
    }

    @Test
    fun theGuideAndTheManifestDeclareTheSamePermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android:name="(android\.permission\.[A-Z_]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

        // Not a subset either way: the guide is the manifest's only explanation,
        // so the two are the same set or the screen is wrong.
        assertEquals(declared, RunPermissions.declared().map { it.manifestName }.toSet())
        assertTrue(declared.isNotEmpty())
    }
}
