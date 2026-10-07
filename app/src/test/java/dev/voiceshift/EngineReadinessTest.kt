package dev.voiceshift

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReadinessTest {
    @Test fun missingModelPreventsStart() {
        assertFalse(EngineReadiness(referenceCached=true, virtualMicVerified=true, physicalInputIsolated=true, rollbackVerified=true).canStartConversion)
    }
    @Test fun shizukuAndMonitorDoNotImplyVirtualMic() {
        val readiness=EngineReadiness(modelInstalled=true, referenceCached=true, monitorVerified=true, shizukuConnected=true)
        assertFalse(readiness.virtualMicVerified)
        assertFalse(readiness.canStartConversion)
    }
    @Test fun routingRequiresPhysicalIsolationAndRollback() {
        val readiness=EngineReadiness(modelInstalled=true, referenceCached=true, virtualMicVerified=true)
        assertFalse(readiness.canStartConversion)
        assertTrue(readiness.copy(physicalInputIsolated=true, rollbackVerified=true).canStartConversion)
    }
}
