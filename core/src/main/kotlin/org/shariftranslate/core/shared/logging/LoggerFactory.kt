package org.shariftranslate.core.shared.logging

import org.shariftranslate.api.core.Logger

interface LoggerFactory {
    fun getLogger(name: String): Logger
}