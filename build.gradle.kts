import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.osdetector)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktlint)
}

apply(from = "$rootDir/gradle/scripts/build-flags.gradle")

allprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.languageVersion.set(KotlinVersion.KOTLIN_1_6)
        compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_1_6)
    }
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes["Implementation-Version"] = project.version
            attributes["Implementation-Title"] = project.name
        }
    }
}

subprojects {
    val proj = this

    plugins.withType<JavaPlugin> {
        dependencies {
            // TODO(ikhoon) Use bundle when dependencies.toml supports it.
            implementation(rootProject.libs.kotlinx.coroutines)
            implementation(rootProject.libs.kotlinx.coroutines.jdk8)
            // Use the same version of Kotlin as the one used by the Kotlin plugin.
            // If unspecified, it could be overridden by third-party dependencies such as Armeria-Kotlin module.
            implementation(rootProject.libs.kotlin.stdlib)
            implementation(rootProject.libs.kotlin.stdlib.common)
            implementation(rootProject.libs.kotlin.reflect)

            implementation(rootProject.libs.kotlin.logging)
            implementation(rootProject.libs.kotlinx.serialization.json)
            implementation(rootProject.libs.micrometer.core)

            if (proj != project(":testing-internal")) {
                testImplementation(project(":testing-internal"))
                proj.tasks.processTestResources.configure {
                    // Use testing-internal/src/main/resources as the default test resources.
                    from("${rootProject.projectDir}/testing-internal/src/main/resources")
                }
            }
            if (proj != project(":junit5")) {
                testImplementation(project(":junit5"))
            }

            testImplementation(rootProject.libs.junit.jupiter)
            testImplementation(rootProject.libs.junit.platfrom.runner)
            testImplementation(rootProject.libs.logback.classic)
            testImplementation(rootProject.libs.kotest.assertions.core)
            testImplementation(rootProject.libs.kotest.assertions.json)
            testImplementation(rootProject.libs.kotest.common)
            testImplementation(rootProject.libs.kotlinx.coroutines.test)
            testImplementation(rootProject.libs.micrometer.prometheus)
            testImplementation(rootProject.libs.mockk)
        }
    }
}
