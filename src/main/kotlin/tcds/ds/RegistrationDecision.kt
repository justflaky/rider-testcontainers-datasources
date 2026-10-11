package tcds.ds

import tcds.catalog.JdbcTarget

sealed interface RegistrationDecision {
    data object CreateNew : RegistrationDecision
    data object NoChange : RegistrationDecision
    data class UpdateInPlace(val url: String, val user: String) : RegistrationDecision
}

/**
 * Decides how to react to a container start when a data source for that container may
 * already exist. Reconnects re-register every running container, so treating "already
 * there" as create would blink the data source and spam notifications.
 */
fun registrationDecision(existingUrl: String?, existingUser: String?, target: JdbcTarget): RegistrationDecision = when {
    existingUrl == null -> RegistrationDecision.CreateNew
    existingUrl == target.url -> RegistrationDecision.NoChange
    else -> RegistrationDecision.UpdateInPlace(target.url, target.user)
}
