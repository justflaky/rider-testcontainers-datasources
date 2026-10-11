package tcds.connstr

import tcds.catalog.TcDbms

data class ConnData(
    val dbms: TcDbms,
    val host: String,
    val port: Int,
    val database: String?,
    val user: String,
    val password: String,
    val jdbcUrl: String,
)

enum class FormatKind { URI, DOTNET, CLI }

data class CopyFormat(val label: String, val text: String, val kind: FormatKind)

/**
 * Renders connection strings in the formats people actually paste:
 * JDBC, ADO.NET / EF Core provider strings, and CLI invocations.
 * Pure logic - fully unit-testable.
 */
fun connectionFormats(d: ConnData): List<CopyFormat> {
    val out = mutableListOf<CopyFormat>()

    fun uri(text: String) {
        out += CopyFormat("JDBC URL", text, FormatKind.URI)
    }

    fun dotnet(body: String, provider: String) {
        out += CopyFormat(".NET ($provider)", body, FormatKind.DOTNET)
    }

    fun cli(label: String, text: String) {
        out += CopyFormat(label, text, FormatKind.CLI)
    }

    uri(d.jdbcUrl)

    when (d.dbms) {
        TcDbms.POSTGRES -> {
            dotnet(
                buildString {
                    append("Host=${d.host};Port=${d.port}")
                    d.database?.let { append(";Database=$it") }
                    if (d.user.isNotEmpty()) append(";Username=${d.user}")
                    if (d.password.isNotEmpty()) append(";Password=${d.password}")
                },
                "Npgsql",
            )
            val connInfo = buildString {
                append("host=${d.host} port=${d.port}")
                d.database?.let { append(" dbname=$it") }
                if (d.user.isNotEmpty()) append(" user=${d.user}")
                if (d.password.isNotEmpty()) append(" password=${d.password}")
            }
            cli("psql — conninfo", """psql "$connInfo"""")
            cli(
                "psql — CLI",
                buildString {
                    if (d.password.isNotEmpty()) append("PGPASSWORD='${d.password}' ")
                    append("psql -h ${d.host} -p ${d.port}")
                    if (d.user.isNotEmpty()) append(" -U ${d.user}")
                    d.database?.let { append(" -d $it") }
                },
            )
        }

        TcDbms.MYSQL, TcDbms.MARIA -> {
            dotnet(
                buildString {
                    append("Server=${d.host};Port=${d.port}")
                    d.database?.let { append(";Database=$it") }
                    if (d.user.isNotEmpty()) append(";User ID=${d.user}")
                    if (d.password.isNotEmpty()) append(";Password=${d.password}")
                },
                "MySqlConnector",
            )
            cli(
                "mysql — CLI",
                buildString {
                    append("mysql -h ${d.host} -P ${d.port}")
                    if (d.user.isNotEmpty()) append(" -u ${d.user}")
                    if (d.password.isNotEmpty()) append(" -p'${d.password}'")
                    d.database?.let { append(" $it") }
                },
            )
        }

        TcDbms.MSSQL -> {
            dotnet(
                buildString {
                    append("Server=${d.host},${d.port}")
                    d.database?.let { append(";Database=$it") }
                    if (d.user.isNotEmpty()) append(";User Id=${d.user}")
                    if (d.password.isNotEmpty()) append(";Password=${d.password}")
                    append(";Encrypt=False;TrustServerCertificate=True")
                },
                "SqlClient",
            )
            cli(
                "sqlcmd",
                buildString {
                    append("sqlcmd -S ${d.host},${d.port}")
                    if (d.user.isNotEmpty()) append(" -U ${d.user}")
                    if (d.password.isNotEmpty()) append(" -P '${d.password}'")
                    d.database?.let { append(" -d $it") }
                },
            )
        }

        TcDbms.REDIS -> {
            cli(
                "redis-cli",
                buildString {
                    append("redis-cli -h ${d.host} -p ${d.port}")
                    if (d.password.isNotEmpty()) append(" -a '${d.password}'")
                },
            )
            if (d.password.isNotEmpty()) {
                cli("StackExchange.Redis", "${d.host}:${d.port},password=${d.password}")
            }
        }

        TcDbms.MONGO -> {
            cli("mongosh", """mongosh "${d.jdbcUrl}"""")
            cli("pymongo", """MongoClient("${d.jdbcUrl}")""")
        }
    }
    return out
}
