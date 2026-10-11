package tcds.ds

/**
 * Logs travel (bug trackers, shared drives) - container credentials must not.
 * Covers all URL shapes produced by DatabaseImageCatalog:
 * `?password=x&…` (postgres/mysql/mariadb/redis), `;password=x;` (SQL Server),
 * and `mongodb://user:password@host` userinfo.
 */
fun redactUrl(url: String): String = url
    .replace(Regex("(password=)[^;&]+"), "$1***")
    .replace(Regex("(//[^:/@]+:)[^@]+(@)"), "$1***$2")
