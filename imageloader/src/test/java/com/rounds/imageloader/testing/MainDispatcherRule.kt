package com.rounds.imageloader.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a test main dispatcher, since a JVM unit test has no Android main looper.
 *
 * Declared with `@JvmOverloads` so the Java interoperability test can use it as a plain
 * `new MainDispatcherRule()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule @JvmOverloads constructor(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
