plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.armeria)
    implementation(libs.kotlinx.serialization.hocon)
}
