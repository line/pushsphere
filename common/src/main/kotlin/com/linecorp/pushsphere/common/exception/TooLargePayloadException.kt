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
package com.linecorp.pushsphere.common.exception

import com.linecorp.pushsphere.common.PushProvider

class TooLargePayloadException(provider: PushProvider, payloadLength: Int, cause: Throwable? = null) : RuntimeException(
    "Payload size exceeds the limit of push provider<$provider>. length: $payloadLength (expected: <= ${provider.maxPayloadByteLength})",
    cause,
) {
    companion object {
        private const val serialVersionUID: Long = -1725191282L
    }
}
