package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tcds.catalog.DatabaseImageCatalog
import tcds.catalog.TcDbms
import tcds.model.ContainerInfo

class DatabaseImageCatalogTest {

    private fun container(
        image: String,
        ports: Map<Int, Int>,
        env: Map<String, String> = emptyMap(),
        cmd: List<String> = emptyList(),
    ) = ContainerInfo("abc123def456789", image, "test-container", mapOf("org.testcontainers" to "true"), env, ports, cmd)

    @Test
    fun `postgres with explicit credentials`() {
        val t = DatabaseImageCatalog.resolve(
            container("postgres:16-alpine", mapOf(5432 to 55001), mapOf("POSTGRES_USER" to "app", "POSTGRES_PASSWORD" to "s3cret", "POSTGRES_DB" to "orders")),
        )!!
        assertEquals(TcDbms.POSTGRES, t.dbms)
        assertEquals("jdbc:postgresql://localhost:55001/orders?user=app&password=s3cret", t.url)
        assertEquals("app", t.user)
        assertEquals("s3cret", t.password)
        assertEquals("orders", t.database)
    }

    @Test
    fun `postgres with defaults`() {
        val t = DatabaseImageCatalog.resolve(container("postgres", mapOf(5432 to 32768)))!!
        assertEquals("jdbc:postgresql://localhost:32768/postgres?user=postgres&password=postgres", t.url)
        assertEquals("postgres", t.user)
        assertEquals("postgres", t.password)
    }

    @Test
    fun `postgres from private registry with port`() {
        val t = DatabaseImageCatalog.resolve(
            container("registry.local:5000/postgres:17", mapOf(5432 to 55002), mapOf("POSTGRES_USER" to "u", "POSTGRES_PASSWORD" to "p", "POSTGRES_DB" to "d")),
        )!!
        assertEquals("jdbc:postgresql://localhost:55002/d?user=u&password=p", t.url)
    }

    @Test
    fun `postgres with digest`() {
        val t = DatabaseImageCatalog.resolve(container("postgres@sha256:abcdef", mapOf(5432 to 55003)))!!
        assertEquals("jdbc:postgresql://localhost:55003/postgres?user=postgres&password=postgres", t.url)
    }

    @Test
    fun `postgres password with url-special characters is encoded`() {
        val t = DatabaseImageCatalog.resolve(
            container("postgres:16", mapOf(5432 to 55004), mapOf("POSTGRES_USER" to "a&b", "POSTGRES_PASSWORD" to "p@ss word")),
        )!!
        assertEquals("jdbc:postgresql://localhost:55004/a%26b?user=a%26b&password=p%40ss+word", t.url)
    }

    @Test
    fun `mysql with named user`() {
        val t = DatabaseImageCatalog.resolve(
            container("mysql:8", mapOf(3306 to 33060), mapOf("MYSQL_USER" to "shop", "MYSQL_PASSWORD" to "shop123", "MYSQL_DATABASE" to "shopdb", "MYSQL_ROOT_PASSWORD" to "rootpw")),
        )!!
        assertEquals(TcDbms.MYSQL, t.dbms)
        assertEquals("jdbc:mysql://localhost:33060/shopdb?user=shop&password=shop123&useSSL=false&allowPublicKeyRetrieval=true", t.url)
        assertEquals("shop", t.user)
        assertEquals("shop123", t.password)
    }

    @Test
    fun `mysql falls back to root`() {
        val t = DatabaseImageCatalog.resolve(
            container("mysql:8.4", mapOf(3306 to 33061), mapOf("MYSQL_ROOT_PASSWORD" to "rootpw")),
        )!!
        assertEquals("root", t.user)
        assertEquals("rootpw", t.password)
        assertEquals("jdbc:mysql://localhost:33061/?user=root&password=rootpw&useSSL=false&allowPublicKeyRetrieval=true", t.url)
    }

    @Test
    fun `mariadb with mariadb env names`() {
        val t = DatabaseImageCatalog.resolve(
            container("mariadb:11", mapOf(3306 to 33062), mapOf("MARIADB_USER" to "m", "MARIADB_PASSWORD" to "mpw", "MARIADB_DATABASE" to "mdb", "MARIADB_ROOT_PASSWORD" to "root")),
        )!!
        assertEquals(TcDbms.MARIA, t.dbms)
        assertEquals("jdbc:mariadb://localhost:33062/mdb?user=m&password=mpw", t.url)
        assertEquals("m", t.user)
        assertEquals("mpw", t.password)
    }

