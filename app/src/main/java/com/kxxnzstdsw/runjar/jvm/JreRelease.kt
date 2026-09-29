package com.kxxnzstdsw.runjar.jvm

/**
 * The guest runtimes this app can fetch, and the ABIs each one is published for.
 *
 * Binaries come from MojoLauncher's `android-openjdk-build-17-25` rolling
 * release, built from the OpenJDK Mobile port. One archive carries every ABI it
 * supports side by side as `bin-<abi>.tar.xz`, so a single download serves the
 * whole device family; the `universal.tar.xz` member holds the architecture
 * independent files.
 */
private val FOUR_ABIS = listOf("arm", "arm64", "x86", "x86_64")

enum class JreRelease(
    val id: String,
    val majorVersion: Int,
    val assetName: String,
    /** ABI layers the published archive contains, verified against the release. */
    val abis: List<String>,
) {
    JRE_17("jre17", 17, "jre17-pojav.zip", FOUR_ABIS),
    JRE_21("jre21", 21, "jre21-pojav.zip", FOUR_ABIS),

    /**
     * Java 25 is not published for 32-bit x86 — the archive has no
     * `bin-x86.tar.xz`. Offering it on such a device would fail at unpack time,
     * so the choice is filtered out instead.
     */
    JRE_25("jre25", 25, "jre25-pojav.zip", listOf("arm", "arm64", "x86_64")),
    ;

    val assetUrl: String
        get() = "https://github.com/MojoLauncher/android-openjdk-build-17-25" +
                "/releases/download/rolling/$assetName"

    /** Whether this release can run on a device whose guest ABI is [abi]. */
    fun supports(abi: String): Boolean = abi in abis

    companion object {
        val DEFAULT = JRE_21

        fun byId(id: String?): JreRelease? = entries.firstOrNull { it.id == id }

        /** The releases that can actually run on this device. */
        fun availableForDevice(): List<JreRelease> {
            val abi = JreRuntime.abiForDevice()
            return entries.filter { it.supports(abi) }
        }
    }
}
