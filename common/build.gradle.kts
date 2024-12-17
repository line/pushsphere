plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(libs.jwt)
    api(libs.armeria)
}
