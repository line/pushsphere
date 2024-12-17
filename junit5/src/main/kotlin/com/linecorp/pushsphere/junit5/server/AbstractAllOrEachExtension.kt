/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.pushsphere.junit5.server

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A base class for JUnit5 extensions that allows implementations to control whether the callbacks are run
 * around the entire class, like [BeforeAll] or [AfterAll], or around each test method, like
 * [BeforeEach] or [AfterEach]. By default, the extension will run around the entire class -
 * implementations that want to run around each test method should override [.runForEachTest].
 */
abstract class AbstractAllOrEachExtension : BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    /**
     * A method that should be run at the beginning of a test lifecycle. If [.runForEachTest]
     * returns `false`, this is run once before all tests, otherwise it is run before each test
     * method.
     */
    protected abstract fun before(context: ExtensionContext)

    /**
     * A method that should be run at the end of a test lifecycle. If [.runForEachTest]
     * returns `false`, this is run once after all tests, otherwise it is run after each test
     * method.
     */
    protected abstract fun after(context: ExtensionContext)

    override fun beforeAll(context: ExtensionContext) {
        if (!runForEachTest()) {
            before(context)
        }
    }

    override fun afterAll(context: ExtensionContext) {
        if (!runForEachTest()) {
            after(context)
        }
    }

    override fun beforeEach(context: ExtensionContext) {
        if (runForEachTest()) {
            before(context)
        }
    }

    override fun afterEach(context: ExtensionContext) {
        if (runForEachTest()) {
            after(context)
        }
    }

    /**
     * Returns whether this extension should run around each test method instead of the entire test class.
     * Implementations should override this method to return `true` to run around each test method.
     */
    protected fun runForEachTest(): Boolean {
        return false
    }
}
