plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xavierxia.mycelium"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xavierxia.mycelium"
        minSdk = 29
        targetSdk = 37
        // 版本号规则（P0-9 补充任务书一）：versionName 与标签（去掉 v）一致；每次发布 versionCode +1。
        // v0.1.0 = 改名为知衍的首个正式版。
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GITHUB_OWNER", "\"plnoble\"")
        buildConfigField("String", "GITHUB_REPO", "\"Mycelium-Android\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val keystorePath = System.getenv("MYCELIUM_ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = System.getenv("MYCELIUM_ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("MYCELIUM_ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("MYCELIUM_ANDROID_KEY_PASSWORD")
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
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // Local JVM tests need a real JSON parser instead of android.jar's method stubs.
    testImplementation("org.json:json:20240303")
}
