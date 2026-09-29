package com.kxxnzstdsw.runjar.jvm

/** Messages exchanged with [JvmService] and the run-status broadcasts it sends. */
object JvmProtocol {

    /** Action of the intent that starts a run. */
    const val CMD_RUN = "run"

    /** Action of the intent that abandons the current run. */
    const val CMD_STOP = "stop"

    /**
     * Action of the intent that ends the guest process regardless of whether a
     * run is in flight.
     */
    const val CMD_SHUTDOWN = "shutdown"

    /** Action of the broadcast the service sends when a run ends. */
    const val ACT_RUN_FINISHED = "com.kxxnzstdsw.runjar.RUN_FINISHED"

    /** Prefix of a failed status, followed by the reason. */
    const val STATUS_ERROR = "ERROR"

    /** The JAR's main returned and left nothing running. */
    const val STATUS_OK = "OK"

    /**
     * The JAR's main returned but left non-daemon threads behind — a web server,
     * a scheduler. The JVM stays up, as it would under `java -jar`.
     */
    const val STATUS_STILL_RUNNING = "OK:STILL_RUNNING"

    /** Machine-readable outcome, one of the `STATUS_*` values. */
    const val EXTRA_STATUS = "status"

    /** The same outcome worded for the user. */
    const val EXTRA_MESSAGE = "message"
}
