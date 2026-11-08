package nl.r1a.grassmap.core.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.r1a.grassmap.plugin.api.service.HeadingSource

/** A heading source for platforms, tests and builds without a compass. */
object NoHeadingSource : HeadingSource {
    private val state = MutableStateFlow<Double?>(null)

    override val headingDegrees: StateFlow<Double?> = state.asStateFlow()
}
