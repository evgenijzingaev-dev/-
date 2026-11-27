package ru.smartbasket.app

/** Decisions are based on time since the last observed catalog progress, not total load time. */
object CatalogStallPolicy {
    const val RELOAD_MS = 20_000L
    fun action(idleMs:Long, stage:Int, quietEnd:Boolean):Int = when {
        quietEnd -> 0
        idleMs >= RELOAD_MS -> 3
        idleMs >= 10_000L && stage < 2 -> 2
        idleMs >= 5_000L && stage < 1 -> 1
        else -> 0
    }
}
