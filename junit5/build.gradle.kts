plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    api(libs.junit.jupiter.api)
    api(project(":server"))
    api(project(":client"))
    api(project(":mock"))
    implementation(project(":testing-internal"))
}
