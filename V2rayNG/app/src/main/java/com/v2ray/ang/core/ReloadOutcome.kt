package com.v2ray.ang.core

/**
 * What is left to do once [CoreServiceManager] has reloaded the core after a network handover.
 *
 * A reload stops Xray and starts it again on a background thread while the service, its tunnel and
 * the Aether core stay up. Two ways of ending it would leave those parts out of step, and the rule is
 * that they run together or not at all: when one of Xray, the Aether core and the service stops, the
 * others stop too.
 *
 * It is kept apart from [CoreServiceManager] because that object loads the native core when it is
 * initialized, which a JVM test cannot do.
 */
internal enum class ReloadOutcome {

    /** Xray runs again and the service is still the one that asked: nothing to do. */
    KEEP_RUNNING,

    /**
     * The reload failed, so Xray is stopped while the tunnel, the root rules and the Aether core are
     * still up around it, swallowing traffic behind a main screen that shows a stopped service. The
     * service is stopped as a whole.
     */
    STOP_SERVICE,

    /**
     * The service was stopped while the reload ran. Its teardown found no Xray to stop, and the
     * reload then started Xray and the Aether core for a service that is gone; they are released.
     */
    RELEASE_CORES;

    companion object {

        /**
         * [coreRunning] is whether Xray runs once the reload is over; [stoppedMeanwhile] is whether
         * the service was stopped while it ran and has not been started again since. A service that
         * was started again owns what runs, and the reload leaves it alone.
         */
        fun of(coreRunning: Boolean, stoppedMeanwhile: Boolean): ReloadOutcome = when {
            stoppedMeanwhile -> RELEASE_CORES
            coreRunning -> KEEP_RUNNING
            else -> STOP_SERVICE
        }

        /**
         * What a client that asks for the state is told. A reload has Xray stopped for a moment while
         * the service stays up, and nothing tells the client once it runs again; answering "not
         * running" then would leave the main screen showing a stopped service that runs. A reload
         * that fails reports it and stops the service, which corrects the answer.
         */
        fun serviceRuns(coreRunning: Boolean, reloading: Boolean): Boolean = coreRunning || reloading
    }
}