    @Test
    fun `mariadb falls back to mysql env names`() {
        val t = DatabaseImageCatalog.resolve(
            container("mariadb:11", mapOf(3306 to 33063), mapOf("MYSQL_ROOT_PASSWORD" to "rootpw")),
        )!!
        assertEquals("root", t.user)
        assertEquals("rootpw", t.password)
    }

    @Test
    fun `sql server by full image ref`() {
        val t = DatabaseImageCatalog.resolve(
            container("mcr.microsoft.com/mssql/server:2022-latest", mapOf(1433 to 14330), mapOf("MSSQL_SA_PASSWORD" to "S3cret!Pass")),
        )!!
        assertEquals(TcDbms.MSSQL, t.dbms)
        assertEquals("jdbc:sqlserver://localhost:14330;databaseName=master;user=sa;password=S3cret%21Pass;encrypt=false;trustServerCertificate=true", t.url)
        assertEquals("sa", t.user)
        assertEquals("S3cret!Pass", t.password)
    }

    @Test
    fun `sql server legacy SA_PASSWORD`() {
        val t = DatabaseImageCatalog.resolve(
            container("mcr.microsoft.com/azure-sql-edge", mapOf(1433 to 14331), mapOf("SA_PASSWORD" to "Old!Pass1")),
        )!!
        assertEquals("sa", t.user)
        assertEquals("Old!Pass1", t.password)
    }

    @Test
    fun `bare server image is not matched`() {
        assertNull(DatabaseImageCatalog.resolve(container("server:2022", mapOf(1433 to 1433))))
    }

    @Test
    fun `unknown image returns null`() {
        assertNull(DatabaseImageCatalog.resolve(container("memcached:1", mapOf(11211 to 11211))))
        assertNull(DatabaseImageCatalog.resolve(container("ryuk:latest", mapOf(8080 to 80810))))
    }

    @Test
    fun `redis without auth`() {
        val t = DatabaseImageCatalog.resolve(container("redis:7-alpine", mapOf(6379 to 55005)))!!
        assertEquals(TcDbms.REDIS, t.dbms)
        assertEquals("jdbc:redis://localhost:55005", t.url)
        assertEquals("", t.user)
        assertEquals("", t.password)
    }

    @Test
    fun `redis with requirepass as separate cmd arg`() {
        val t = DatabaseImageCatalog.resolve(
            container("redis:7", mapOf(6379 to 55006), cmd = listOf("redis-server", "--requirepass", "sekret123")),
        )!!
        assertEquals("jdbc:redis://localhost:55006?password=sekret123", t.url)
        assertEquals("sekret123", t.password)
    }

    @Test
    fun `redis with requirepass as single cmd arg`() {
        val t = DatabaseImageCatalog.resolve(
            container("redis:7", mapOf(6379 to 55007), cmd = listOf("--requirepass=abc")),
        )!!
        assertEquals("jdbc:redis://localhost:55007?password=abc", t.url)
    }

    @Test
    fun `redis password from env (bitnami)`() {
        val t = DatabaseImageCatalog.resolve(
            container("bitnami/redis:7.4", mapOf(6379 to 55008), env = mapOf("REDIS_PASSWORD" to "bitpw")),
        )!!
        assertEquals("jdbc:redis://localhost:55008?password=bitpw", t.url)
    }

    @Test
    fun `valkey is treated as redis`() {
        val t = DatabaseImageCatalog.resolve(container("valkey/valkey:8", mapOf(6379 to 55009)))!!
        assertEquals(TcDbms.REDIS, t.dbms)
        assertEquals("jdbc:redis://localhost:55009", t.url)
    }

    @Test
    fun `redis password with url-special characters is encoded`() {
        val t = DatabaseImageCatalog.resolve(
            container("redis:7", mapOf(6379 to 55010), cmd = listOf("--requirepass", "p@ss&word")),
        )!!
        assertEquals("jdbc:redis://localhost:55010?password=p%40ss%26word", t.url)
    }

    @Test
    fun `database image without published port returns null`() {
        assertNull(DatabaseImageCatalog.resolve(container("postgres:16", mapOf(5432 to 0))))
        assertNull(DatabaseImageCatalog.resolve(container("postgres:16", emptyMap())))
    }
}
