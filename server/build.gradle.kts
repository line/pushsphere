plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    api(project(":common"))
    api(project(":client"))
    implementation(libs.armeria)
    implementation(libs.armeria.kotlin)
    implementation(libs.armeria.logback)
    implementation(libs.armeria.prometheus1)
    implementation(libs.micrometer.prometheus)
    implementation(libs.kotlinx.serialization.hocon)
}
