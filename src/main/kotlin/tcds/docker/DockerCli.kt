package tcds.docker

import tcds.model.ContainerInfo
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the docker CLI for finite commands (events streaming lives in TcWatcherService).
 * The executor is injectable for tests.
 */
class DockerCli(
    private val exec: (List<String>) -> ExecResult = Companion::runDocker,
) {

    data class ExecResult(val exitCode: Int, val output: String)

    fun daemonVersion(): String? {
        val r = exec(listOf("version", "--format", "{{.Server.Version}}"))
        return if (r.exitCode == 0) r.output.trim().ifEmpty { null } else null
    }

    fun inspect(id: String): ContainerInfo? {
        val r = runCatching { exec(listOf("inspect", "--format", "{{json .}}", id)) }.getOrNull() ?: return null
        if (r.exitCode != 0) return null
        return DockerJson.containerFromInspect(r.output)
    }

    fun runningContainerIds(): List<String> {
        val r = runCatching { exec(listOf("ps", "--filter", "label=${DockerJson.TC_LABEL}=true", "-q")) }.getOrNull()
            ?: return emptyList()
        return if (r.exitCode == 0) DockerJson.containerIds(r.output) else emptyList()
    }

    companion object {
        /**
         * Runs `docker <args>`, merges stdout+stderr, waits up to [timeoutSeconds].
         * Output is drained concurrently: `docker inspect` emits more than the OS pipe
         * buffer, which would otherwise deadlock waitFor().
         */
        fun runDocker(args: List<String>, timeoutSeconds: Long = 15): ExecResult {
            val dockerBin = System.getenv("TCDS_DOCKER_BINARY") ?: "docker"
            val process = try {
                ProcessBuilder(listOf(dockerBin) + args)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                return ExecResult(-1, e.message ?: e.javaClass.simpleName)
            }
            val outputRef = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread({
                outputRef.set(runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
            }, "tcds-docker-cli").apply { isDaemon = true }
            reader.start()
            try {
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                reader.join(2000)
                if (!finished) {
                    process.destroyForcibly()
                    return ExecResult(-1, outputRef.get())
                }
                return ExecResult(process.exitValue(), outputRef.get())
            } catch (e: Exception) {
                runCatching { process.destroyForcibly() }
                return ExecResult(-1, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
