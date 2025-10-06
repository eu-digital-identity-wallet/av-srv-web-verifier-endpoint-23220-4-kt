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
package eu.europa.ec.eudi.verifier.endpoint.adapter.input.web.form

import eu.europa.ec.eudi.verifier.endpoint.adapter.input.web.form.strategy.VpTokenParsingStrategy
import kotlinx.serialization.json.JsonElement
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap

/**
 * Composite extractor that tries multiple parsing strategies
 */
@Component
class VpTokenExtractor(
    private val strategies: List<VpTokenParsingStrategy>,
) {
    /**
     * Tries each strategy until one succeeds
     */
    fun extractVpToken(formData: MultiValueMap<String, String>): JsonElement? =
        strategies.firstNotNullOfOrNull { strategy ->
            strategy.extractVpToken(formData)
        }
}
