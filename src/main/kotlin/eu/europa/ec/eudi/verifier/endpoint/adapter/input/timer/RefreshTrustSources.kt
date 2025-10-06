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
package eu.europa.ec.eudi.verifier.endpoint.adapter.input.timer

import eu.europa.ec.eudi.verifier.endpoint.adapter.out.cert.TrustSources
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.cert.X5CShouldBe
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.getOrThrow
import eu.europa.ec.eudi.verifier.endpoint.domain.KeyStoreConfig
import eu.europa.ec.eudi.verifier.endpoint.domain.TrustSourceConfig
import eu.europa.ec.eudi.verifier.endpoint.domain.TrustedListConfig
import eu.europa.ec.eudi.verifier.endpoint.domain.TrustedListType
import eu.europa.ec.eudi.verifier.endpoint.domain.ValidationType
import eu.europa.ec.eudi.verifier.endpoint.domain.VerifierConfig
import eu.europa.ec.eudi.verifier.endpoint.port.out.lotl.FetchLOTLCertificates
import eu.europa.ec.eudi.verifier.endpoint.port.out.tl.FetchTLCertificates
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.InitializingBean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.security.cert.X509Certificate

@EnableScheduling
class RefreshTrustSources(
    private val fetchLOTLCertificates: FetchLOTLCertificates,
    private val fetchTLCertificates: FetchTLCertificates,
    private var trustSources: TrustSources,
    private val verifierConfig: VerifierConfig,
) : InitializingBean, SchedulingConfigurer {
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(2)

    override fun afterPropertiesSet() {
        runBlocking { updateAllTrustSources() }
    }

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        // Configure refresh tasks for each trust source configuration
        verifierConfig.trustSourcesConfig?.forEach { (regex, trustSourceConfig) ->
            trustSourceConfig.leftOrNull()?.let {
                taskRegistrar.addCronTask(
                    CronTask(
                        {
                            CoroutineScope(ioDispatcher + CoroutineName("$regex")).launch {
                                updateTrustSource(regex, trustSourceConfig)
                            }
                        },
                        it.refreshInterval,
                    ),
                )
            }
        }
    }

    private suspend fun updateAllTrustSources() =
        withContext(ioDispatcher + CoroutineName("initializing LOTL(s)")) {
            when (verifierConfig.trustSourcesConfig) {
                null -> trustSources.ignoreAll()
                else ->
                    verifierConfig.trustSourcesConfig
                        .map { (regex, trustSourceConfig) ->
                            launch { updateTrustSource(regex, trustSourceConfig) }
                        }.joinAll()
            }
        }

    private suspend fun updateTrustSource(regex: Regex, trustSourceConfig: TrustSourceConfig) {
        val trustedListConfig = trustSourceConfig.leftOrNull() ?: return

        try {
            val certs = trustSourceConfig.fetchCerts()
            if (trustedListConfig.validationType == ValidationType.DS) {
                trustSources.updateWithDirectlyTrustedDSCertificates(regex, certs)
            } else {
                trustSources.updateWithX5CShouldBe(regex, certs)
            }
        } catch (e: Exception) {
            // TODO: log error
            throw e
        }
    }

    private suspend fun TrustSourceConfig.fetchCerts(): List<X509Certificate> = coroutineScope {
        suspend fun TrustedListConfig.smartFetch(): List<X509Certificate> {
            return when (this.type) {
                TrustedListType.TL -> {
                    fetchTLCertificates(this).getOrThrow()
                }
                TrustedListType.LOTL -> {
                    fetchLOTLCertificates(this).getOrThrow()
                }
            }
        }

        suspend fun KeyStoreConfig.keyCerts(): List<X509Certificate> =
            withContext(ioDispatcher) {
                val x5CShouldBe = X5CShouldBe.fromKeystore(keystore)
                x5CShouldBe.caCertificates()
            }

        fold(
            fa = { trustedList ->
                trustedList.smartFetch()
            },
            fb = { keyStore ->
                keyStore.keyCerts()
            },
            fab = { trustedList, keyStore ->
                val lotlOrTlCerts = async { trustedList.smartFetch() }
                val ksCerts = async { keyStore.keyCerts() }
                ksCerts.await() + lotlOrTlCerts.await()
            },
        )
    }
}
