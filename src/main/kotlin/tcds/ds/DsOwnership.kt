package tcds.ds

/**
 * Recognizing which data sources belong to this plugin.
 *
 * Primary mechanism: the container id stored via [LocalDataSource.setAdditionalProperty]
 * under [MARKER_KEY] (additional properties persist with the data source).
 * Fallback for data sources created before the marker existed (or after an IDE restart
 * dropped nothing): name shape "<prefix> <knownImageBase> <7-hex>", where the image token
 * must be a catalog-known database image - this rejects foreign names like
 * "TC staging deadbee" that merely look similar.
 */
object DsOwnership {

    const val MARKER_KEY: String = "tcds.containerId"

    private val nameShape = Regex("^([a-z0-9._-]+) ([0-9a-f]{7})$")

    /**
     * The container id owning this data source (full id from the marker, 7-char short id
     * from the name fallback), or null when the data source is not plugin-owned.
     */
    fun ownedContainerId(
        additionalId: String?,
        name: String,
        prefix: String,
        knownImageBases: Set<String>,
    ): String? {
        if (!additionalId.isNullOrEmpty()) return additionalId
        if (!name.startsWith("$prefix ")) return null
        val match = nameShape.matchEntire(name.removePrefix("$prefix ")) ?: return null
        val (imageBase, shortId) = match.destructured
        return if (imageBase in knownImageBases) shortId else null
    }

    /**
     * Docker ids are prefix-comparable: `docker ps -q` yields 12 chars, the ownership marker
     * stores the full 64-char id from events/inspect, the name fallback stores 7 chars.
     */
    fun isAlive(ownedId: String, runningIds: Set<String>): Boolean =
        runningIds.any { it.startsWith(ownedId) || ownedId.startsWith(it) }
}
