package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tcds.catalog.TcDbms
import tcds.connstr.ConnData
import tcds.connstr.connectionFormats

class ConnectionFormatsTest {

    private fun labels(d: ConnData) = connectionFormats(d).map { it.label }
    private fun text(d: ConnData, label: String) = connectionFormats(d).first { it.label == label }.text

    private val pg = ConnData(
        TcDbms.POSTGRES, "localhost", 55001, "orders", "app", "s3cret",
        "jdbc:postgresql://localhost:55001/orders?user=app&password=s3cret",
    )

    @Test
    fun `postgres jdbc`() {
        assertEquals("jdbc:postgresql://localhost:55001/orders?user=app&password=s3cret", text(pg, "JDBC URL"))
    }

    @Test
    fun `postgres npgsql`() {
        assertEquals(
            "Host=localhost;Port=55001;Database=orders;Username=app;Password=s3cret",
            text(pg, "ADO.NET / EF Core (Npgsql)"),
        )
    }

    @Test
    fun `postgres psql conninfo and cli`() {
        assertEquals(
            """psql "host=localhost port=55001 dbname=orders user=app password=s3cret"""",
            text(pg, "psql (conninfo string)"),
        )
        assertEquals(
            "PGPASSWORD='s3cret' psql -h localhost -p 55001 -U app -d orders",
            text(pg, "psql (command line)"),
        )
    }

    @Test
    fun `mysql connector and client`() {
        val my = ConnData(
            TcDbms.MYSQL, "localhost", 33060, "shopdb", "shop", "shop123",
            "jdbc:mysql://localhost:33060/shopdb?user=shop&password=shop123",
        )
        assertEquals(
            "Server=localhost;Port=33060;Database=shopdb;User ID=shop;Password=shop123",
            text(my, "ADO.NET / EF Core (MySqlConnector / Pomelo)"),
        )
        assertEquals(
            "mysql -h localhost -P 33060 -u shop -p'shop123' shopdb",
            text(my, "mysql client"),
        )
    }

    @Test
    fun `mssql sqlclient and sqlcmd`() {
        val ms = ConnData(
            TcDbms.MSSQL, "localhost", 14330, "master", "sa", "S3cret",
            "jdbc:sqlserver://localhost:14330;databaseName=master;user=sa;password=S3cret",
        )
        assertEquals(
            "Server=localhost,14330;Database=master;User Id=sa;Password=S3cret;Encrypt=False;TrustServerCertificate=True",
            text(ms, "ADO.NET / EF Core (Microsoft.Data.SqlClient)"),
        )
        assertEquals(
            "sqlcmd -S localhost,14330 -U sa -P 'S3cret' -d master",
            text(ms, "sqlcmd"),
        )
    }

    @Test
    fun `redis with and without auth`() {
        val auth = ConnData(TcDbms.REDIS, "localhost", 55005, null, "", "sekret", "jdbc:redis://localhost:55005?password=sekret")
        assertEquals("redis-cli -h localhost -p 55005 -a 'sekret'", text(auth, "redis-cli"))
        assertEquals("localhost:55005,password=sekret", text(auth, "StackExchange.Redis"))

        val noAuth = ConnData(TcDbms.REDIS, "localhost", 55006, null, "", "", "jdbc:redis://localhost:55006")
        assertEquals("redis-cli -h localhost -p 55006", text(noAuth, "redis-cli"))
        assertTrue("StackExchange.Redis" !in labels(noAuth))
    }

    @Test
    fun `mongo uri-based formats`() {
        val mo = ConnData(
            TcDbms.MONGO, "localhost", 56001, null, "root", "mongopw",
            "mongodb://root:mongopw@localhost:56001/?authSource=admin",
        )
        assertEquals("""mongosh "mongodb://root:mongopw@localhost:56001/?authSource=admin"""", text(mo, "mongosh (URI)"))
        assertEquals("""MongoClient("mongodb://root:mongopw@localhost:56001/?authSource=admin")""", text(mo, "pymongo"))
    }

    @Test
    fun `mysql without database omits database parts`() {
        val my = ConnData(TcDbms.MYSQL, "localhost", 33061, null, "root", "rootpw", "jdbc:mysql://localhost:33061/?user=root")
        assertEquals("Server=localhost;Port=33061;User ID=root;Password=rootpw", text(my, "ADO.NET / EF Core (MySqlConnector / Pomelo)"))
        assertEquals("mysql -h localhost -P 33061 -u root -p'rootpw'", text(my, "mysql client"))
    }
}
