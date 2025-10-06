# Verifying MSO MDoc and Managing Trusted Sources

## Table of contents

- [Verifying MSO MDoc and Managing Trusted Sources](#verifying-mso-mdoc-and-managing-trusted-sources)
  - [Table of contents](#table-of-contents)
  - [1. Validating MSO MDoc](#1-validating-mso-mdoc)
  - [2. Loading and Managing Trusted Certificates](#2-loading-and-managing-trusted-certificates)
  - [3. Summary and Suggested Implementation](#3-summary-and-suggested-implementation)
    - [3.1. Load the Trusted List](#31-load-the-trusted-list)
    - [3.2. Validation of the AV Attestation](#32-validation-of-the-av-attestation)

## 1. Validating MSO MDoc

The current implementation of the Verifier uses the library [walt-id/waltid-identity](https://github.com/walt-id/waltid-identity).

### [DeviceResponseValidator.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/out/mso/DeviceResponseValidator.kt)

This class validates the Verifier Presentation. It receives the VerifiablePresentation (VP) and uses a DocumentValidator to validate each MSO MDoc document within the DeviceResponse.

Note: This class is instantiated in [VerifierContext.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/VerifierContext.kt)

### [DocumentValidator.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/out/mso/DocumentValidator.kt)

This class validates each individual document in the DeviceResponse.

Note: DocumentValidator is also instantiated in [VerifierContext.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/VerifierContext.kt)

The key method of this class to analyse is:

```
suspend fun ensureValid(document: MDoc): EitherNel<DocumentError, MDoc> =
    either {
        document.decodeMso()
        val x5CShouldBe = ensureMatchingX5CShouldBe(document, provideTrustSource) // Retrieves the X5CShouldBe from the *ProvideTrustSource*. Which in turn has a list of CA Certificates
        val issuerChain = ensureTrustedChain(document, x5CShouldBe) // Retrieves the chain from the document and check if it is present in the trusted chain, in X5CShouldBe
        zipOrAccumulate(
            { ensureNotExpiredValidityInfo(document, clock, validityInfoShouldBe) }, // Checks the ValidityInfo of the document's MSO is not expired
            { ensureMatchingDocumentType(document) }, // uses the walt.id library to check the docType of the document
            { ensureDigestsOfIssuerSignedItems(document, issuerSignedItemsShouldBe) }, // Uses the walt.id library to check the document's issuer signed items
            { ensureValidIssuerSignature(document, issuerChain, x5CShouldBe.caCertificates())}, // verifies signature of the MSO MDoc document.
        ) { _, _, _, _ -> document }
    }
```

The function 'ensureTrustedChain' from this class is later responsible for retrieving the certificate chain from the MDoc and validate if there are supported by the trusted certificate chain.

```
private fun Raise<Nel<DocumentError.X5CNotTrusted>>.ensureTrustedChain(
    document: MDoc,
    x5CShouldBe: X5CShouldBe,
): NonEmptyList<X509Certificate> =
    either {
        val chain = ensureContainsChain(document)
        ensureValidChain(chain, x5CShouldBe)
    }.toEitherNel().bind()

...

private fun Raise<DocumentError.X5CNotTrusted>.ensureValidChain(
    chain: NonEmptyList<X509Certificate>,
    x5CShouldBe: X5CShouldBe,
): Nel<X509Certificate> {
    val x5cValidator = X5CValidator(x5CShouldBe)
    val validChain = x5cValidator.ensureTrusted(chain).mapLeft { exception ->
        DocumentError.X5CNotTrusted(exception.message)
    }
    return validChain.bind()
}
```

The [X5CValidator.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/out/cert/X5CValidator.kt) later checks the certificate chain of the MDoc with the following function:

```
@Throws(CertPathValidatorException::class)
private fun trustedOrThrow(
    chain: Nel<X509Certificate>,
    trusted: X5CShouldBe.Trusted,
) {
    val factory = CertificateFactory.getInstance("X.509")
    val certPath = factory.generateCertPath(chain)

    val pkixParameters = trusted.asPkixParameters()
    val validator = CertPathValidator.getInstance("PKIX")

    validator.validate(certPath, pkixParameters)
}
```

The previous function validates a certificate chain (chain) against trusted certificates (trusted) using PKIX rules.

### [ValidateSdJwtVcOrMsoMdocVerifiablePresentation.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/out/presentation/ValidateSdJwtVcOrMsoMdocVerifiablePresentation.kt)

Also instantiated in [VerifierContext.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/VerifierContext.kt)

This class handles the validation of a Verifiable Presentation that follows the MsoMdoc format:

```
override suspend fun invoke(
        transactionId: TransactionId?,
        verifiablePresentation: VerifiablePresentation,
        vpFormat: VpFormat,
        nonce: Nonce,
        transactionData: NonEmptyList<TransactionData>?,
        issuerChain: X5CShouldBe.Trusted?,
    ):
    ...
    Format.MsoMdoc -> {
        require(vpFormat is VpFormat.MsoMdoc)
        val validator = deviceResponseValidatorFactory(issuerChain)
        validator.validateMsoMdocVerifiablePresentation(vpFormat, verifiablePresentation).bind()
    }
    ...

private suspend fun DeviceResponseValidator.validateMsoMdocVerifiablePresentation(
        vpFormat: VpFormat.MsoMdoc,
        verifiablePresentation: VerifiablePresentation,
    ): Either<WalletResponseValidationError, VerifiablePresentation.Str> = either {
        ensure(verifiablePresentation is VerifiablePresentation.Str) {
            WalletResponseValidationError.InvalidVpToken("Mso MDoc VC must be a string.")
        }

        val documents = ensureValid(verifiablePresentation.value)
            .mapLeft { error ->
                log.warn("Failed to validate MsoMdoc VC. Reason: '$error'")
                error.toWalletResponseValidationError()
            }
            .bind()

        documents.forEach { document ->
            val issuerAuth = ensureNotNull(document.issuerSigned.issuerAuth) {
                WalletResponseValidationError.InvalidVpToken("DeviceResponse contains unsigned MSO MDoc documents")
            }
            val algorithm = issuerAuth.algorithm.toJwsAlgorithm().bind()
            ensure(algorithm in vpFormat.algorithms) {
                WalletResponseValidationError.InvalidVpToken("MSO MDoc is not signed with a supported algorithms")
            }
        }
        verifiablePresentation
    }
```

## 2. Loading and Managing Trusted Certificates

### [FetchLOTLCertificatesDSS.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/out/lotl/FetchLOTLCertificatesDSS.kt)

**Important**: The FetchLOTLCertificatesDSS class uses the DSS library to load the certificates from the list of trusted lists. However, the 'Digital Signature Service' documentation (see the section [11.1.1.1. Trusted List Source (TLSource)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html#TrustedLists)) provides an example of how to configure the trusted list directly using the TL URL.

Fetches and updates certificates from the List of Trusted Lists (LOTL) using the DSS library.

```
override suspend fun invoke(
    trustedListConfig: TrustedListConfig,
): Result<List<X509Certificate>> = runCatching {
    val trustedListsCertificateSource = TrustedListsCertificateSource()
    val tlCacheDirectory = Files.createTempDirectory("lotl-cache").toFile()
    val offlineLoader: DSSCacheFileLoader = FileCacheDataLoader().apply {
        setCacheExpirationTime(24 * 60 * 60 * 1000)
        setFileCacheDirectory(tlCacheDirectory)
        dataLoader = IgnoreDataLoader()
    }
    val onlineLoader: DSSCacheFileLoader = FileCacheDataLoader().apply {
        setCacheExpirationTime(24 * 60 * 60 * 1000)
        setFileCacheDirectory(tlCacheDirectory)
        dataLoader = CommonsDataLoader()
    }
    val cacheCleaner = CacheCleaner().apply {
        setCleanMemory(true)
        setCleanFileSystem(true)
        setDSSFileLoader(offlineLoader)
    }
    val validationJob = TLValidationJob().apply {
        setListOfTrustedListSources(lotlSource(trustedListConfig))
        setOfflineDataLoader(offlineLoader)
        setOnlineDataLoader(onlineLoader)
        setTrustedListCertificateSource(trustedListsCertificateSource)
        setSynchronizationStrategy(ExpirationAndSignatureCheckStrategy())
        setCacheCleaner(cacheCleaner)
        setExecutorService(executorService)
    }
    logger.info("Starting validation job")
    val (certs, duration) = measureTimedValue {
        withContext(dispatcher) {
            validationJob.onlineRefresh()
        }
        trustedListsCertificateSource.certificates.map {
            it.certificate
        }
    }
    logger.info("Finished validation job in $duration")
    certs
}

private suspend fun lotlSource(
    trustedListConfig: TrustedListConfig,
): LOTLSource = LOTLSource().apply {
    url = trustedListConfig.location.toExternalForm()
    trustedListConfig.keystoreConfig
        ?.let { lotlCertificateSource(it).getOrNull() }
        ?.let { certificateSource = it }
    isPivotSupport = true
    trustAnchorValidityPredicate = GrantedOrRecognizedAtNationalLevelTrustAnchorPeriodPredicate()
    tlVersions = listOf(5, 6)
    trustedListConfig.serviceTypeFilter?.let {
        trustServicePredicate = Predicate { tspServiceType ->
            tspServiceType.serviceInformation.serviceTypeIdentifier == it.value
        }
    }
}

private suspend fun lotlCertificateSource(keystoreConfig: KeyStoreConfig): Result<KeyStoreCertificateSource> =
    withContext(dispatcher + CoroutineName("LotlCertificateSource-${keystoreConfig.keystorePath}")) {
        runCatching {
            val resource = DefaultResourceLoader().getResource(keystoreConfig.keystorePath)
            KeyStoreCertificateSource(
                resource.inputStream,
                keystoreConfig.keystoreType,
                keystoreConfig.keystorePassword,
            )
        }
    }
```

This task is started by the class [RefreshTrustSources.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/adapter/input/timer/RefreshTrustSources.kt) which corresponds to a scheduled job.
The class **RefreshTrustSources** updates the **TrustSourcesConfig** of the **VerifierConfig**, which was initialyzed by the [VerifierContext.kt](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt/blob/v0.5.0/src/main/kotlin/eu/europa/ec/eudi/verifier/endpoint/VerifierContext.kt#L540).

The **TrustSources** is created as a _bean_ and are used by the DocumentValidator.kt previously mentioned (identified by the name 'provideTrustSource'), and have a map of the **X5CShouldBe**. This map corresponds to the chain of certificates that are trusted. This **TrustSources** are also loaded by the **RefreshTrustSource**.

### ProvideTrustSource.kt

The _TrustSources_ class provides trusted certificate chains (_X5CShouldBe_) via regex-matching docTypes.

```
class TrustSources(
    private val revocationEnabled: Boolean = false,
    private val x5CShouldBeMap: MutableMap<Regex, X5CShouldBe> = mutableMapOf(),
) : ProvideTrustSource
```

Updating trusted sources:

```
suspend fun updateWithX5CShouldBe(pattern: Regex, certs: List<X509Certificate>) {
    mutex.withLock {
        val x5CShouldBe = X5CShouldBe(
            rootCACertificates = certs,
            customizePKIX = { isRevocationEnabled = revocationEnabled },
        )
        x5CShouldBeMap[pattern] = x5CShouldBe
        logger.info("TrustSources updated for pattern $pattern with ${x5CShouldBe.caCertificates().size} certificates")
    }
}
```

Trust source lookup:

```
override suspend fun invoke(type: String): X5CShouldBe? =
    mutex.withLock {
        x5CShouldBeMap.entries
            .firstOrNull { (pattern, _) -> pattern.matches(type)
        }?.value
    }
```

### Definition via Environment Variables

The Trusted Sources are loaded from environment variables:

```
Variable: VERIFIER_TRUSTSOURCES_0_PATTERN Description: The regex pattern used to match the trust source to an issuer, based on a credential's docType/vct Example: eu.europa.ec.eudi.pid.*|urn:eu.europa.ec.eudi:pid:.*
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_LOCATION Description: If present, the URL of the List of Trusted Lists from which to load the X509 Certificates for this trust source
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_REFRESHINTERVAL Description: If present, a cron expression with the refresh interval of the List of Trusted Lists in seconds. If not present, the default value is 0 0 * * * *  (every hour) Example: 0 0 */4 * * *
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_SERVICETYPEFILTER Description: If present, the service type filter to be used when loading the List of Trusted Lists. If not present, all service types are loaded. Valid values are PIDProvider, QEEAProvider and PubEAAProvider. Example: PIDProvider
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_KEYSTORE_PATH Description: If present, the URL of the Keystore which contains the public key that was used to sign the List of Trusted Lists Examples: classpath:lotl-key.jks, file:///lotl-key.jks
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_KEYSTORE_TYPE Description: Type of the Keystore which contains the public key that was used to sign the List of Trusted Lists Examples: jks, pkcs12
Variable: VERIFIER_TRUSTSOURCES_0_LOTL_KEYSTORE_PASSWORD Description: If present and non-blank, the password of the Keystore which contains the public key that was used to sign the List of Trusted Lists
Variable: VERIFIER_TRUSTSOURCES_0_KEYSTORE_PATH Description: If present, the URL of the Keystore from which to load the X509 Certificates for this trust source Examples: classpath:trusted-issuers.jks, file:///trusted-issuers.jks
Variable: VERIFIER_TRUSTSOURCES_0_KEYSTORE_TYPE Description: Type of the Keystore from which to load the X509 Certificates for this trust source Examples: jks, pkcs12
Variable: VERIFIER_TRUSTSOURCES_0_KEYSTORE_PASSWORD Description: If present and non-blank, the password of the Keystore from which to load the X509 Certificates for this trust source
```

This variables are parsed into a **Map<Regex, TrustSourceConfig>** by this function in **VerifierContext.kt**:

```
/**
 * Parses the trust sources configuration from the environment.
 * Handles array-like property names: verifier.trustSources[0].pattern, etc.
 */
/* This is the function that is used to parse the trust sources configuration from the environment and into the Verifier Config */
private fun Environment.trustSources(): Map<Regex, TrustSourceConfig>? {
    val trustSourcesConfigMap = mutableMapOf<Regex, TrustSourceConfig>()
    val prefix = "verifier.trustSources"

    var index = 0
    while (true) {
        val indexPrefix = "$prefix[$index]"
        val patternStr = getPropertyOrEnvVariable("$indexPrefix.pattern") ?: break
        val pattern = patternStr.toRegex() // The regex pattern used to match the trust source to an issuer, based on a credential's docType/vct*

        // Parse LOTL configuration if present
        val lotlSourceConfig = getPropertyOrEnvVariable("$indexPrefix.lotl.location")?.takeIf { it.isNotBlank() }?.let { lotlLocation ->
            val location = URI(lotlLocation).toURL()
            val serviceTypeFilter = getPropertyOrEnvVariable<ProviderKind>("$indexPrefix.lotl.serviceTypeFilter")
            val refreshInterval = getPropertyOrEnvVariable("$indexPrefix.lotl.refreshInterval", "0 0 * * * *")

            val lotlKeystoreConfig = parseKeyStoreConfig("$indexPrefix.lotl.keystore")

            TrustedListConfig(location, serviceTypeFilter, refreshInterval, lotlKeystoreConfig)
        }

        // Parse keystore configuration if present
        val keystoreConfig = parseKeyStoreConfig("$indexPrefix.keystore")

        trustSourcesConfigMap[pattern] = TrustSourcesConfig(lotlSourceConfig, keystoreConfig)

        index++
    }

    return trustSourcesConfigMap.ifEmpty {
        fallbackTrustSources()
    }
}
```

## 3. Summary and Suggested Implementation

This section summarizes the key steps for validating the AV Attestation and suggests a recommended implementation approach:

1. Load the Trusted List into memory at the start of the verifier and refresh it daily.
2. Upon receiving the AV Attestation, retrieve the Trusted Lists certificates and extract the certificate used to sign the MSO from the Attestation.
3. Verify the extracted certificate against the Trusted List.
4. Verify the signature of the AV Attestation.

Suggestions for implementing these steps are provided in the following sections.

### 3.1. Loading the Trusted List

The following Java code is from DSS Library section [11.1.1.1. Trusted List Source (TLSource)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html#TrustedLists):

```
TLValidationJob tlValidationJob = new TLValidationJob();
TLSource tlSource = new TLSource();

// Mandatory : The url where the TL needs to be downloaded
tlSource.setUrl("http://www.ssi.gouv.fr/eidas/TL-FR.xml");

// A certificate source which contains the signing certificate(s) for the current trusted list
tlSource.setCertificateSource(getSigningCertificatesForFrenchTL());

// Optional : predicate to filter trust services which are/were granted or equivalent (pre/post eIDAS).
// Input : implementation of TrustServicePredicate interface.
// Default : none (select all)
tlSource.setTrustServicePredicate(new GrantedTrustService());

// Optional : predicate to filter the trust service providers
// Input : implementation of TrustServiceProviderPredicate interface.
// Default : none (select all)
tlSource.setTrustServiceProviderPredicate(new CryptologOnlyTrustServiceProvider());

//instance of CertificateSource where all trusted certificates and their properties (service type,...) are stored.
tlValidationJob.setTrustedListSources(tlSource);

// Initialize the trusted list certificate source to fill with the information extracted from TLValidationJob
TrustedListsCertificateSource trustedListsCertificateSource = new TrustedListsCertificateSource();
tlValidationJob.setTrustedListCertificateSource(trustedListsCertificateSource);

// Update TLValidationJob
tlValidationJob.onlineRefresh();

// Extract X.509 certificates from the TL
trustedListsCertificateSource.getCertificates().stream().map(CertificateToken::getCertificate).toList();
```

Note: the function _getSigningCertificatesForFrenchTL()_ in the previous code seems to be:

```
private CertificateSource getSigningCertificatesForFrenchTL() {
    CertificateSource cs = new CommonCertificateSource();
    cs.addCertificate(DSSUtils.loadCertificateFromBase64EncodedString("MIIFWjCCBEKgAwIBAgISESH4uNBzewNTch8/fZTnHRxBMA0GCSqGSIb3DQEBCwUAMIGXMQswCQYDVQQGEwJGUjEwMC4GA1UECgwnQWdlbmNlIE5hdGlvbmFsZSBkZXMgVGl0cmVzIFPDqWN1cmlzw6lzMRcwFQYDVQQLDA4wMDAyIDEzMDAwMzI2MjExMC8GA1UEAwwoQXV0b3JpdMOpIGRlIENlcnRpZmljYXRpb24gUGVyc29ubmVzIEFBRTEKMAgGA1UEBRMBMzAeFw0xOTA5MDkxMTEyMzdaFw0yMjA5MDkxMTEyMzdaMHwxCzAJBgNVBAYTAkZSMQ0wCwYDVQQKDARBTlRTMRcwFQYDVQQLDA4wMDAyIDEzMDAwNzY2OTEjMCEGA1UEAwwaTWF0aGlldSBKT1JSWSAzMzEwMDAzODk4am0xEDAOBgNVBCoMB01hdGhpZXUxDjAMBgNVBAQMBUpPUlJZMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4iGy9/ATBcm6vIHI0vHgDfvkdaE2QicBcJFyRjexawI8fudrX5ffiMJZV5GCFBERvlu2IwctE0kVWpHGu0QMyLTNl4ZGDhjmgpX0u5zrF0KzKafKVzrKFbo4wr9+ZkUiJChHOWqejVDq40dVbRN5RzAFacIL2A6wyywmreAMnloh+vG2BEgTcj1lWWKc5rJx+ISYvG5j1bmbFYgNnI6RfbbM9QD7g1Bxw91kCPilT1P1L37Ay4kQQhLVDYFEsxBcSRkginO1iFFUlMendzj4RlxEcFwrGj26fIkLOmSOfAzWjkHvCcxgXydc6Y8zNpe1bYFIiNdsyFrK+GwzH26v0wIDAQABo4IBuDCCAbQwCQYDVR0TBAIwADAYBgNVHSAEETAPMA0GCyqBegGBSAMBAgMBMEcGA1UdHwRAMD4wPKA6oDiGNmh0dHA6Ly9jcmwuYW50cy5nb3V2LmZyL2FudHNhdjMvYWNfcGVyc29ubmVzX2FhZV8zLmNybDCBlAYIKwYBBQUHAQEEgYcwgYQwPwYIKwYBBQUHMAGGM2h0dHA6Ly9vY3NwLmFudHMuZ291di5mci9hbnRzYXYzL2FjX3BlcnNvbm5lc19hYWVfMzBBBggrBgEFBQcwAoY1aHR0cDovL3NwLmFudHMuZ291di5mci9hbnRzYXYzL2FjX3BlcnNvbm5lc19hYWVfMy5jZXIwDgYDVR0PAQH/BAQDAgZAMDcGCCsGAQUFBwEDBCswKTAIBgYEAI5GAQEwCAYGBACORgEEMBMGBgQAjkYBBjAJBgcEAI5GAQYBMCQGA1UdEQQdMBuBGW1hdGhpZXUuam9ycnlAc3NpLmdvdXYuZnIwHQYDVR0OBBYEFLGJXUMMaUx1wr2cJA7YxWipXF69MB8GA1UdIwQYMBaAFPVSfQ6yaX5wCwQ3h9ZQDSanC6SDMA0GCSqGSIb3DQEBCwUAA4IBAQCZidW3Bisie+Kf/NajL09gzeYhe0528GD//7z7RlMsMtEK3rCxW+El5lv37Zpi7WTZQN4qboP0K34y3QIzMt2BwUrGhP/u3ZBY/uuxXTD4p6DGZlbwrgnWNjAri2hS7J4T7n3LES/ieNDnj+EMa/d44wUMBQOayNnmDRneEwITljNnBTO1K0hkZwAdGx/5eH8dYEisNyjYAC+hSApN9sZqopU5Mb7Dautv6dqbRJQ2q/BuNqGPKKJKFtgpaVV9pFdetUVnAf/uBqGQ5iDWNCRyXnZ3gW7z747koSvNN2K/jWjA6u1c/cPgiUOBD3I9Ss0An8zcy5nsd+JJhTkOR8zG"));
        return cs;
}
```

Additionally, the following structure from the Verifier Source Code allows you to associate trusted certificates with different docType patterns:

```
class TrustSources(private val revocationEnabled: Boolean = false, private val x5CShouldBeMap: MutableMap<Regex, X5CShouldBe> = mutableMapOf()) : TrustSources {
    private Boolean revocationEnabled = false;
    private  MutableMap<Regex, X5CShouldBe>  x5CShouldBeMap = mutableMapOf();

    // Add/update trusted certs for a given docType pattern
    suspend fun updateWithX5CShouldBe(pattern: Regex, certs: List<X509Certificate>) {
        mutex.withLock {
            val x5CShouldBe = X5CShouldBe(
                rootCACertificates = certs,
                customizePKIX = { isRevocationEnabled = revocationEnabled },
            )
            x5CShouldBeMap[pattern] = x5CShouldBe
            logger.info("TrustSources updated for pattern $pattern with ${x5CShouldBe.caCertificates().size} certificates")
        }
    }

    // Resolve trusted certificates for a given docType
    override suspend fun invoke(type: String): X5CShouldBe? =
    mutex.withLock {
        x5CShouldBeMap.entries
            .firstOrNull { (pattern, _) -> pattern.matches(type) }
            ?.value
    }
}
```

For example, the two previous codes can be used in a function such as:

```
List<X509Certificate> certs = ...; // calls the first code
TrustSources trustSources = new TrustSources();
trustSources.updateWithX5CShouldBe(regex, certs); // adds the certificates retrieved from the trusted list associated to a docType, for example
```

### 3.2. Validation of the AV Attestation

For the VPToken validation section, we will look into the Verifier Source Code. Some Verifier Source Code functions are omitted or simplified for clarity.

The main classes from the Verifier Source Code to considered for the Attestation Validation are the **DeviceResponseValidator** and the **DocumentValidator**. The **DeviceResponseValidator** will handle the VPToken received and call the **DocumentValidator**, which validates the MSO MDoc in the VPToken.

```
class DeviceResponseValidator(private val documentValidator: DocumentValidator) {
    /**
     * Validates the given verifier presentation
     * It could a vp_token or an element of an array vp_token
     */
    suspend fun ensureValid(vp: String): Either<DeviceResponseError, List<MDoc>> =
        either {
            val deviceResponse = ensureCanBeDecoded(vp)
            ensureStatusIsOk(deviceResponse)
            val validDocuments = ensureValidDocuments(deviceResponse, documentValidator).bind()
            validDocuments
        }

    /**
     * Validates each document within the device response.
     * Calls the document validator on each item and aggregates errors if present.
     */
    private suspend fun Raise<DeviceResponseError.InvalidDocuments>.ensureValidDocuments(deviceResponse: DeviceResponse, documentValidator: DocumentValidator): List<MDoc> =
        deviceResponse.documents.withIndex().mapOrAccumulate { (index, document) ->
            documentValidator
                .ensureValid(document) // function that ensures that each document is valid
                .mapLeft { documentErrors -> InvalidDocument(index, document.docType.value, documentErrors) }
                .bind()
        }.mapLeft(DeviceResponseError::InvalidDocuments).bind()
}
```

```
class DocumentValidator( ..., private val provideTrustSource: TrustSources) {

    /**
     * Validates a given MDoc.
     */
    suspend fun ensureValid(document: MDoc): EitherNel<DocumentError, MDoc> =
        either {
            document.decodeMso()
            val x5CShouldBe = ensureMatchingX5CShouldBe(document, provideTrustSource) // retrieves the X5CShouldBe that has the trusted list certificates
            val issuerChain = ensureTrustedChain(document, x5CShouldBe) // validates the issuerSigned certificates of the MDoc with the trusted list certificates
            zipOrAccumulate(
                { ensureNotExpiredValidityInfo(document, clock, validityInfoShouldBe) },
                { ensureMatchingDocumentType(document) },
                { ensureDigestsOfIssuerSignedItems(document, issuerSignedItemsShouldBe) },
                { ensureValidIssuerSignature(document, issuerChain, x5CShouldBe.caCertificates()) },
            ) { _, _, _, _ -> document }
        }
```

The function _ensureValid_ from the **DocumentValidator** class uses helper functions to validate the MSO MDoc. A few of those functions will be presented in the next section since they're key to validating the AV Attestation.

#### 3.2.1. Extracting the Certificate from the Attestation

As stated, steps of the validation of AV Attestation is extracting the certificate from the MSO MDoc. Additionally, the Verifier Source Code also retrieves the trusted lists certificates associated to one MSO MDoc DocType. The function _ensureMatchingX5CShouldBe_ presented next is responsible for retrieving the Trusted List certificates and the function _ensureContainsChain_ extracts the certificate chain from the AV Attestation.

```
class DocumentValidator( ..., private val provideTrustSource: TrustSources) {

    ...

    /**
     * Retrieves trusted certificates (`X5CShouldBe`) for the document's type.
     */
    private suspend fun Raise<Nel<DocumentError.NoMatchingX5CShouldBe>>.ensureMatchingX5CShouldBe(document: MDoc, trustSourceProvider: TrustSources): X5CShouldBe =
        trustSourceProvider(document.docType.value) ?: raise(DocumentError.NoMatchingX5CShouldBe.nel())

    ...

    /**
     * Extracts the X.509 certificate chain from the issuerAuth field of the MDoc.
     */
    private fun Raise<DocumentError.X5CNotTrusted>.ensureContainsChain(document: MDoc): Nel<X509Certificate> {
        val issuerAuth =
            ensureNotNull(document.issuerSigned.issuerAuth) {
                DocumentError.X5CNotTrusted("Missing issuerAuth")
            }
        val chain =
            run {
                val x5c = ensureNotNull(issuerAuth.x5Chain) { DocumentError.X5CNotTrusted("Missing x5Chain") }
                val factory: CertificateFactory = CertificateFactory.getInstance("X.509")
                factory.generateCertificates(x5c.inputStream()).mapNotNull { it as? X509Certificate }.toNonEmptyListOrNull()
            }

        return ensureNotNull(chain) {
            DocumentError.X5CNotTrusted("Empty chain")
        }
    }
    ...
}
```

#### 3.2.2. Validating the Certificate

The function _ensureTrustedChain_ presented in the next snippet of code is responsible for the validation of the AV Attestation certificate. That function will use the function _ensureContainsChain_ presented previously and a class called _X5CValidator_ also presented in this document.

```
class DocumentValidator( ..., private val provideTrustSource: TrustSources) {

    ...
    /**
     * Extracts the issuer signed certificate chain from the MDoc and validates it
     * against the trusted certificates.
     */
    private fun Raise<Nel<DocumentError.X5CNotTrusted>>.ensureTrustedChain(document: MDoc, x5CShouldBe: X5CShouldBe): NonEmptyList<X509Certificate> =
        either {
            val chain = ensureContainsChain(document) // retrieves the certificate chain from the MDoc
            ensureValidChain(chain, x5CShouldBe) // validates the certificate chain against the X5CShouldBe that has the trusted list certificates for the document docType
        }.toEitherNel().bind()

    /**
     * Validates that the provided certificate chain is trusted based on
     * the corresponding Trusted List entries.
     */
    private fun Raise<DocumentError.X5CNotTrusted>.ensureValidChain(
        chain: NonEmptyList<X509Certificate>,
        x5CShouldBe: X5CShouldBe,
    ): Nel<X509Certificate> {
        val x5cValidator = X5CValidator(x5CShouldBe)
        val validChain = x5cValidator.ensureTrusted(chain).mapLeft { exception ->
            DocumentError.X5CNotTrusted(exception.message)
        }
        return validChain.bind()
    }
}
```

```
class X5CValidator(private val x5CShouldBe: X5CShouldBe) {
    /**
     * Validates whether a certificate chain is trusted.
     */
    fun ensureTrusted(chain: Nel<X509Certificate>): Either<CertPathValidatorException, Nel<X509Certificate>> =
        Either.catchOrThrow {
            trustedOrThrow(chain)
            chain
        }

    @Throws(CertPathValidatorException::class)
    fun trustedOrThrow(chain: Nel<X509Certificate>) {
        when (x5CShouldBe) {
            X5CShouldBe.Ignored -> Unit // Do nothing
            is X5CShouldBe.Trusted -> {
                trustedOrThrow(chain, x5CShouldBe)
            }
        }
    }
}

@Throws(CertPathValidatorException::class)
private fun trustedOrThrow(chain: Nel<X509Certificate>, trusted: X5CShouldBe.Trusted) {
    val factory = CertificateFactory.getInstance("X.509")
    val certPath = factory.generateCertPath(chain)

    val pkixParameters = trusted.asPkixParameters()
    val validator = CertPathValidator.getInstance("PKIX")

    validator.validate(certPath, pkixParameters)
}

private fun X5CShouldBe.Trusted.asPkixParameters(): PKIXParameters {
    val trust = rootCACertificates.map { cert -> TrustAnchor(cert, null) }.toSet()
    return PKIXParameters(trust).apply(customizePKIX)
}
```

Note: The 'rootCACertificates' previously mentioned will be the certificates from the trusted list.

Assuming that the MSO is signed by DS certificate and said DS certificate is known to be directly trusted, the last 'trustedOrThrow' could be replaced with:

```
@Throws(CertPathValidatorException::class)
private fun trustedOrThrow(chain: Nel<X509Certificate>, trusted: X5CShouldBe.Trusted) {
    val lastCertInChain = chain.last()

    val isTrusted = trusted.rootCACertificates.any { trustedCert ->
        trustedCert == lastCertInChain
    }

    if (!isTrusted) {
        throw CertPathValidatorException("The last certificate in the chain is not trusted.")
    }
}
```

This simplified approach assumes the last certificate in the chain is directly trusted (e.g., a DS certificate from the Trusted List).

#### 3.2.3. Verifying the Attestation Signature

Lastly, it is presented the function responsible for the validation of the MSO MDoc signature. This function was based on the Verifier Source Code with some simplication to improve clarity.

```
class DocumentValidator( ..., private val provideTrustSource: TrustSources) {

    ...

    /*Validates the MSO signature*/
    private fun Raise<DocumentError>.ensureValidIssuerSignature(
        document: MDoc,
        chain: NonEmptyList<X509Certificate>,
        caCertificates: List<X509Certificate>,
    ) {
        val issuerECKey = ensureIssuerKeyIsEC(chain.head)
        val issuerKeyInfo = COSECryptoProviderKeyInfo(
            keyID = "ISSUER_KEY_ID",
            algorithmID = issuerECKey.coseAlgorithmID,
            publicKey = issuerECKey.toECPublicKey(),
            privateKey = null,
            x5Chain = chain,
            trustedRootCAs = caCertificates,
        )
        val issuerCryptoProvider = SimpleCOSECryptoProvider(listOf(issuerKeyInfo))
        ensure(document.verifySignature(issuerCryptoProvider, issuerKeyInfo.keyID)) {
            DocumentError.InvalidIssuerSignature
        }
    }

    ...
}
```

Additional information about the class 'COSECryptoProviderKeyInfo' used in the signature validation function can be found in [https://github.com/walt-id/waltid-identity/blob/main/waltid-libraries/credentials/waltid-mdoc-credentials/src/jvmMain/kotlin/id/walt/mdoc/COSECryptoProviderKeyInfo.kt](https://github.com/walt-id/waltid-identity/blob/main/waltid-libraries/credentials/waltid-mdoc-credentials/src/jvmMain/kotlin/id/walt/mdoc/COSECryptoProviderKeyInfo.kt). Note: The x5Chain parameter in this function is specified as "certificate chain, including intermediate and signing key certificates, but excluding root CA certificate!".
