plugins {
    application
    alias(libs.plugins.jib)
}

val mockMainClass = "com.linecorp.pushsphere.dist.mock.MockMainKt"

// Force to use Java 17 for running the application.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    applicationName = "pushsphere-mock"
    mainClass.set(mockMainClass)
}

dependencies {
    implementation(libs.clikt.jvm)
    implementation(project(":mock"))
    implementation(libs.logback.classic)
}

val generateSources: Copy =
    task<Copy>("generateSources") {
        from(project(":dist").projectDir.resolve("src"))
        includeEmptyDirs = false
        include("**/LogbackConfigurer.kt")
        into("gen-src")
    }

tasks.compileKotlin.get().dependsOn(generateSources)
tasks.processResources.get().dependsOn(generateSources)
tasks.processTestResources.get().dependsOn(generateSources)

jib {
    from {
        image = "eclipse-temurin:17-jdk"
        platforms {
            if (System.getProperty("os.arch") == "aarch64") {
                // Build aarch64 image if the host is aarch64.
                platform {
                    architecture = "arm64"
                    os = "linux"
                }
            } else {
                // Build amd64 image otherwise.
                platform {
                    architecture = "amd64"
                    os = "linux"
                }
            }
        }
    }

    to {
        image = "ghcr.io/line/pushsphere-mock"
        tags = setOf("latest", "${rootProject.version}")
        auth {
            username = System.getenv("GHCR_USERNAME")
            password = System.getenv("GHCR_PASSWORD")
        }
    }

    container {
        user = "0:0" // root
        appRoot = "/app"
        workingDirectory = appRoot
        volumes = listOf("/conf")
        args = listOf("--config-dir", "/conf")
        mainClass = mockMainClass
    }
}

// workaround: retryable jib task
tasks.jib {
    val actions = this.actions.toList()
    val retryAction: Action<in Task> =
        Action<Task> {
            val exceptions = mutableListOf<Exception>()
            for (i in 1..3) {
                try {
                    actions.forEach { it.execute(this) }
                    break
                } catch (e: Exception) {
                    if (i == 3) {
                        exceptions.forEach { e.addSuppressed(it) }
                        throw e
                    } else {
                        println("jib task attempt $i failed, retrying...")
                        exceptions.add(e)
                    }
                }
            }
        }
    this.actions = mutableListOf(retryAction)
}
