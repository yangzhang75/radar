package com.shipradar.uicore.target

/**
 * Edge-detector for target acquisition / loss — drives the ARPA **new-target (3048)** and
 * **lost-target (3052)** alerts (IMO A.823(19) §3.3.2 / IEC 62388 §11). Pure and stateful: each
 * [update] compares the current set of (confirmed) target ids against the previous one and reports
 * which ids just **appeared** and which just **disappeared**. Fully unit-testable, no I/O.
 */
class TargetLifecycle {
    private var previous: Set<String> = emptySet()

    /** ids that appeared since the last update, and ids that disappeared. */
    data class Changes(val appeared: Set<String>, val disappeared: Set<String>) {
        val isEmpty: Boolean get() = appeared.isEmpty() && disappeared.isEmpty()
    }

    fun update(currentIds: Set<String>): Changes {
        val appeared = currentIds - previous
        val disappeared = previous - currentIds
        previous = currentIds
        return Changes(appeared, disappeared)
    }

    fun reset() { previous = emptySet() }
}
