package tcds

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tcds.catalog.JdbcTarget
import tcds.catalog.TcDbms
import tcds.ds.RegistrationDecision
import tcds.ds.registrationDecision

class RegistrationDecisionTest {

    private val target = JdbcTarget(
        TcDbms.POSTGRES,
        "jdbc:postgresql://localhost:55001/app?user=app&password=p",
        "app", "p", "app",
    )

    @Test
    fun `no existing data source - create`() {
        assertEquals(RegistrationDecision.CreateNew, registrationDecision(null, null, target))
    }

    @Test
    fun `same url - no change`() {
        assertEquals(RegistrationDecision.NoChange, registrationDecision(target.url, target.user, target))
    }

    @Test
    fun `different url - update in place`() {
        val d = registrationDecision("jdbc:postgresql://localhost:1/old?user=app&password=p", "app", target)
        assertEquals(RegistrationDecision.UpdateInPlace(target.url, target.user), d)
    }
}
