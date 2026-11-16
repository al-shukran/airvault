package app.lensly.ui.navigation

/**
 * When the cover locks without the owner asking. [LENSLY] is the policy Lensly has always had: every stop of the app locks the
 * vault, and two minutes without a touch in the hidden area lock it. Lumio builds its own from its settings.
 */
data class LockPolicy(
    /** Milliseconds without a touch after which the vault locks. The countdown starts when the hidden area opens and restarts with each touch. */
    val idleTimeoutMs: Long,
    /**
     * True: a stop of the app locks the vault. False: switching to another app keeps the vault open, and only the idle lock, a
     * return to the root screen, or the end of the process locks it.
     */
    val lockWhenBackgrounded: Boolean,
    /**
     * True: a launch that the app makes itself, such as the document picker or a scanner, keeps the vault open. The idle countdown
     * waits while one is open, and its return counts as a touch. False: the countdown runs through a launch, as it always has.
     */
    val ownLaunchesKeepUnlocked: Boolean,
) {
    companion object {
        /** Lensly's policy, unchanged: a stop locks, two minutes without a touch lock, and a launch does not pause the countdown. */
        val LENSLY = LockPolicy(
            idleTimeoutMs = INACTIVITY_LOCK_MS,
            lockWhenBackgrounded = true,
            ownLaunchesKeepUnlocked = false,
        )
    }
}

/**
 * The lock decisions of the cover, as pure functions of the policy and of the clock, so the rules can be tested on the JVM.
 * Times are milliseconds on one clock.
 */
object LockRules {
    /**
     * Milliseconds left before the idle lock is due at [now]. The countdown starts at the latest of the entry to the hidden area
     * ([enteredAt]), the last touch ([lastTouch]) and, when the policy keeps own launches open, the return of the last one
     * ([launchReturnedAt]). Zero or less means the lock is due.
     */
    fun idleRemainingMs(policy: LockPolicy, enteredAt: Long, lastTouch: Long, launchReturnedAt: Long, now: Long): Long {
        val returned = if (policy.ownLaunchesKeepUnlocked) launchReturnedAt else 0L
        val lastActivity = maxOf(enteredAt, lastTouch, returned)
        return policy.idleTimeoutMs - (now - lastActivity)
    }

    /** True while the idle countdown waits: the policy keeps own launches open, and one is open. */
    fun idlePaused(policy: LockPolicy, launchOpen: Boolean): Boolean = policy.ownLaunchesKeepUnlocked && launchOpen

    /** True when a stop locks the vault. A stop that an own launch causes never locks, whatever the policy says. */
    fun lockOnStop(policy: LockPolicy, causedByOwnLaunch: Boolean): Boolean = policy.lockWhenBackgrounded && !causedByOwnLaunch
}
