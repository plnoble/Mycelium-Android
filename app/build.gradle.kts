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
        // v0.1.0 = 改名为知衍的首个正式版。v0.1.1 只升版本号，用来验证应用内更新。
        // 0.2.0 = 圆桌空间骨架（P1-6）。正式发布由用户决定，这里只改版本号。
        versionCode = 3
        versionName = "0.2.0"

        buildConfigField("String", "GITHUB_OWNER", "\"plnoble\"")
        buildConfigField("String", "GITHUB_REPO", "\"Mycelium-Android\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // 真机走查只装这个包，不覆盖用户手机上的正式版。应用名在 src/debug 覆盖为「知衍（调试）」。
            applicationIdSuffix = ".debug"
        }
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

val generatedAssets = layout.buildDirectory.dir("generated/contractAssets")
val generatedTestAssets = layout.buildDirectory.dir("generated/contractTestAssets")

val contractAssets = tasks.register<Copy>("copyContractAssets") {
    from(rootProject.file("contract/persona-pack/manifest.schema.json"))
    into(generatedAssets.map { it.dir("contract/persona-pack") })
}

val contractTestAssets = tasks.register<Copy>("copyContractTestAssets") {
    into(generatedTestAssets)
    into("samples/synthetic-persona-v1") {
        from(rootProject.file("contract/samples/synthetic-persona-v1/persona.sqlite"))
    }
    into("retrieval") {
        from(rootProject.file("contract/retrieval/vectors.json"))
    }
}

tasks.named("preBuild") { dependsOn(contractAssets) }
afterEvaluate {
    tasks.named("preDebugAndroidTestBuild") { dependsOn(contractTestAssets) }
}
android.sourceSets.getByName("main").assets.directories.add("build/generated/contractAssets")
android.sourceSets.getByName("androidTest").assets.directories.add("build/generated/contractTestAssets")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(project(":contract"))
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
    // P0-7 选定 SQLCipher：系统 SQLite 没有 FTS5，角色包以空口令只读打开。
    // sqlite 是它的编译期父类，运行时依赖没有放进编译类路径。
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite:2.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // Local JVM tests need a real JSON parser instead of android.jar's method stubs.
    testImplementation("org.json:json:20240303")
    testImplementation(project(":contract"))
    testImplementation("org.xerial:sqlite-jdbc:3.53.0.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.5") {
        exclude(group = "androidx.test", module = "monitor")
    }
    androidTestImplementation("androidx.test:runner:1.5.0") {
        exclude(group = "androidx.test", module = "monitor")
    }
    androidTestImplementation("androidx.test:monitor:1.8.0")
    androidTestImplementation(project(":contract"))
}
