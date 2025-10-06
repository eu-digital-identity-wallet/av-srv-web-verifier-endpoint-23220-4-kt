/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.verifier.endpoint.adapter.input.web.form.strategy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap

/**
 * Handles iOS wallet vp_token format: "vp_token[key]" -> String value
 */
@Component
class IosVpTokenStrategy : VpTokenParsingStrategy {

    companion object {
        private val VP_TOKEN_PATTERN = "^vp_token\\[([^\\[\\]]+)]$".toRegex()
    }

    override fun extractVpToken(formData: MultiValueMap<String, String>): JsonElement? {
        val vpTokenEntries = formData.keys
            .mapNotNull { key -> VP_TOKEN_PATTERN.matchEntire(key)?.let { it.groupValues[1] to key } }
            .mapNotNull { (innerKey, fullKey) ->
                formData.getFirst(fullKey)?.let { innerKey to it }
            }

        return if (vpTokenEntries.isNotEmpty()) {
            buildJsonObject {
                vpTokenEntries.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            }
        } else null
    }
}
