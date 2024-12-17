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
package com.linecorp.pushsphere.internal.common

import com.linecorp.armeria.common.util.Ticker
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

class SlidingWindowCounter(private val windowSizeNanos: Long) {
    private val windowRef: AtomicReference<Window> = AtomicReference()
    private var ticker: Ticker = Ticker.systemTicker()

    fun get(): Long {
        val currentTimeNanos = ticker.read()
        val currentWindowKey = currentTimeNanos - (currentTimeNanos % windowSizeNanos)
        val prevWindowKey = currentWindowKey - windowSizeNanos

        val currentWindow = acquireWindow(currentWindowKey, prevWindowKey)
        val prevWindow = currentWindow.prevWindow ?: return currentWindow.counter.toLong()

        val prevWeight = 1 - (currentTimeNanos - currentWindowKey) / windowSizeNanos.toDouble()

        return (prevWeight * prevWindow.counter.toDouble()).toLong() + currentWindow.counter.toLong()
    }

    fun count() {
        count(1L)
    }

    fun count(number: Long) {
        val currentTimeNanos = ticker.read()
        val currentWindowKey = currentTimeNanos - (currentTimeNanos % windowSizeNanos)
        val prevWindowKey = currentWindowKey - windowSizeNanos
        val currentWindow = acquireWindow(currentWindowKey, prevWindowKey)

        currentWindow.counter.add(number)
    }

    fun setTicker(ticker: Ticker) {
        this.ticker = ticker
    }

    private fun acquireWindow(
        windowKey: Long,
        prevWindowKey: Long,
    ): Window {
        while (true) {
            val window: Window? = windowRef.get()
            if (window?.windowKey == windowKey) return window

            val prevWindow = if (prevWindowKey == window?.windowKey) window else null
            val newWindow = Window(windowKey, LongAdder(), prevWindow)
            if (windowRef.compareAndSet(window, newWindow)) {
                return newWindow
            }
        }
    }

    data class Window(val windowKey: Long, val counter: LongAdder, val prevWindow: Window?)
}
