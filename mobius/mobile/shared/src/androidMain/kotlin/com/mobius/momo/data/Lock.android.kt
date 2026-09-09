@file:Suppress("EXPECT_ACTUAL_CLASSES_IN_BETA_WARNING")

package com.mobius.momo.data

import java.util.concurrent.locks.ReentrantLock as JvmReentrantLock
import kotlin.concurrent.withLock

internal actual class ReentrantLock {
    private val delegate = JvmReentrantLock()

    actual fun <T> withLock(block: () -> T): T = delegate.withLock { block() }
}
