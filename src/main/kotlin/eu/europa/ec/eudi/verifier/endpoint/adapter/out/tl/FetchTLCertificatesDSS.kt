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
package eu.europa.ec.eudi.verifier.endpoint.adapter.out.tl

import arrow.core.Either
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.DSSValidationSupport
import eu.europa.ec.eudi.verifier.endpoint.domain.TrustedListConfig
import eu.europa.ec.eudi.verifier.endpoint.port.out.tl.FetchTLCertificates
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.tsl.cache.CacheCleaner
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.TLSource
import eu.europa.esig.dss.tsl.sync.ExpirationAndSignatureCheckStrategy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.measureTimedValue

private val logger: Logger = LoggerFactory.getLogger(FetchTLCertificatesDSS::class.java)

class FetchTLCertificatesDSS(
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2),
) : FetchTLCertificates, DisposableBean {
    private val dispatcher = executorService.asCoroutineDispatcher()

    override fun destroy() {
        dispatcher.close()
    }

    override suspend fun invoke(
        trustedListConfig: TrustedListConfig,
    ): Either<Throwable, List<X509Certificate>> = Either.catch {
        val trustedListsCertificateSource = TrustedListsCertificateSource()

        val tlCacheDirectory = DSSValidationSupport.createCacheDirectory("tl-cache")

        val offlineLoader = DSSValidationSupport.createOfflineLoader(tlCacheDirectory)
        val onlineLoader = DSSValidationSupport.createOnlineLoader(tlCacheDirectory)

        val cacheCleaner = CacheCleaner().apply {
            setCleanMemory(true)
            setCleanFileSystem(true)
            setDSSFileLoader(offlineLoader)
        }

        val validationJob = TLValidationJob().apply {
            setTrustedListSources(tlSource(trustedListConfig))
            setOfflineDataLoader(offlineLoader)
            setOnlineDataLoader(onlineLoader)
            setTrustedListCertificateSource(trustedListsCertificateSource)
            setSynchronizationStrategy(ExpirationAndSignatureCheckStrategy())
            setCacheCleaner(cacheCleaner)
            setExecutorService(executorService)
        }

        logger.info("Starting TL validation job for: ${trustedListConfig.location}")
        val (certs, duration) = measureTimedValue {
            withContext(dispatcher) {
                validationJob.onlineRefresh()
            }
            trustedListsCertificateSource.certificates.map { it.certificate }
        }
        logger.info("Finished TL validation job in $duration, found ${certs.size} certificates")

        certs.forEachIndexed { index, cert -> // TODO delete later
            logger.info("Certificate ${index + 1}:")
            logger.info("  Subject: ${cert.subjectDN}")
            logger.info("  Issuer: ${cert.issuerDN}")
            logger.info("  Serial Number: ${cert.serialNumber}")
            logger.info("  Valid From: ${cert.notBefore}")
            logger.info("  Valid Until: ${cert.notAfter}")
            logger.info("  ---")
        }

        certs
    }

    private suspend fun tlSource(
        trustedListConfig: TrustedListConfig,
    ): TLSource = TLSource().apply {
        url = trustedListConfig.location.toExternalForm()
        trustedListConfig.keystoreConfig
            ?.let { DSSValidationSupport.loadKeyStoreCertificateSource(it, dispatcher, "TlCertificateSource").getOrNull() }
            ?.let { certificateSource = it }
        trustedListConfig.serviceTypeFilter?.let { filter ->
            trustServicePredicate = java.util.function.Predicate { tspServiceType ->
                tspServiceType.serviceInformation.serviceTypeIdentifier == filter.value
            }
        }
    }
}
