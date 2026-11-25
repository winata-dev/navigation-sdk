import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional settings in android/local.properties (not committed):
//   sample.remaplyApiKey=rmp_pk_...       use the Remaply API for routes and the map; overrides the two below
//   sample.styleUrl=https://...           a MapLibre style; defaults to OpenFreeMap (free, no key)
//   sample.valhallaUrl=https://.../route  any Valhalla server; defaults to the public FOSSGIS demo server
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun sampleSetting(name: String, default: String): String =
    (localProperties.getProperty("sample.$name") ?: default).replace("\"", "\\\"")

android {
    namespace = "com.remaply.navigation.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.remaply.navigation.sample"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()

        buildConfigField("String", "STYLE_URL", "\"${sampleSetting("styleUrl", "https://tiles.openfreemap.org/styles/liberty")}\"")
        buildConfigField("String", "VALHALLA_URL", "\"${sampleSetting("valhallaUrl", "https://valhalla1.openstreetmap.de/route")}\"")
        buildConfigField("String", "REMAPLY_API_KEY", "\"${sampleSetting("remaplyApiKey", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            // Exercises the SDK's consumer R8 rules. Signed with the debug key: for testing only.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // Ferrostar uses java.time, which needs desugaring below API 26. Apps must enable it too.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Apps use the JitPack coordinates instead; see docs/android.md.
    implementation(project(":navigation-ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
