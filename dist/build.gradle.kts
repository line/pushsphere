plugins {
    application
    alias(libs.plugins.jib)
}

// Force to use Java 17 for running the application.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    applicationName = "pushsphere"
    mainClass.set("com.linecorp.pushsphere.dist.MainKt")
}

dependencies {
    api(project(":server"))
    api(libs.clikt.jvm)
    implementation(libs.armeria.prometheus1)
    implementation(libs.logback.classic)
    implementation(libs.logback.core)
    implementation(libs.micrometer.prometheus)
}

jib {
    from {
        image = "eclipse-temurin:17-jdk"
        platforms {
            platform {
                architecture = "arm64"
                os = "linux"
            }
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }

    to {
        image = "ghcr.io/line/pushsphere"
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
