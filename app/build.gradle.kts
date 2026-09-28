plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nivra.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nivra.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        buildConfigField("String", "GITHUB_OWNER", "\"plnoble\"")
        buildConfigField("String", "GITHUB_REPO", "\"Nivra\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val keystorePath = System.getenv("NIVRA_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = System.getenv("NIVRA_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("NIVRA_KEY_ALIAS")
                    keyPassword = System.getenv("NIVRA_KEY_PASSWORD")
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
