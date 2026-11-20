plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// JitPack sets GROUP and VERSION from the repository and the git tag
// (com.github.<owner>.<repo> and e.g. 1.2.0). Local builds use the values below.
val sdkGroup: String = System.getenv("GROUP") ?: "com.remaply.navigation"
val sdkVersion: String = System.getenv("VERSION") ?: providers.gradleProperty("sdkVersion").get()

subprojects {
    group = sdkGroup
    version = sdkVersion
}
