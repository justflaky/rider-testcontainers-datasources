package tcds.docker

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import tcds.model.ContainerInfo

data class DockerEvent(val action: String, val containerId: String)

/** Pure parsing of docker CLI JSON output (unit-testable, no process needed). */
object DockerJson {

    const val TC_LABEL = "org.testcontainers"

    /** One line of `docker events --format json`. Returns null for non-container or malformed lines. */
    fun parseEvent(line: String): DockerEvent? = runCatching {
        val obj = JsonParser.parseString(line).asJsonObject
        if (obj.primitive("Type") != "container") return null
        val action = obj.primitive("Action") ?: return null
        val id = obj.getAsJsonObject("Actor")?.primitive("ID") ?: return null
        DockerEvent(action, id)
    }.getOrNull()

    /** Output of `docker inspect --format "{{json .}}" <id>` (single container object). */
    fun containerFromInspect(json: String): ContainerInfo? = runCatching {
        parseInspectObject(JsonParser.parseString(json).asJsonObject)
    }.getOrNull()

    fun parseInspectObject(obj: JsonObject): ContainerInfo? {
        val id = obj.primitive("Id") ?: return null
        val name = (obj.primitive("Name") ?: id).removePrefix("/")
        val config = obj.getAsJsonObject("Config")
        val image = config?.primitive("Image") ?: ""
        val env = envMap(config?.getAsJsonArray("Env"))
        val cmd = stringList(config?.getAsJsonArray("Cmd"))
        val labels = stringMap(config?.getAsJsonObject("Labels"))
        if (labels[TC_LABEL] != "true") return null
        val ports = hostPorts(obj.getAsJsonObject("NetworkSettings")?.getAsJsonObject("Ports"))
        return ContainerInfo(id, image, name, labels, env, ports, cmd)
    }

    /** Output of `docker ps -q` (one id per line). */
    fun containerIds(psOutput: String): List<String> =
        psOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun envMap(env: JsonArray?): Map<String, String> {
        if (env == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (e in env) {
            val s = e.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val idx = s.indexOf('=')
            if (idx > 0) result[s.substring(0, idx)] = s.substring(idx + 1)
        }
        return result
    }

    private fun hostPorts(ports: JsonObject?): Map<Int, Int> {
        if (ports == null) return emptyMap()
        val result = LinkedHashMap<Int, Int>()
        for ((key, value) in ports.entrySet()) {
            val containerPort = key.substringBefore('/').toIntOrNull() ?: continue
            val bindings = value as? JsonArray ?: continue
            for (b in bindings) {
                val hostPort = (b as? JsonObject)?.primitive("HostPort")?.toIntOrNull() ?: continue
                if (hostPort > 0) {
                    result[containerPort] = hostPort
                    break
                }
            }
        }
        return result
    }

    private fun stringList(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
    }

    private fun stringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((k, v) in obj.entrySet()) {
            v.takeIf { it.isJsonPrimitive }?.asString?.let { result[k] = it }
        }
        return result
    }

    private fun JsonObject.primitive(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement?.asSafeObject(): JsonObject? = this as? JsonObject
}
