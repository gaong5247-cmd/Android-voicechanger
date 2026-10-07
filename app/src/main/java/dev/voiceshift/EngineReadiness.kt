package dev.voiceshift

/** Independent capabilities: Shizuku availability never implies audio routing. */
data class EngineReadiness(
    val modelInstalled: Boolean = false,
    val referenceCached: Boolean = false,
    val monitorVerified: Boolean = false,
    val shizukuConnected: Boolean = false,
    val virtualMicVerified: Boolean = false,
    val physicalInputIsolated: Boolean = false,
    val rollbackVerified: Boolean = false,
) {
    val canStartConversion: Boolean
        get() = modelInstalled && referenceCached && virtualMicVerified && physicalInputIsolated && rollbackVerified
}
