package com.linecorp.pushsphere.server

import com.linecorp.armeria.client.InvalidHttpResponseException
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.Flags
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.HttpStatusClass
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.logging.LogFormatter
import com.linecorp.armeria.common.logging.LogLevel
import com.linecorp.armeria.common.logging.LogWriter
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries
import com.linecorp.armeria.common.util.Sampler
import com.linecorp.armeria.common.util.SystemInfo
import com.linecorp.armeria.internal.common.util.KeyStoreUtil
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerListenerAdapter
import com.linecorp.armeria.server.ServerPort
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.TransientServiceOption
import com.linecorp.armeria.server.annotation.ResponseConverterFunction
import com.linecorp.armeria.server.auth.AuthService
import com.linecorp.armeria.server.docs.DocService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import com.linecorp.armeria.server.logging.AccessLogWriter
import com.linecorp.armeria.server.logging.ContentPreviewingService
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.server.management.ManagementService
import com.linecorp.armeria.server.metric.MetricCollectingService
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService
import com.linecorp.pushsphere.common.AppleCredentials
import com.linecorp.pushsphere.common.AppleKeyPairCredentials
import com.linecorp.pushsphere.common.AppleTokenCredentials
import com.linecorp.pushsphere.common.CircuitBreakerOptions
import com.linecorp.pushsphere.common.ConnectionOutlierDetectionOptions
import com.linecorp.pushsphere.common.EndpointGroupOptions
import com.linecorp.pushsphere.common.NetworkOptions
import com.linecorp.pushsphere.common.Profile
import com.linecorp.pushsphere.common.ProfileSet
import com.linecorp.pushsphere.common.PushProvider
import com.linecorp.pushsphere.common.PushResult
import com.linecorp.pushsphere.common.PushResultSource
import com.linecorp.pushsphere.common.PushStatus
import com.linecorp.pushsphere.common.RetryOptions
import com.linecorp.pushsphere.common.RetryRateLimitOptions
import com.linecorp.pushsphere.common.URI
import com.linecorp.pushsphere.common.credentials.GoogleServiceAccountCredentials
import com.linecorp.pushsphere.internal.common.Path
import com.linecorp.pushsphere.server.PushAuthorizer.Companion.asArmeriaAuthorizer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.net.InetSocketAddress
import java.time.Duration
import java.util.ServiceLoader
import java.util.function.Function
import kotlin.reflect.KClass

