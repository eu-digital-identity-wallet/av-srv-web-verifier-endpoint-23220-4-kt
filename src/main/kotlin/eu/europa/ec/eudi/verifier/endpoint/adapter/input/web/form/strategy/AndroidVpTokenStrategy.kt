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

import arrow.core.Either
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap

/**
 * Handles Android wallet vp_token format: "vp_token" -> JSON Object
 */
@Component
class AndroidVpTokenStrategy : VpTokenParsingStrategy {

    override fun extractVpToken(formData: MultiValueMap<String, String>): JsonElement? =
        formData.getFirst("vp_token")?.toJsonElementOrNull()

    private fun String.toJsonElementOrNull(): JsonElement? =
        Either.catch { Json.decodeFromString<JsonElement>(this) }.getOrNull()
}
