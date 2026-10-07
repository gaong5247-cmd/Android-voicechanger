package dev.voiceshift

import android.content.Context

// Capability is never inferred from su existence, package presence, or a socket.
data class Capability(val name: String, val available: Boolean, val reason: String)
interface VirtualMicBackend { fun probe(): Capability }
class StandardBackend : VirtualMicBackend {
    override fun probe() = Capability("Standard", false, "Android public APIs cannot register a system microphone input supplied by this app.")
}
class ShizukuBackend(private val context: Context) : VirtualMicBackend {
    override fun probe(): Capability {
        val installed = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
        return Capability("Shizuku", false, if (installed) "Installed. No verified HAL injection API available; shell access alone is insufficient." else "Not installed. Installation alone would not create a virtual input.")
    }
}
class RootBackend : VirtualMicBackend {
    override fun probe() = Capability("Root / custom HAL", false, runCatching { Native.probe() }.getOrElse { "Native transport unavailable: ${it.javaClass.simpleName}" } + " Physical bypass, rollback and third-party routing are not certified.")
}
object Native {
    init { System.loadLibrary("voiceshift") }
    external fun probe(): String
    external fun startProbe(): String
    external fun stopProbe()
    external fun stats(): LongArray
}
