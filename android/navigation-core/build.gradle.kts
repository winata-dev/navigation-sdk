plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.remaply.navigation.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // Ferrostar uses java.time, which needs desugaring below API 26. Apps must enable it too.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // Every public declaration must say it is public: nothing becomes API by accident.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("com.remaply.navigation.core.InternalNavigationApi")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // StateFlow and Flow are part of the public API.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // The navigation engine. Not part of the public API: see docs/architecture.md.
    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.google.play.services)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "navigation-core"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Remaply Navigation Core")
                description.set("Turn-by-turn navigation engine for Android, built on Ferrostar.")
                url.set("https://github.com/winata-dev/navigation-sdk")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
