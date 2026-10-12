package tcds.connstr

import tcds.catalog.TcDbms
import java.net.URLDecoder

data class ParsedJdbcUrl(
    val engine: TcDbms,
    val host: String,
    val port: Int,
    val database: String?,
    val user: String?,
    val password: String?,
)

/**
 * Parses the JDBC/URI shapes produced by DataGrip data sources of the supported engines,
 * so "Copy Connection As..." works on foreign (non-plugin) data sources too.
 * Returns null for unsupported or unrecognizable URLs.
 */
fun parseJdbcUrl(url: String): ParsedJdbcUrl? {
    val (engine, rest) = when {
        url.startsWith("jdbc:postgresql://") -> TcDbms.POSTGRES to url.removePrefix("jdbc:postgresql://")
        url.startsWith("jdbc:mysql://") -> TcDbms.MYSQL to url.removePrefix("jdbc:mysql://")
        url.startsWith("jdbc:mariadb://") -> TcDbms.MARIA to url.removePrefix("jdbc:mariadb://")
        url.startsWith("jdbc:sqlserver://") -> TcDbms.MSSQL to url.removePrefix("jdbc:sqlserver://")
        url.startsWith("jdbc:redis://") -> TcDbms.REDIS to url.removePrefix("jdbc:redis://")
        url.startsWith("mongodb://") -> TcDbms.MONGO to url.removePrefix("mongodb://")
        else -> return null
    }
    val defaultPort = when (engine) {
        TcDbms.POSTGRES -> 5432
        TcDbms.MYSQL, TcDbms.MARIA -> 3306
        TcDbms.MSSQL -> 1433
        TcDbms.REDIS -> 6379
        TcDbms.MONGO -> 27017
    }

    var userpass: Pair<String?, String?> = null to null
    var body = rest
    if (engine == TcDbms.MONGO && rest.contains('@')) {
        val (userInfo, after) = rest.substringBeforeLast('@') to rest.substringAfterLast('@')
        val u = userInfo.substringBefore(':').takeIf { it.isNotEmpty() }?.decode()
        val p = userInfo.substringAfter(':', "").takeIf { userInfo.contains(':') }?.decode()
        userpass = u to p
        body = after
    }

    return when (engine) {
        TcDbms.MSSQL -> {
            val hostPort = body.substringBefore(';')
            val params = semicolonParams(body)
            ParsedJdbcUrl(
                engine,
                hostPort.substringBefore(':'),
                hostPort.substringAfter(':', "").toIntOrNull() ?: defaultPort,
                params["databaseName"],
                params["user"],
                params["password"],
            )
        }

        TcDbms.MONGO -> {
            val hostPort = body.substringBefore('/').substringBefore('?')
            ParsedJdbcUrl(
                engine,
                hostPort.substringBefore(':'),
                hostPort.substringAfter(':', "").toIntOrNull() ?: defaultPort,
                body.substringAfter('/', "").substringBefore('?').takeIf { it.isNotEmpty() },
                userpass.first,
                userpass.second,
            )
        }

        else -> {
            val hostPortAndPath = body.substringBefore('?')
            val query = queryParams(body)
            ParsedJdbcUrl(
                engine,
                hostPortAndPath.substringBefore(':').substringBefore('/'),
                hostPortAndPath.substringBefore('/').substringAfter(':', "").toIntOrNull() ?: defaultPort,
                hostPortAndPath.substringAfter('/', "").takeIf { it.isNotEmpty() },
                query["user"],
                query["password"],
            )
        }
    }
}

private fun queryParams(body: String): Map<String, String> =
    body.substringAfter('?', "").split('&').filter { it.contains('=') }
        .associate { it.substringBefore('=').decode() to it.substringAfter('=').decode() }

private fun semicolonParams(body: String): Map<String, String> =
    body.split(';').filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

private fun String.decode(): String = runCatching { URLDecoder.decode(this, Charsets.UTF_8) }.getOrDefault(this)
