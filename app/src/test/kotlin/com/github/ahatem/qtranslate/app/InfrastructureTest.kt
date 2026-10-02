package com.github.ahatem.qtranslate.app

import java.nio.file.Files
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InfrastructureTest {
    @Test fun explicitDataDirectoryIsIndependentOfWorkingDirectory() {
        val previous = System.getProperty("appData")
        val directory = Files.createTempDirectory("qtranslate-data").resolve("داده فارسی").toFile()
        try {
            System.setProperty("appData", directory.path)
            assertEquals(directory.canonicalFile, AppDataDirectory.resolve().canonicalFile)
            assertTrue(directory.isDirectory)
        } finally {
            if (previous == null) System.clearProperty("appData") else System.setProperty("appData", previous)
        }
    }

    @Test fun singleInstanceFocusAndRelease() {
        val port = ServerSocket(0).use { it.localPort }
        System.setProperty("qtranslate.instancePort", port.toString())
        val focused = CountDownLatch(1)
        try {
            assertTrue(SingleInstanceGuard.tryLock { focused.countDown() })
            assertFalse(SingleInstanceGuard.tryLock {})
            assertTrue(focused.await(3, TimeUnit.SECONDS))
            SingleInstanceGuard.release()
            assertTrue(SingleInstanceGuard.tryLock {})
        } finally { SingleInstanceGuard.release() }
    }
}
