package org.shariftranslate.core.main.domain.model

import org.shariftranslate.core.shared.arch.ServiceType

/**
 * UI-facing metadata for a single loaded service.
 * Produced by [org.shariftranslate.core.main.domain.usecase.SelectActiveServiceUseCase]
 * and consumed by service selection dropdowns and the services panel.
 */
data class ServiceInfo(
    val id: String,
    val name: String,
    val iconPath: String?,
    val type: ServiceType
)