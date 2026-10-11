package tcds.catalog

import tcds.model.ContainerInfo
import java.net.URLEncoder

enum class TcDbms { POSTGRES, MYSQL, MARIA, MSSQL, REDIS, MONGO }

data class JdbcTarget(
    val dbms: TcDbms,
    val url: String,
    val user: String,
    val password: String,
    val database: String?,
    val port: Int = 0,
    val host: String = "localhost",
)

/**
 * Maps a running Testcontainers container to a JDBC connection target.
 * Credentials are embedded as URL properties: LocalDataSource.create() has no password
 * parameter and every supported driver accepts user/password from the URL. The driver
 * definition is attached by DataSourceRegistrar via DatabaseDriver.matchesUrl().
 */
object DatabaseImageCatalog {

    private class Spec(
        val baseNames: Set<String>,
        val fullRefs: Set<String> = emptySet(),
        val containerPort: Int,
        val build: (c: ContainerInfo, hostPort: Int) -> JdbcTarget,
    )

    fun resolve(c: ContainerInfo): JdbcTarget? {
        val spec = specs.firstOrNull { c.imageBase in it.baseNames || c.imageRef in it.fullRefs } ?: return null
        val hostPort = c.hostPorts[spec.containerPort]?.takeIf { it > 0 } ?: return null
        return spec.build(c, hostPort)
    }

    /** Image tokens that may appear in plugin-owned data source names (ownership fallback). */
    val knownImageBases: Set<String> get() = specs.flatMapTo(mutableSetOf()) { it.baseNames }

    private fun enc(v: String): String = URLEncoder.encode(v, Charsets.UTF_8)

    private fun creds(user: String, password: String): String =
        "user=${enc(user)}&password=${enc(password)}"

    private val specs = listOf(
        Spec(
            baseNames = setOf("postgres", "postgis", "timescaledb", "pgvector"),
            containerPort = 5432,
        ) { c, host ->
            val user = c.env["POSTGRES_USER"] ?: "postgres"
            val password = c.env["POSTGRES_PASSWORD"] ?: "postgres"
            val db = c.env["POSTGRES_DB"] ?: user
            JdbcTarget(
                TcDbms.POSTGRES,
                "jdbc:postgresql://localhost:$host/${enc(db)}?${creds(user, password)}",
                user, password, db,
                port = host,
            )
        },
        Spec(
            baseNames = setOf("mysql", "mysql-server"),
            containerPort = 3306,
        ) { c, host ->
            val namedUser = c.env["MYSQL_USER"]
            val db = c.env["MYSQL_DATABASE"]
            val user = namedUser ?: "root"
            val password = if (namedUser != null) c.env["MYSQL_PASSWORD"] else c.env["MYSQL_ROOT_PASSWORD"]
            JdbcTarget(
                TcDbms.MYSQL,
                "jdbc:mysql://localhost:$host/${db ?: ""}?${creds(user, password ?: "")}&useSSL=false&allowPublicKeyRetrieval=true",
                user, password ?: "", db,
                port = host,
            )
        },
        Spec(
            baseNames = setOf("mariadb"),
            containerPort = 3306,
        ) { c, host ->
            val namedUser = c.env["MARIADB_USER"] ?: c.env["MYSQL_USER"]
            val db = c.env["MARIADB_DATABASE"] ?: c.env["MYSQL_DATABASE"]
            val user = namedUser ?: "root"
            val password = when {
                namedUser?.startsWith("MARIADB") == true -> c.env["MARIADB_PASSWORD"]
                namedUser != null -> c.env["MARIADB_PASSWORD"] ?: c.env["MYSQL_PASSWORD"]
                else -> c.env["MARIADB_ROOT_PASSWORD"] ?: c.env["MYSQL_ROOT_PASSWORD"]
            }
            JdbcTarget(
                TcDbms.MARIA,
                "jdbc:mariadb://localhost:$host/${db ?: ""}?${creds(user, password ?: "")}",
                user, password ?: "", db,
                port = host,
            )
        },
        Spec(
            baseNames = setOf("azure-sql-edge"),
            fullRefs = setOf("mcr.microsoft.com/mssql/server", "mssql/server", "mcr.microsoft.com/azure-sql-edge"),
            containerPort = 1433,
        ) { c, host ->
            val password = c.env["MSSQL_SA_PASSWORD"] ?: c.env["SA_PASSWORD"] ?: ""
            val db = c.env["MSSQL_DATABASE"] ?: "master"
            JdbcTarget(
                TcDbms.MSSQL,
                "jdbc:sqlserver://localhost:$host;databaseName=$db;user=sa;password=${enc(password)};encrypt=false;trustServerCertificate=true",
                "sa", password, db,
                port = host,
            )
        },
        Spec(
            baseNames = setOf("redis", "redis-stack", "redis-stack-server", "valkey"),
            containerPort = 6379,
        ) { c, host ->
            // official image: `redis-server --requirepass <pw>` in Cmd; bitnami and friends: REDIS_PASSWORD env
            val password = redisPassword(c)
            val url = "jdbc:redis://localhost:$host" +
                (if (password.isNotEmpty()) "?password=${enc(password)}" else "")
            JdbcTarget(
                TcDbms.REDIS,
                url,
                "", password, null,
                port = host,
            )
        },
        Spec(
            baseNames = setOf("mongo"),
            containerPort = 27017,
        ) { c, host ->
            val user = c.env["MONGO_INITDB_ROOT_USERNAME"]
            val password = c.env["MONGO_INITDB_ROOT_PASSWORD"] ?: ""
            val url = if (!user.isNullOrEmpty()) {
                // root users are created in the admin database
                "mongodb://${enc(user)}:${enc(password)}@localhost:$host/?authSource=admin"
            } else {
                "mongodb://localhost:$host/"
            }
            JdbcTarget(TcDbms.MONGO, url, user ?: "", password, null, port = host)
        },
    )

    private fun redisPassword(c: ContainerInfo): String {
        val cmd = c.cmd
        val i = cmd.indexOfFirst { it == "--requirepass" || it.startsWith("--requirepass=") }
        if (i >= 0) {
            val arg = cmd[i]
            return when {
                arg.startsWith("--requirepass=") -> arg.substringAfter('=')
                i + 1 < cmd.size -> cmd[i + 1]
                else -> ""
            }
        }
        return c.env["REDIS_PASSWORD"] ?: ""
    }
}
