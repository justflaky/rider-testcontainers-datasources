package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tcds.docker.DockerJson

class DockerJsonTest {

    @Test
    fun `parses container start event`() {
        val line = """
            {"status":"start","id":"1b50a5bf755f","from":"postgres:16","Type":"container","Action":"start",
            "Actor":{"ID":"1b50a5bf755fabc","Attributes":{"image":"postgres:16","name":"/test-pg","org.testcontainers":"true"}},
            "scope":"local","time":1739012345,"timeNano":1739012345123456789}
        """.trimIndent()
        val event = DockerJson.parseEvent(line)!!
        assertEquals("start", event.action)
        assertEquals("1b50a5bf755fabc", event.containerId)
    }

    @Test
    fun `parses die event`() {
        val line = """{"Type":"container","Action":"die","Actor":{"ID":"deadbeef","Attributes":{}}}"""
        val event = DockerJson.parseEvent(line)!!
        assertEquals("die", event.action)
        assertEquals("deadbeef", event.containerId)
    }

    @Test
    fun `ignores non-container events`() {
        val line = """{"Type":"network","Action":"connect","Actor":{"ID":"net123"}}"""
        assertNull(DockerJson.parseEvent(line))
    }

    @Test
    fun `ignores malformed lines`() {
        assertNull(DockerJson.parseEvent("not json at all"))
        assertNull(DockerJson.parseEvent(""))
    }

    @Test
    fun `parses docker inspect output`() {
        val json = """
        {
          "Id": "abc123def456789",
          "Name": "/testcontainers-pg",
          "Config": {
            "Image": "postgres:16-alpine",
            "Cmd": ["postgres"],
            "Env": ["POSTGRES_USER=app", "POSTGRES_PASSWORD=secret123", "POSTGRES_DB=appdb", "PATH=/usr/bin"],
            "Labels": {"org.testcontainers": "true", "org.testcontainers.lang": "dotnet"}
          },
          "NetworkSettings": {
            "Ports": {
              "5432/tcp": [{"HostIp":"0.0.0.0","HostPort":"55001"}],
              "5433/tcp": []
            }
          }
        }
        """.trimIndent()
        val c = DockerJson.containerFromInspect(json)!!
        assertEquals("abc123def456789", c.id)
        assertEquals("testcontainers-pg", c.name)
        assertEquals("postgres:16-alpine", c.image)
        assertEquals(listOf("postgres"), c.cmd)
        assertEquals(mapOf("POSTGRES_USER" to "app", "POSTGRES_PASSWORD" to "secret123", "POSTGRES_DB" to "appdb", "PATH" to "/usr/bin"), c.env)
        assertEquals(mapOf(5432 to 55001), c.hostPorts)
        assertEquals("postgres", c.imageBase)
    }

    @Test
    fun `rejects inspect of non-testcontainers container`() {
        val json = """
        {"Id":"x","Name":"/plain","Config":{"Image":"postgres:16","Env":[],"Labels":{}},"NetworkSettings":{"Ports":{}}}
        """.trimIndent()
        assertNull(DockerJson.containerFromInspect(json))
    }

    @Test
    fun `parses docker ps -q output`() {
        val out = "abc123\n\ndef456   \n\n"
        assertEquals(listOf("abc123", "def456"), DockerJson.containerIds(out))
    }
}
