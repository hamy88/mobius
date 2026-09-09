@file:Suppress("EXPECT_ACTUAL_CLASSES_IN_BETA_WARNING")

package com.mobius.momo.data

internal actual class ReentrantLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
