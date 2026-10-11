package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tcds.ds.redactUrl

class RedactedUrlTest {

    @Test
    fun `query-string password is redacted (postgres)`() {
        assertEquals(
            "jdbc:postgresql://localhost:55001/app?user=app&password=***",
            redactUrl("jdbc:postgresql://localhost:55001/app?user=app&password=s3cret"),
        )
    }

    @Test
    fun `semicolon password is redacted (sql server)`() {
        assertEquals(
            "jdbc:sqlserver://localhost:1433;databaseName=master;user=sa;password=***;encrypt=false",
            redactUrl("jdbc:sqlserver://localhost:1433;databaseName=master;user=sa;password=S3cret!Pass;encrypt=false"),
        )
    }

    @Test
    fun `userinfo password is redacted (mongo)`() {
        assertEquals(
            "mongodb://root:***@localhost:27017/?authSource=admin",
            redactUrl("mongodb://root:mongopw@localhost:27017/?authSource=admin"),
        )
    }

    @Test
    fun `url without credentials stays intact`() {
        assertEquals("jdbc:redis://localhost:55005", redactUrl("jdbc:redis://localhost:55005"))
        assertEquals("mongodb://localhost:56002/", redactUrl("mongodb://localhost:56002/"))
    }

    @Test
    fun `encoded special characters in password are fully redacted`() {
        assertEquals(
            "jdbc:postgresql://localhost:55004/app?user=a%26b&password=***",
            redactUrl("jdbc:postgresql://localhost:55004/app?user=a%26b&password=p%40ss+word"),
        )
    }
}
