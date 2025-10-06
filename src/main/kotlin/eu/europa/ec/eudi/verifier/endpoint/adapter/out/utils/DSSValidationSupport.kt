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
package eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils

import arrow.core.Either
import eu.europa.ec.eudi.verifier.endpoint.domain.KeyStoreConfig
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader
import eu.europa.esig.dss.spi.client.http.DSSCacheFileLoader
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.withContext
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.nio.file.Files

object DSSValidationSupport {

    fun createCacheDirectory(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    fun createOfflineLoader(cacheDir: File): DSSCacheFileLoader =
        FileCacheDataLoader().apply {
            setCacheExpirationTime(24 * 60 * 60 * 1000)
            setFileCacheDirectory(cacheDir)
            dataLoader = IgnoreDataLoader()
        }

    fun createOnlineLoader(cacheDir: File): DSSCacheFileLoader =
        FileCacheDataLoader().apply {
            setCacheExpirationTime(24 * 60 * 60 * 1000)
            setFileCacheDirectory(cacheDir)
            dataLoader = CommonsDataLoader()
        }

    suspend fun loadKeyStoreCertificateSource(
        keystoreConfig: KeyStoreConfig,
        dispatcher: CoroutineDispatcher,
        prefix: String,
    ): Either<Throwable, KeyStoreCertificateSource> =
        withContext(dispatcher + CoroutineName("$prefix-${keystoreConfig.keystorePath}")) {
            Either.catch {
                val resource = DefaultResourceLoader().getResource(keystoreConfig.keystorePath)
                KeyStoreCertificateSource(
                    resource.inputStream,
                    keystoreConfig.keystoreType,
                    keystoreConfig.keystorePassword,
                )
            }
        }
}
