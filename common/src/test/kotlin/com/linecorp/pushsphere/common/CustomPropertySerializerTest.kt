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
package com.linecorp.pushsphere.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class CustomPropertySerializerTest {
    private val serializer = CustomPropertySerializer()

    @Test
    fun `null`() {
        toJson(null) shouldBe "null"
        fromJson("null") shouldBe null
    }

    @Test
    fun boolean() {
        toJson(true) shouldBe "true"
        fromJson("true") shouldBe true
        toJson(false) shouldBe "false"
        fromJson("false") shouldBe false
    }

    @Test
    fun short() {
        toJson(1.toShort()) shouldBe "1"
        fromJson("1") shouldBe 1
    }

    @Test
    fun int() {
        toJson(1) shouldBe "1"
        fromJson("1") shouldBe 1
    }

    @Test
    fun long() {
        toJson(1L) shouldBe "1"
        fromJson("1") shouldBe 1

        // A number out of Int range should be serialized correctly.
        toJson(Long.MAX_VALUE) shouldBe "9223372036854775807"
        toJson(Long.MIN_VALUE) shouldBe "-9223372036854775808"

        // A nunber out of Int range must be deserialized into Long.
        fromJson("9223372036854775807") shouldBe Long.MAX_VALUE
        fromJson("-9223372036854775808") shouldBe Long.MIN_VALUE
    }

    @Test
    fun float() {
        toJson(3.14f) shouldBe "3.14"
        fromJson("3.14") shouldBe 3.14 // Always deserialized into Double.
    }

    @Test
    fun double() {
        toJson(2.71828) shouldBe "2.71828"
        fromJson("2.71828") shouldBe 2.71828
    }

    @Test
    fun string() {
        toJson("foo") shouldBe "\"foo\""
        fromJson("\"foo\"") shouldBe "foo"
    }

    @Test
    fun array() {
        toJson(arrayOf(1, 2, 3)) shouldBe "[1,2,3]"
        fromJson("[1,2,3]") shouldBe listOf(1, 2, 3) // Always deserialized into List.
    }

    @Test
    fun list() {
        toJson(listOf(1, 2, 3)) shouldBe "[1,2,3]"
        fromJson("[1,2,3]") shouldBe listOf(1, 2, 3)

        // Elements with different types
        toJson(listOf(1, "foo", 3.14)) shouldBe "[1,\"foo\",3.14]"
        fromJson("[1,\"foo\",3.14]") shouldBe listOf(1, "foo", 3.14)
    }

    @Test
    fun map() {
        toJson(mapOf("a" to "b", "c" to 4)) shouldBe "{\"a\":\"b\",\"c\":4}"
        fromJson("{\"a\":\"b\",\"c\":4}") shouldBe mapOf("a" to "b", "c" to 4)

        // A non-string key should be converted to a string.
        toJson(mapOf(1 to "b", null to 4)) shouldBe "{\"1\":\"b\",\"null\":4}"

        // A null value should be removed.
        toJson(mapOf("a" to null, "b" to 4)) shouldBe "{\"b\":4}"
    }

    @Test
    fun `complex list`() {
        toJson(listOf(1, listOf(2, 3), mapOf("a" to "b"))) shouldBe "[1,[2,3],{\"a\":\"b\"}]"
    }

    @Test
    fun `complex map`() {
        toJson(mapOf("a" to listOf(1, 2), "b" to mapOf("c" to 3))) shouldBe "{\"a\":[1,2],\"b\":{\"c\":3}}"
    }

    @Test
    fun `unsupported types`() {
        toJson(object {}) shouldMatch """".*@[0-9a-f]+"""" // Just toString()
    }

    private fun toJson(value: Any?): String {
        return Json.encodeToString(serializer, value)
    }

    private fun fromJson(json: String): Any? {
        return Json.decodeFromString(serializer, json)
    }
}
