package com.kxxnzstdsw.runjar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.kxxnzstdsw.runjar.R

/**
 * A permission the app declares, and what the app does with it.
 *
 * A JAR's own permissions are never merged into the APK: the guest JVM runs in
 * this app's process, under this app's UID, so a JAR that opens a socket or
 * binds a port needs *this* app to hold the permission. That is what makes the
 * list below the user's business rather than an implementation detail — the
 * permissions the JAR would have carried are the app's.
 *
 * [purposeRes] describes the code that actually uses the permission, not what the
 * permission would allow in general: the guide is what the user has to check the
 * app against, and a reason the app cannot be shown to act on is a reason it
 * should not have declared.
 *
 * The heading and the purpose are resource ids rather than their text, because
 * they are read by the guide screen and nowhere else: the sentence the user
 * reads is the translation the device asks for, in the same way every other
 * string on that screen is.
 *
 * [introducedInApi] is the Android version the permission exists in, and
 * [askedAtRuntime] whether the platform asks the user for it rather than
 * granting it at install. The two are separate: a normal permission arrives
 * granted the moment its version does, and the guide has to say which of the two
 * a card is — "not required on this version" and "not granted yet" are different
 * things to tell someone.
 */
enum class RunPermission(
    /** The declaration, exactly as the manifest spells it. */
    val manifestName: String,
    /** A heading in the user's terms. */
    @param:StringRes val labelRes: Int,
    /** What the app does with it, in the user's terms. */
    @param:StringRes val purposeRes: Int,
    val introducedInApi: Int,
    val askedAtRuntime: Boolean = false,
) {
    INTERNET(
        manifestName = Manifest.permission.INTERNET,
        labelRes = R.string.permission_internet_label,
        purposeRes = R.string.permission_internet_purpose,
        introducedInApi = Build.VERSION_CODES.BASE,
    ),

    ACCESS_LOCAL_NETWORK(
        manifestName = Manifest.permission.ACCESS_LOCAL_NETWORK,
        labelRes = R.string.permission_local_network_label,
        purposeRes = R.string.permission_local_network_purpose,
        introducedInApi = Build.VERSION_CODES.CINNAMON_BUN,
        askedAtRuntime = true,
    ),

    FOREGROUND_SERVICE(
        manifestName = Manifest.permission.FOREGROUND_SERVICE,
        labelRes = R.string.permission_foreground_service_label,
        purposeRes = R.string.permission_foreground_service_purpose,
        introducedInApi = Build.VERSION_CODES.P,
    ),

    FOREGROUND_SERVICE_SPECIAL_USE(
        manifestName = Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        labelRes = R.string.permission_foreground_service_type_label,
        purposeRes = R.string.permission_foreground_service_type_purpose,
        introducedInApi = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    ),

    POST_NOTIFICATIONS(
        manifestName = Manifest.permission.POST_NOTIFICATIONS,
        labelRes = R.string.permission_notifications_label,
        purposeRes = R.string.permission_notifications_purpose,
        introducedInApi = Build.VERSION_CODES.TIRAMISU,
        askedAtRuntime = true,
    ),
    ;

    /**
     * This permission as something to ask the user for on [sdk], or null when
     * [sdk] is not asked for it — because it is granted at install, or because
     * that version does not have it.
     */
    fun requestableOn(sdk: Int): String? =
        manifestName.takeIf { askedAtRuntime && sdk >= introducedInApi }
}

/** What the platform says about a [RunPermission] on the device at hand. */
enum class PermissionState {
    /** A normal permission: granted at install, never asked for. */
    InstallTime,

    /** A runtime permission this Android version does not have. */
    NotOnThisVersion,

    Granted,
    Denied,
}

/**
 * The declared permissions, and the platform's answer about each.
 *
 * The guide screen renders this, and a run asks for what is missing; both read
 * the same list, so what the app asks for cannot drift from what it explains.
 */
object RunPermissions {

    /** The set the guide explains, in the order it explains them. */
    fun declared(): List<RunPermission> = RunPermission.entries

    /**
     * The permissions a device running [sdk] can be asked for, granted or not.
     *
     * Nothing is asked for that the platform does not have: a request for a
     * permission the device's Android version does not know is dropped, and a
     * guide that showed one as "not granted" would be describing a decision the
     * user never made.
     */
    fun requestableOn(sdk: Int): List<String> =
        RunPermission.entries.mapNotNull { it.requestableOn(sdk) }

    /**
     * The runtime permissions a run needs that the user has not granted yet.
     *
     * A refusal costs only what was refused — the run starts either way — so
     * this is asked next to the Run button rather than used to block it.
     */
    fun missing(context: Context, sdk: Int = Build.VERSION.SDK_INT): Array<String> =
        requestableOn(sdk)
            .filter { !isGranted(context, it) }
            .toTypedArray()

    /** What the platform says about [permission] on a device running [sdk]. */
    fun stateOf(
        context: Context,
        permission: RunPermission,
        sdk: Int = Build.VERSION.SDK_INT,
    ): PermissionState {
        if (sdk < permission.introducedInApi) return PermissionState.NotOnThisVersion
        if (!permission.askedAtRuntime) return PermissionState.InstallTime
        return if (isGranted(context, permission.manifestName)) {
            PermissionState.Granted
        } else {
            PermissionState.Denied
        }
    }

    private fun isGranted(context: Context, name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}
