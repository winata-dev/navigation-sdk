plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.remaply.navigation.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }

    buildFeatures {
        compose = true
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
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("com.remaply.navigation.core.InternalNavigationApi")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":navigation-core"))
    // Exposed only through the optional `mapContent` slot, for drawing your own layers.
    api(libs.maplibre.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.compose)
    implementation(libs.ferrostar.ui.maplibre)
    // MapAuthentication: adds headers to MapLibre's own requests.
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "navigation-ui"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Remaply Navigation UI")
                description.set("Ready-to-use Jetpack Compose navigation screen, built on Ferrostar and MapLibre.")
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
