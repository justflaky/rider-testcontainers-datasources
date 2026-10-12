package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tcds.connstr.parseJdbcUrl

class JdbcUrlParserTest {

    @Test
    fun `postgres full`() {
        val p = parseJdbcUrl("jdbc:postgresql://db.prod:5433/orders?user=app&password=s3cret")!!
        assertEquals(tcds.catalog.TcDbms.POSTGRES, p.engine)
        assertEquals("db.prod", p.host)
        assertEquals(5433, p.port)
        assertEquals("orders", p.database)
        assertEquals("app", p.user)
        assertEquals("s3cret", p.password)
    }

    @Test
    fun `postgres default port and no credentials`() {
        val p = parseJdbcUrl("jdbc:postgresql://localhost/appdb")!!
        assertEquals(5432, p.port)
        assertEquals("appdb", p.database)
        assertNull(p.user)
        assertNull(p.password)
    }

    @Test
    fun `mysql with encoded password`() {
        val p = parseJdbcUrl("jdbc:mysql://localhost:3307/shop?user=root&password=p%40ss")!!
        assertEquals(tcds.catalog.TcDbms.MYSQL, p.engine)
        assertEquals(3307, p.port)
        assertEquals("p@ss", p.password)
    }

    @Test
    fun `mariadb`() {
        val p = parseJdbcUrl("jdbc:mariadb://localhost/mdb")!!
        assertEquals(tcds.catalog.TcDbms.MARIA, p.engine)
        assertEquals(3306, p.port)
        assertEquals("mdb", p.database)
    }

    @Test
    fun `sql server semicolon params`() {
        val p = parseJdbcUrl("jdbc:sqlserver://localhost:1434;databaseName=master;user=sa;password=S3cret;encrypt=true")!!
        assertEquals(tcds.catalog.TcDbms.MSSQL, p.engine)
        assertEquals(1434, p.port)
        assertEquals("master", p.database)
        assertEquals("sa", p.user)
        assertEquals("S3cret", p.password)
    }

    @Test
    fun `redis with password query`() {
        val p = parseJdbcUrl("jdbc:redis://cache:6380?password=sekret")!!
        assertEquals(tcds.catalog.TcDbms.REDIS, p.engine)
        assertEquals(6380, p.port)
        assertEquals("sekret", p.password)
    }

    @Test
    fun `mongo userinfo and default port`() {
        val p = parseJdbcUrl("mongodb://root:mongo%40pw@cluster.local/?authSource=admin")!!
        assertEquals(tcds.catalog.TcDbms.MONGO, p.engine)
        assertEquals("cluster.local", p.host)
        assertEquals(27017, p.port)
        assertEquals("root", p.user)
        assertEquals("mongo@pw", p.password)
    }

    @Test
    fun `unsupported or malformed yields null`() {
        assertNull(parseJdbcUrl("jdbc:oracle:thin:@localhost:1521:orcl"))
        assertNull(parseJdbcUrl("mongodb+srv://cluster.example.com/"))
        assertNull(parseJdbcUrl("not a url"))
        assertNull(parseJdbcUrl(""))
    }
}
