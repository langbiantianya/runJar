package com.kxxnzstdsw.runjar.jvm

import android.os.Build

/**
 * Device-architecture facts the runtime installer needs.
 *
 * The repack script in `android-openjdk-build-17-25` names each per-ABI layer
 * after the Android ABI directory it was built for (`arm`, `arm64`, `x86`,
 * `x86_64`), not after the OpenJDK target triple.
 */
object JreRuntime {

    /** The archive layer matching this device's preferred ABI. */
    fun abiForDevice(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86" -> "x86"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "x86_64"
    }

    /**
     * Whether the guest should be built for 64 bits.
     *
     * A 64-bit guest needs a 64-bit host process: a 32-bit Android process
     * cannot map the 64-bit libjvm.so at all. Android only guarantees a 32-bit
     * process on a 32-bit-only device, so this follows the primary ABI.
     */
    fun is64BitDevice(): Boolean = Build.SUPPORTED_ABIS.firstOrNull()?.endsWith("64") == true
}
