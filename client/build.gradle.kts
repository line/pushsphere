plugins {
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    api(project(":common"))
    api(libs.armeria)
    implementation(libs.armeria.kotlin)
    implementation(libs.armeria.oauth2)
    testImplementation(libs.armeria.junit5)
}
