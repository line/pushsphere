plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    api(project(":common"))
    implementation(libs.junit.jupiter.api)
    implementation(libs.armeria)
    implementation(libs.kotlinx.serialization.hocon)
}
