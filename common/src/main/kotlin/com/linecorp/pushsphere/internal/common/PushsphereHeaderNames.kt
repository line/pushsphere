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

object PushsphereHeaderNames {
    const val REMOTE_RETRY_MAX_ATTEMPTS = "retry-options-max-attempts"
    const val REMOTE_RETRY_BACKOFF = "retry-options-backoff"
    const val REMOTE_RETRY_TIMEOUT_PER_ATTEMPT = "retry-options-timeout-per-attempt"
    const val REMOTE_RETRY_POLICIES = "retry-options-retry-policies"
    const val REMOTE_HTTP_STATUS_OPTIONS = "retry-options-http-status-options"
    const val REMOTE_RETRY_AFTER_STRATEGY = "retry-options-retry-after-strategy"
    const val REMOTE_RESPONSE_TIMEOUT = "response-timeout"
}
