package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tcds.ds.DsOwnership

class DsOwnershipTest {

    private val bases = setOf("postgres", "mysql", "mariadb", "redis", "valkey", "mongo")

    @Test
    fun `marker wins and returns the full container id`() {
        assertEquals(
            "abcdef1234567890abcdef",
            DsOwnership.ownedContainerId("abcdef1234567890abcdef", "TC postgres abcd123", "TC", bases),
        )
    }

    @Test
    fun `name fallback with known image base returns short id`() {
        assertEquals("a1b2c3d", DsOwnership.ownedContainerId(null, "TC postgres a1b2c3d", "TC", bases))
        assertEquals("a1b2c3d", DsOwnership.ownedContainerId(null, "TC redis a1b2c3d", "TC", bases))
    }

    @Test
    fun `name fallback with unknown image token is rejected`() {
        assertNull(DsOwnership.ownedContainerId(null, "TC staging deadbee", "TC", bases))
    }

    @Test
    fun `foreign names are rejected`() {
        assertNull(DsOwnership.ownedContainerId(null, "Production DB", "TC", bases))
        assertNull(DsOwnership.ownedContainerId(null, "TC postgres a1b2c3d extra", "TC", bases))
        assertNull(DsOwnership.ownedContainerId(null, "TC postgres a1b2c3", "TC", bases))
        assertNull(DsOwnership.ownedContainerId(null, "TC postgres A1B2C3D", "TC", bases))
    }

    @Test
    fun `custom prefix is honored`() {
        assertEquals("a1b2c3d", DsOwnership.ownedContainerId(null, "SANDBOX mysql a1b2c3d", "SANDBOX", bases))
        assertNull(DsOwnership.ownedContainerId(null, "TC mysql a1b2c3d", "SANDBOX", bases))
    }

    @Test
    fun `isAlive matches docker id prefixes of any length`() {
        val running = setOf("abcdef123456") // docker ps -q style, 12 chars
        org.junit.jupiter.api.Assertions.assertTrue(DsOwnership.isAlive("abcdef1234567890abcdef12", running)) // full marker id
        org.junit.jupiter.api.Assertions.assertTrue(DsOwnership.isAlive("abcdef1", running)) // name fallback short id
        org.junit.jupiter.api.Assertions.assertFalse(DsOwnership.isAlive("abcdef1", emptySet()))
        org.junit.jupiter.api.Assertions.assertFalse(DsOwnership.isAlive("123456789012345678901234", running))
    }
}
