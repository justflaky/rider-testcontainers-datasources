package tcds.model

data class ContainerInfo(
    val id: String,
    val image: String,
    val name: String,
    val labels: Map<String, String>,
    val env: Map<String, String>,
    /** container port -> published host port */
    val hostPorts: Map<Int, Int>,
    /** entrypoint args, e.g. ["redis-server", "--requirepass", "secret"] */
    val cmd: List<String> = emptyList(),
) {
    val shortId: String get() = id.take(7)

    /** "registry:5000/postgres:16-alpine" -> "postgres" */
    val imageBase: String
        get() = image.substringAfterLast('/')
            .substringBefore('@')
            .substringBefore(':')
            .lowercase()

    /** full reference without tag/digest: "registry:5000/postgres:16" -> "registry:5000/postgres" */
    val imageRef: String
        get() = image.substringBefore('@').substringBefore(':').lowercase()
}