class PushServer(
    hostname: String? = null,
    ports: List<PushServerPort> = emptyList(),
    management: ManagementConfig? = null,
    healthCheck: HealthCheckConfig = HealthCheckConfig.DEFAULT,
    tls: PushServerTlsConfig? = null,
    gracefulShutdownOptions: GracefulShutdownOptions?,
    authorizers: List<PushAuthorizer>,
    requestTimeoutMillis: Long = 120_000L,
    requestLog: PushServerRequestLoggingConfig? = null,
    enableAccessLog: Boolean = false,
    val meterRegistry: MeterRegistry = Flags.meterRegistry(),
    val version: Version = Version["pushsphere-server"],
) {
    private val server: Server

    init {
        require(authorizers.isNotEmpty()) { "No authorizers were specified." }

        val builder = Server.builder()

        if (hostname != null) {
            builder.defaultHostname(hostname)
        }

        builder.meterRegistry(meterRegistry)

        var hasHttps = false
        if (ports.isEmpty()) {
            builder.http(0)
        } else {
            ports.forEach { port ->
                val sessionProtocol = SessionProtocol.of(port.protocol)
                if (sessionProtocol.isHttps) {
                    hasHttps = true
                }
                builder.port(ServerPort(port.port, sessionProtocol))
            }
        }

        if (management != null) {
            require(management.port >= 0) {
                "management.port: ${management.port} (expected: >= 0)"
            }

            // curl -L https://<domain>/internal/management/jvm/threaddump
            // curl -L https://<domain>/internal/management/jvm/heapdump -o heapdump.hprof
            if (management.port == 0) {
                logger.info {
                    "'pushsphere.server.management.port' is 0, using the same ports as 'pushsphere.server.ports'."
                }
                builder.route()
                    .pathPrefix(management.path)
                    .defaultServiceName("management")
                    .build(ManagementService.of())
            } else {
                val managementProtocol = SessionProtocol.of(management.protocol)
                if (management.address == null) {
                    builder.port(ServerPort(management.port, managementProtocol))
                } else {
                    builder.port(
                        ServerPort(
                            InetSocketAddress(management.address, management.port),
                            managementProtocol,
                        ),
                    )
                }
                builder.virtualHost(management.port)
                    .route()
                    .pathPrefix(management.path)
                    .defaultServiceName("management")
                    .build(ManagementService.of())
            }
        }

        if (tls == null) {
            if (hasHttps) {
                builder.tlsSelfSigned()
                logger.warn { "Using auto-generated self-signed certificate for HTTPS; consider using a real certificate." }
            }
        } else {
            val keyPair = KeyStoreUtil.load(tls.keyStore.toFile(), tls.password, null, tls.alias)
            builder.tls(keyPair)
        }

        gracefulShutdownOptions?.let {
            builder.gracefulShutdownTimeout(
                Duration.ofMillis(gracefulShutdownOptions.quietPeriodMillis),
                Duration.ofMillis(gracefulShutdownOptions.timeoutMillis),
            )
        }

        builder
            .route()
            .get("/")
            .defaultServiceName("root")
            .build { _, _ -> HttpResponse.of("Hi! I'm Pushsphere \uD83D\uDC4B") }

        builder
            .annotatedService()
            .defaultServiceName("api")
            .decorator(
                AuthService.builder()
                    .add(authorizers.map { it.asArmeriaAuthorizer(meterRegistry) }.asIterable())
                    .onFailure { _, ctx, _, cause ->
                        if (cause != null) {
                            logger.warn(cause) { "Unexpected exception during authorization." }
                        }
                        PushResult(
                            PushStatus.AUTH_FAILURE,
                            PushResultSource.SERVER,
                            "Invalid credential",
                            httpStatus = PushStatus.AUTH_FAILURE.httpStatus(),
                        ).toHttpResponse(logger, ctx)
                    }.newDecorator(),
            )
            .apply {
                if (requestLog == null) {
                    return@apply
                }
                decorator(requestLog.toLoggingDecorator())
                decorator(ContentPreviewingService.newDecorator(requestLog.contentPreviewMaxLength))
            }
            .responseConverters(PushResultConverter(meterRegistry))
            .requestTimeoutMillis(requestTimeoutMillis)
            .build(PushService(meterRegistry))

        val healthCheckService =
            HealthCheckService.builder()
                .updatable(HealthCheckUpdatePolicyHandler(healthCheck.updatable))
                .transientServiceOptions(TransientServiceOption.WITH_ACCESS_LOGGING)
                .build()
        builder.route()
            .path(healthCheck.path)
            .defaultServiceName("health")
            .build(healthCheckService)

        val prometheusRegistry =
            findPrometheusRegistry(meterRegistry)
                ?: PrometheusMeterRegistries.defaultRegistry().prometheusRegistry
        builder
            .route()
            .path("/internal/metrics")
            .defaultServiceName("metrics")
            .build(PrometheusExpositionService.of(prometheusRegistry))

        builder
            .route()
            .pathPrefix("/internal/docs")
            .defaultServiceName("docs")
            .build(
                DocService
                    .builder()
                    .examplePaths(
                        PushService::class.java,
                        "authorize",
                        "/api/v1/:profileSetGroup/:profileSet/authorize",
                    )
                    .examplePaths(PushService::class.java, "send", "/api/v1/:profileSetGroup/:profileSet/send")
                    .exampleRequests(
                        PushService::class.java,
                        "send",
                        """
                      |{
                      |"provider": "APPLE",
                      |"deviceToken": "some-device-token",
                      |  "push": {
                      |    "title": "some-title",
                      |    "body": "some-body",
                      |    "imageUri": "https://example.com/dog.png",
                      |    "appleProps": {
                      |      "headers": {
                      |        "apnsPushType": "alert"
                      |      },
                      |      "aps": {
                      |        "badge": 15,
                      |        "sound": "bingbong.aiff"
                      |      }
                      |    }
                      |  }
                      |}
                        """.trimMargin(),
                    )
                    .exampleHeaders(
                        PushService::class.java,
                        HttpHeaders.of("Authorization", "bearer <access token>"),
                    )
                    .exampleHeaders(
                        PushService::class.java,
                        HttpHeaders.of("Authorization", "DK <access token>"),
                    )
                    .build(),
            )

        builder.decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("pushsphere")))

        builder.errorHandler(PushServerErrorHandler())

        if (enableAccessLog) {
            builder.accessLogWriter(AccessLogWriter.common(), true)
        }

        server = builder.build()
    }

    private fun findPrometheusRegistry(meterRegistry: MeterRegistry): PrometheusRegistry? {
        if (meterRegistry is PrometheusMeterRegistry) {
            return meterRegistry.prometheusRegistry
        }

        if (meterRegistry is CompositeMeterRegistry) {
            for (registry in meterRegistry.registries) {
                val result = findPrometheusRegistry(registry)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    suspend fun start() {
        server.start().await()
    }

    suspend fun close() {
        server.closeAsync().await()
    }

    fun closeOnJvmShutdown(whenClosing: () -> Unit = {}) {
        server.closeOnJvmShutdown(whenClosing)
    }

    fun onServerStopped(stopAction: () -> Unit) {
        server.addListener(
            object : ServerListenerAdapter() {
                override fun serverStopped(server: Server) {
                    stopAction()
                }
            },
        )
    }

    fun activeHttpPort(): Int = server.activeLocalPort(SessionProtocol.HTTP)

    fun activeHttpsPort(): Int = server.activeLocalPort(SessionProtocol.HTTPS)

    fun <R> use(
        waitForStop: Boolean = false,
        block: suspend (PushServer) -> R,
    ): R {
        @OptIn(DelicateCoroutinesApi::class)
        return GlobalScope.future {
            try {
                start()
                return@future block(this@PushServer)
            } finally {
                if (waitForStop) {
                    close()
                } else {
                    @OptIn(DelicateCoroutinesApi::class)
                    @Suppress("DeferredResultUnused")
                    GlobalScope.async {
                        close()
                    }
                }
            }
        }.join()
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        fun load(configFile: Path): PushServer {
            val configDir = configFile.parent
            val config = PushServerConfig.load(configFile)
            // Resolve the key store path relative to the config directory if necessary.
            val tlsConfig =
                if (config.tls == null || config.tls.keyStore.isAbsolute) {
                    config.tls
                } else {
                    config.tls.copy(keyStore = configDir.resolve(config.tls.keyStore).normalize())
                }

            return PushServer(
                config.hostname,
                config.ports,
                config.management,
                config.healthCheck,
                tlsConfig,
                config.gracefulShutdownOptions,
                buildAuthorizers(config, configDir),
                config.requestTimeoutMillis,
                config.requestLog,
                config.enableAccessLog,
            )
        }

        private fun buildAuthorizers(
            config: PushServerConfig,
            configDir: Path,
        ): List<PushAuthorizer> {
            val authorizationMap = buildAuthorizationMap(config.profileSets, configDir)
            val authorizerConfigs = config.authorizers.ifEmpty { listOf(PushServerStaticAuthorizerConfig) }
            return authorizerConfigs.map { it.buildAuthorizer(authorizationMap) }
        }

        @Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")
        private fun buildAuthorizationMap(
            config: List<PushServerProfileSetConfig>,
            configDir: Path,
        ): Map<PushAuthorization, ProfileSetContextRepository> {
            val mutableAuthorizationMap =
                mutableMapOf<PushAuthorization, MutableMap<String, MutableMap<String, ProfileSetContext>>>()
            config.forEach { c ->
                val profileSet = c.toProfileSet(configDir)
                c.allowedAccessTokens.forEach { a ->
                    mutableAuthorizationMap.getOrPut(a) { mutableMapOf() }
                        .getOrPut(profileSet.group) { mutableMapOf() }
                        .put(profileSet.name, ProfileSetContext(c, profileSet))
                }
            }

            @Suppress("NestedLambdaShadowedImplicitParameter")
            val authorizationMap = mutableAuthorizationMap.mapValues { it.value.mapValues { it.value.toMap() } }
            return authorizationMap.mapValues { entry ->
                val allowedProfileSetMap = entry.value
                ProfileSetContextRepository { profileSetGroupName, profileSetName ->
                    allowedProfileSetMap.get(profileSetGroupName)?.get(profileSetName)
                }
            }
        }
    }
}

@Serializable
data class PushServerConfig(
    val profileSets: List<PushServerProfileSetConfig>,
    val hostname: String = SystemInfo.hostname(),
    val ports: List<PushServerPort> = emptyList(),
    val management: ManagementConfig? = null,
    val healthCheck: HealthCheckConfig = HealthCheckConfig.DEFAULT,
    val tls: PushServerTlsConfig? = null,
    val authorizers: List<PushServerAuthorizerConfig> = emptyList(),
    val requestLog: PushServerRequestLoggingConfig? = null,
    val enableAccessLog: Boolean = false,
    val retryOptions: RetryOptions = RetryOptions.EMPTY,
    val gracefulShutdownOptions: GracefulShutdownOptions = GracefulShutdownOptions.EMPTY,
    val requestTimeoutMillis: Long = 120_000L,
) {
    companion object {
        private val json =
            Json {
                serializersModule =
                    SerializersModule {
                        ServiceLoader.load(PushServerAuthorizerConfigProvider::class.java).forEach {
                            @Suppress("UNCHECKED_CAST")
                            val provider = it as PushServerAuthorizerConfigProvider<PushServerAuthorizerConfig>
                            polymorphic(
                                PushServerAuthorizerConfig::class,
                                provider.configType,
                                provider.serializer,
                            )
                        }
                    }
            }

        fun load(configFile: Path): PushServerConfig {
            val config: Config =
                ConfigFactory.parseFile(configFile.toFile()).resolve().getConfig("pushsphere.server")
            // kotlinx-serialization-hocon fails to decode PushServerConfig.
            // As a workaround, convert the config to JSON and decode it.
            // TODO(ikhoon): Report this issue to kotlinx-serialization-hocon.
            val jsonText = config.root().render(ConfigRenderOptions.concise())
            val serverConfig = json.decodeFromString(serializer(), jsonText)
            return normalizeRetryOptions(serverConfig)
        }

        /**
         * Apply default retry options of profile sets or the server config if necessary.
         */
        private fun normalizeRetryOptions(config: PushServerConfig): PushServerConfig {
            val normalizedProfileSets =
                config.profileSets.map { profileSet ->
                    val normalizedProfiles =
                        profileSet.profiles.map { profile ->
                            val optionsChain =
                                listOf(
                                    profile.retryOptions,
                                    profileSet.retryOptions,
                                    config.retryOptions,
                                )

                            val maxAttempts = optionsChain.map { it.maxAttempts }.firstOrNull { it != -1 }
                            if (maxAttempts == null || maxAttempts <= 1) {
                                // Retry is disabled.
                                return@map profile
                            }

                            val optionsChainWithDefault = optionsChain + RetryOptions.DEFAULT

                            val backoff = optionsChainWithDefault.map { it.backoff }.first { it.isNotEmpty() }
                            val timeoutPerAttemptMillis =
                                optionsChainWithDefault.map { it.timeoutPerAttemptMillis }.first { it != -1L }
                            val retryPolicies =
                                optionsChainWithDefault.map { it.retryPolicies }.first { it.isNotEmpty() }

                            val effectiveRetryOptions =
                                RetryOptions(
                                    maxAttempts,
                                    backoff,
                                    timeoutPerAttemptMillis,
                                    retryPolicies,
                                )
                            when (profile) {
                                is PushServerAppleProfileConfig -> profile.copy(retryOptions = effectiveRetryOptions)
                                is PushServerFirebaseProfileConfig -> profile.copy(retryOptions = effectiveRetryOptions)
                            }
                        }
                    profileSet.copy(profiles = normalizedProfiles)
                }
            return config.copy(profileSets = normalizedProfileSets)
        }
    }
}

@Serializable
data class PushServerPort(val protocol: String, val port: Int)

@Serializable
data class ManagementConfig(
    val protocol: String = "https",
    val address: String? = null,
    val port: Int,
    val path: String = "/internal/management",
)

@Serializable
data class PushServerTlsConfig(val keyStore: Path, val alias: String? = null, val password: String? = null)

@Serializable
data class HealthCheckConfig(
    val path: String = "/internal/health",
    val updatable: HealthCheckUpdatePolicy = HealthCheckUpdatePolicy.DISALLOWED,
) {
    companion object {
        val DEFAULT = HealthCheckConfig()
    }
}

@Serializable
enum class HealthCheckUpdatePolicy {
    DISALLOWED,
    ALLOW_LOCAL,
    ALLOW_ALL,
}

@Serializable
data class GracefulShutdownOptions(val quietPeriodMillis: Long, val timeoutMillis: Long) {
    companion object {
        val EMPTY = GracefulShutdownOptions(0L, 0L)
    }
}

interface PushServerAuthorizerConfigProvider<T : PushServerAuthorizerConfig> {
    val configType: KClass<T>
    val serializer: KSerializer<T>
}

interface PushServerAuthorizerConfig {
    fun buildAuthorizer(authorizationMap: Map<PushAuthorization, ProfileSetContextRepository>): PushAuthorizer
}

class PushServerStaticAuthorizerConfigProvider :
    PushServerAuthorizerConfigProvider<PushServerStaticAuthorizerConfig> {
    override val configType: KClass<PushServerStaticAuthorizerConfig> = PushServerStaticAuthorizerConfig::class
    override val serializer: KSerializer<PushServerStaticAuthorizerConfig> =
        PushServerStaticAuthorizerConfig.serializer()
}

@Serializable
@SerialName("static")
object PushServerStaticAuthorizerConfig : PushServerAuthorizerConfig {
    override fun buildAuthorizer(authorizationMap: Map<PushAuthorization, ProfileSetContextRepository>): PushAuthorizer =
        StaticPushAuthorizer(authorizationMap)
}

@Serializable
data class PushServerProfileSetConfig(
    val group: String,
    val name: String,
    val profiles: List<PushServerProfileConfig>,
    val allowedAccessTokens: List<PushAuthorization>,
    val retryOptions: RetryOptions = RetryOptions.EMPTY,
) {
    private val profileMap: Map<PushProvider, PushServerProfileConfig> = profiles.associateBy { it.provider }

    fun toProfileSet(configDir: Path): ProfileSet = ProfileSet(group, name, profiles.map { it.toProfile(configDir) })

    fun findProfileConfig(provider: PushProvider): PushServerProfileConfig? = profileMap[provider]
}

@Serializable
sealed class PushServerProfileConfig {
    abstract val provider: PushProvider
    abstract val endpointUri: URI
    abstract val networkOptions: NetworkOptions
    abstract val retryOptions: RetryOptions
    abstract val retryRateLimitOptions: RetryRateLimitOptions
    abstract val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions
    abstract val useRequestOptionsHeader: Boolean

    abstract fun toProfile(configDir: Path): Profile
}

@Serializable
@SerialName("apple")
data class PushServerAppleProfileConfig(
    val bundleId: String,
    override val endpointUri: URI,
    val credentials: PushServerAppleCredentialsConfig,
    override val networkOptions: NetworkOptions = NetworkOptions.EMPTY,
    override val retryOptions: RetryOptions = RetryOptions.EMPTY,
    override val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
    val endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
    val circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
    override val retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
    override val useRequestOptionsHeader: Boolean = false,
) : PushServerProfileConfig() {
    @Transient
    override val provider = PushProvider.APPLE

    override fun toProfile(configDir: Path): Profile {
        val netOpts =
            if (networkOptions.maxNumEventLoops != null) {
                networkOptions
            } else {
                // Use as many event loops as possible by default because we're a server.
                networkOptions.copy(maxNumEventLoops = Int.MAX_VALUE)
            }

        return when (val credentials = credentials.toAppleCredentials(configDir)) {
            is AppleKeyPairCredentials -> {
                Profile.forApple(
                    endpointUri,
                    credentials.certChain,
                    credentials.privateKey,
                    bundleId,
                    netOpts,
                    retryOptions,
                    retryRateLimitOptions,
                    connectionOutlierDetectionOptions,
                    endpointGroupOptions,
                    circuitBreakerOptions,
                )
            }

            is AppleTokenCredentials -> {
                Profile.forApple(
                    endpointUri,
                    credentials.accessToken,
                    bundleId,
                    netOpts,
                    retryOptions,
                    retryRateLimitOptions,
                    connectionOutlierDetectionOptions,
                    endpointGroupOptions,
                    circuitBreakerOptions,
                )
            }
        }
    }
}

@Serializable
@SerialName("firebase")
data class PushServerFirebaseProfileConfig(
    override val endpointUri: URI,
    val credentials: Path,
    override val networkOptions: NetworkOptions = NetworkOptions.EMPTY,
    override val retryOptions: RetryOptions = RetryOptions.EMPTY,
    override val connectionOutlierDetectionOptions: ConnectionOutlierDetectionOptions = ConnectionOutlierDetectionOptions.EMPTY,
    val endpointGroupOptions: EndpointGroupOptions = EndpointGroupOptions.EMPTY,
    val circuitBreakerOptions: CircuitBreakerOptions = CircuitBreakerOptions.EMPTY,
    override val retryRateLimitOptions: RetryRateLimitOptions = RetryRateLimitOptions.EMPTY,
    override val useRequestOptionsHeader: Boolean = false,
) : PushServerProfileConfig() {
    @Transient
    override val provider = PushProvider.FIREBASE

    override fun toProfile(configDir: Path): Profile {
        val netOpts =
            if (networkOptions.maxNumEventLoops != null) {
                networkOptions
            } else {
                // Use as many event loops as possible by default because we're a server.
                networkOptions.copy(maxNumEventLoops = Int.MAX_VALUE)
            }
        val credentialsFile =
            if (credentials.isAbsolute) {
                credentials.toFile()
            } else {
                configDir.resolve(credentials).normalize().toFile()
            }
        val serviceAccountCredentials = GoogleServiceAccountCredentials(credentialsFile)
        return Profile.forFirebase(
            endpointUri,
            serviceAccountCredentials,
            netOpts,
            retryOptions,
            retryRateLimitOptions,
            connectionOutlierDetectionOptions,
            endpointGroupOptions,
            circuitBreakerOptions,
        )
    }
}

@Serializable
sealed class PushServerAppleCredentialsConfig {
    abstract fun toAppleCredentials(configDir: Path): AppleCredentials
}

@Serializable
@SerialName("accessToken")
data class PushServerAppleTokenCredentialsConfig(val accessToken: String) : PushServerAppleCredentialsConfig() {
    override fun toAppleCredentials(configDir: Path): AppleTokenCredentials = AppleTokenCredentials(accessToken)
}

@Serializable
@SerialName("keyPair")
data class PushServerAppleKeyPairCredentialsConfig(
    val keyStore: Path,
    val alias: String? = null,
    val password: String? = null,
) : PushServerAppleCredentialsConfig() {
    override fun toAppleCredentials(configDir: Path): AppleKeyPairCredentials {
        val keyStore =
            if (keyStore.isAbsolute) {
                keyStore
            } else {
                configDir.resolve(keyStore).normalize()
            }.toFile()
        val keyPair = KeyStoreUtil.load(keyStore, password, null, alias)
        return AppleKeyPairCredentials(keyPair.certificateChain(), keyPair.privateKey())
    }
}

internal fun PushResult.toHttpResponse(
    logger: KLogger,
    ctx: ServiceRequestContext,
    httpHeaders: ResponseHeaders? = null,
): HttpResponse {
    return toAggregatedHttpResponse(logger, ctx, httpHeaders).toHttpResponse()
}

internal fun PushResult.toAggregatedHttpResponse(
    logger: KLogger,
    ctx: ServiceRequestContext? = null,
    httpHeaders: ResponseHeaders? = null,
): AggregatedHttpResponse {
    val json = Json.encodeToString(PushResult.serializer(), this)
    val httpStatus = status.httpStatus() ?: HttpStatus.INTERNAL_SERVER_ERROR.code()

    // Remove ctx.push() when Armeria fixes to propagate the context.
    if (cause != null) {
        if (
            cause is InvalidHttpResponseException &&
            Flags.verboseExceptionSampler().isSampled(InvalidHttpResponseException::class.java)
        ) {
            val res = (cause as InvalidHttpResponseException).response()
            logger.warn {
                "response is given in an unexpected form. " +
                    "status: ${res.status()}, " +
                    "headers: ${res.headers()}, " +
                    "content: ${res.contentUtf8()}"
            }
        }

        if (ctx != null) {
            ctx.push().use {
                logger.warn(cause) { "$ctx PushResult has a cause. status: $status, source: $resultSource" }
            }
        } else {
            logger.warn(cause) { "PushResult has a cause. status: $status, source: $resultSource" }
        }
    }

    return AggregatedHttpResponse.of(
        httpHeaders ?: ResponseHeaders.builder(httpStatus)
            .contentType(MediaType.JSON_UTF_8)
            .build(),
        HttpData.ofUtf8(json),
    )
}

@Serializable
@SerialName("requestLog")
data class PushServerRequestLoggingConfig(
    val sampler: String? = null,
    val successSampler: String? = null,
    val failureSampler: String? = null,
    val logWriter: LogWriterConfig? = null,
    val contentPreviewMaxLength: Int = 200,
) {
    companion object {
        val headerAndContentSanitizer =
            LogFormatter.builderForText()
                .headersSanitizer { _, _ -> null }
                .requestContentSanitizer { _, _ -> null }
                .build()
    }

    @Serializable
    data class LogWriterConfig(
        val logger: String? = null,
        val requestLogLevel: LogLevel? = LogLevel.TRACE,
        val responseLogLevel: LogLevel? = null,
        val successfulResponseLogLevel: LogLevel? = LogLevel.TRACE,
        val failureResponseLogLevel: LogLevel? = LogLevel.WARN,
    )

    internal fun toLoggingDecorator(): Function<in HttpService, LoggingService> {
        val builder = LoggingService.builder()
        if (sampler != null) {
            builder.sampler(Sampler.of(sampler))
        }
        if (successSampler != null) {
            builder.successSampler(Sampler.of(successSampler))
        }
        if (failureSampler != null) {
            builder.failureSampler(Sampler.of(failureSampler))
        }

        val lw =
            LogWriter.builder()
                .logFormatter(headerAndContentSanitizer)
                .apply {
                    if (logWriter?.logger != null) {
                        logger(logWriter.logger)
                    }
                    if (logWriter?.requestLogLevel != null) {
                        requestLogLevel(logWriter.requestLogLevel)
                    }
                    if (logWriter?.responseLogLevel != null) {
                        responseLogLevel(HttpStatusClass.SUCCESS, logWriter.responseLogLevel)
                    }
                    if (logWriter?.successfulResponseLogLevel != null) {
                        successfulResponseLogLevel(logWriter.successfulResponseLogLevel)
                    }
                    if (logWriter?.failureResponseLogLevel != null) {
                        failureResponseLogLevel(logWriter.failureResponseLogLevel)
                    }
                }.build()
        builder.logWriter(lw)
        return builder.newDecorator()
    }
}

data class ProfileSetContext(
    val config: PushServerProfileSetConfig,
    val profileSet: ProfileSet,
)

private class PushResultConverter(private val meterRegistry: MeterRegistry) : ResponseConverterFunction {
    override fun convertResponse(
        ctx: ServiceRequestContext,
        headers: ResponseHeaders,
        result: Any?,
        trailers: HttpHeaders,
    ): HttpResponse {
        if (result is PushResult) {
            PushResultMetricCollector.count(
                result,
                meterRegistry,
                "pushsphere.server.push.result",
                ctx.pathParam("profileSetGroup"),
                ctx.pathParam("profileSet"),
                ctx.attr(PushService.PUSH_PROVIDER),
            )
            return result.toHttpResponse(logger, ctx, headers)
        }

        return ResponseConverterFunction.fallthrough()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
