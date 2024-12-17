package com.linecorp.pushsphere.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.linecorp.armeria.internal.shaded.guava.collect.ImmutableMap
import com.linecorp.armeria.internal.shaded.guava.collect.ImmutableSortedMap
import com.linecorp.armeria.internal.shaded.guava.collect.MapMaker
import com.linecorp.armeria.internal.shaded.guava.io.Closeables
import org.slf4j.LoggerFactory
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Objects
import java.util.Properties

// This file is forked from armeria,
// https://github.com/line/armeria/blob/1.16/core/src/main/java/com/linecorp/armeria/common/util/Version.java

/**
 * Retrieves the version information of available artifacts.
 *
 * This class retrieves the version information from
 * `META-INF/${project.group}.properties`, which is generated in build time. Note that
 */
class Version private constructor(
    private val artifactId: String,
    private val artifactVersion: String,
    private val commitTimeMillis: Long,
    private val shortCommitHash: String,
    private val longCommitHash: String,
    private val repositoryStatus: String,
) {
    /**
     * Returns the Maven artifact ID of the component, such as `"server"`.
     */
    @JsonProperty
    fun artifactId(): String {
        return artifactId
    }

    /**
     * Returns the Maven artifact version of the component, such as `"1.0.0"`.
     */
    @JsonProperty
    fun artifactVersion(): String {
        return artifactVersion
    }

    /**
     * Returns when the release commit was created.
     */
    @JsonProperty
    fun commitTimeMillis(): Long {
        return commitTimeMillis
    }

    /**
     * Returns the short hash of the release commit.
     */
    @JsonProperty
    fun shortCommitHash(): String {
        return shortCommitHash
    }

    /**
     * Returns the long hash of the release commit.
     */
    @JsonProperty
    fun longCommitHash(): String {
        return longCommitHash
    }

    /**
     * Returns the status of the repository when performing the release process.
     *
     * @return `"clean"` if the repository was clean. `"dirty"` otherwise.
     */
    @JsonProperty
    fun repositoryStatus(): String {
        return repositoryStatus
    }

    @get:JsonIgnore
    val isRepositoryClean: Boolean
        /**
         * Returns whether the repository was clean when performing the release process.
         * This method is a shortcut for `"clean".equals(repositoryStatus())`.
         */
        get() = "clean" == repositoryStatus

    override fun toString(): String {
        return artifactId + '-' + artifactVersion + '.' + shortCommitHash +
            if (isRepositoryClean) "" else "(repository: $repositoryStatus)"
    }

    companion object {
        // Forked from Netty 4.1.34 at d0912f27091e4548466df81f545c017a25c9d256
        private val logger = LoggerFactory.getLogger(Version::class.java)
        private const val PROP_RESOURCE_PATH = "META-INF/com.linecorp.pushsphere.versions.properties"
        private const val PROP_VERSION = ".version"
        private const val PROP_COMMIT_DATE = ".commitDate"
        private const val PROP_SHORT_COMMIT_HASH = ".shortCommitHash"
        private const val PROP_LONG_COMMIT_HASH = ".longCommitHash"
        private const val PROP_REPO_STATUS = ".repoStatus"
        private val VERSIONS: MutableMap<ClassLoader, Map<String, Version>> =
            MapMaker().weakKeys().makeMap()

        /**
         * Returns the version information for the artifact named `artifactId`. If information for
         * the artifact can't be found, a default value is returned with arbitrary `unknown` values.
         */
        @JvmOverloads
        operator fun get(
            artifactId: String,
            classLoader: ClassLoader = Version::class.java.getClassLoader(),
        ): Version {
            Objects.requireNonNull(artifactId, "artifactId")
            val version = getAll(classLoader)[artifactId]
            return version
                ?: Version(
                    artifactId,
                    "unknown",
                    0,
                    "unknown",
                    "unknown",
                    "unknown",
                )
        }

        val all: Map<String, Version>
            /**
             * Retrieves the version information of artifacts.
             * This method is a shortcut for [getAll(Version.class.getClassLoader())][.getAll].
             *
             * @return A [Map] whose keys are Maven artifact IDs and whose values are [Version]s
             */
            get() = getAll(Version::class.java.getClassLoader())

        /**
         * Retrieves the version information of artifacts using the specified [ClassLoader].
         *
         * @return A [Map] whose keys are Maven artifact IDs and whose values are [Version]s
         */
        fun getAll(classLoader: ClassLoader): Map<String, Version> {
            Objects.requireNonNull(classLoader, "classLoader")
            return VERSIONS.computeIfAbsent(
                classLoader,
            ) { cl: ClassLoader ->
                var foundProperties = false

                // Collect all properties.
                val props = Properties()
                try {
                    val resources =
                        cl.getResources(PROP_RESOURCE_PATH)
                    while (resources.hasMoreElements()) {
                        foundProperties = true
                        val url = resources.nextElement()
                        val input = url.openStream()
                        try {
                            props.load(input)
                        } finally {
                            Closeables.closeQuietly(input)
                        }
                    }
                } catch (ignore: Exception) {
                    // Not critical. Just ignore.
                }
                if (!foundProperties) {
                    logger.info(
                        "Could not find any property files at " +
                            "META-INF/com.linecorp.pushsphere.versions.properties. " +
                            "This usually indicates an issue with your application packaging, for example using " +
                            "a fat JAR method that only keeps one copy of any file. For maximum functionality, " +
                            "it is recommended to fix your packaging to include these files.",
                    )
                    return@computeIfAbsent ImmutableMap.of()
                }

                // Collect all artifactIds.
                val artifactIds: MutableSet<String> = HashSet()
                for (o in props.keys) {
                    val k = o as String
                    val dotIndex = k.indexOf('.')
                    if (dotIndex <= 0) {
                        continue
                    }
                    val artifactId = k.substring(0, dotIndex)

                    // Skip the entries without required information.
                    if (!props.containsKey(artifactId + PROP_VERSION) ||
                        !props.containsKey(artifactId + PROP_COMMIT_DATE) ||
                        !props.containsKey(artifactId + PROP_SHORT_COMMIT_HASH) ||
                        !props.containsKey(artifactId + PROP_LONG_COMMIT_HASH) ||
                        !props.containsKey(artifactId + PROP_REPO_STATUS)
                    ) {
                        continue
                    }
                    artifactIds.add(artifactId)
                }
                val versions: ImmutableSortedMap.Builder<String, Version> =
                    ImmutableSortedMap.naturalOrder()
                for (artifactId in artifactIds) {
                    versions.put(
                        artifactId,
                        Version(
                            artifactId,
                            props.getProperty(artifactId + PROP_VERSION),
                            parseIso8601(props.getProperty(artifactId + PROP_COMMIT_DATE)),
                            props.getProperty(artifactId + PROP_SHORT_COMMIT_HASH),
                            props.getProperty(artifactId + PROP_LONG_COMMIT_HASH),
                            props.getProperty(artifactId + PROP_REPO_STATUS),
                        ),
                    )
                }
                versions.build()
            }
        }

        private fun parseIso8601(value: String): Long {
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").parse(value).time
            } catch (ignored: ParseException) {
                0
            }
        }
    }
}
