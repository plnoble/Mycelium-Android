plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xavierxia.mycelium"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.xavierxia.mycelium"
        minSdk = 29
        targetSdk = 37
        // 版本号规则（P0-9 补充任务书一）：versionName 与标签（去掉 v）一致；每次发布 versionCode +1。
        // v0.1.0 = 改名为知衍的首个正式版。v0.1.1 只升版本号，用来验证应用内更新。
        // 0.2.0 = 圆桌空间骨架（P1-6）。0.3.0 = 圆桌单聊（P1-7）。正式发布由用户决定，这里只改版本号。
        // 0.5.1 = 只修大模型闪退：超过 4 GiB 的模型不做 CPU 权重重排（2026-10-30，统筹热修）。
        versionCode = 7
        versionName = "0.5.1"

        buildConfigField("String", "GITHUB_OWNER", "\"plnoble\"")
        buildConfigField("String", "GITHUB_REPO", "\"Mycelium-Android\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 明文 HTTP 只给调试包的走查用（adb reverse 的本机假服务）；正式包显式关闭。
        // 用清单占位符而不是 debug 清单覆盖：覆盖要靠 tools:replace，而合并单测清单时
        // 那个标记找不到可替换的声明，清单合并器会报警告（P2-1b）。
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // P2-1b：警告即错误，防止警告重新长出来。
        warningsAsErrors = true
        // 依赖升级要单独评估：本机连不上 Maven Central，所有构建都走 --offline，
        // 现有版本是配合 P0-7 选定的（SQLCipher 支持 FTS5）。这项由统筹在联网评估时再开。
        disable += "GradleDependency"
        // 「有更新版本可用」类检查要联网查最新版本，结果随时间变：本机离线查不到所以一直绿，
        // CI 联网后报「Gradle 9.8.2 可用」被当成错误（2026-10-21 run 39568585440）。版本是刻意固定的，
        // 升级要单独评估，所以这两项也关掉，让门禁结果只取决于代码本身（统筹补改）。
        disable += "AndroidGradlePluginVersion"
        disable += "NewerVersionAvailable"
        // P2-2b 修复：本产品只发 arm64，ChromeOS 的 x86_64 要求不适用。
        disable += "ChromeOsAbiSupport"
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
            // JDK 24 起，JNI 加载本地库会报「restricted method」警告；测试用到的 sqlite-jdbc 就属于这种。
            // 只在会出现该警告的 JVM 上加这个参数：旧 JDK 不认识它，也不会有这条警告。
            val testJvm = it.javaLauncher.orNull?.metadata?.languageVersion?.asInt()
                ?: JavaVersion.current().majorVersion.toInt()
            if (testJvm >= 24) it.jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

    buildTypes {
        debug {
            // 真机走查只装这个包，不覆盖用户手机上的正式版。应用名在 src/debug 覆盖为「知衍（调试）」。
            applicationIdSuffix = ".debug"
            // The walkthrough points at a fake model on the phone's own loopback. Release builds refuse it.
            buildConfigField("Boolean", "ALLOW_CLEARTEXT_LOCALHOST", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            buildConfigField("Boolean", "ALLOW_CLEARTEXT_LOCALHOST", "false")
            // P2-2b 步骤 7：正式包只带 arm64-v8a 的原生库。
            // 本地模型引擎（:llm）本来就只编 arm64-v8a；SQLCipher 的 armeabi-v7a / x86 / x86_64
            // 库在正式包里是纯浪费（约 13.58 MiB），正式包只给真机装。
            // 只加在 release：debug 与 androidTest 包保持全 ABI，模拟器还要用。
            ndk {
                abiFilters += "arm64-v8a"
            }
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

// P2-1b：警告即缺陷。Kotlin 编译一有警告就让构建失败，和 lint 的 warningsAsErrors 一致。
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val generatedAssets = layout.buildDirectory.dir("generated/contractAssets")
val generatedTestAssets = layout.buildDirectory.dir("generated/contractTestAssets")

val contractAssets = tasks.register<Copy>("copyContractAssets") {
    into(generatedAssets)
    into("contract/persona-pack") {
        from(rootProject.file("contract/persona-pack/manifest.schema.json"))
    }
    into("contract/answer") {
        from(rootProject.file("contract/answer/system-prompt.zh.txt"))
        from(rootProject.file("contract/answer/mode-rules.zh.json"))
    }
}

val contractTestAssets = tasks.register<Copy>("copyContractTestAssets") {
    into(generatedTestAssets)
    into("samples/synthetic-persona-v1") {
        from(rootProject.file("contract/samples/synthetic-persona-v1/persona.sqlite"))
    }
    into("retrieval") {
        from(rootProject.file("contract/retrieval/vectors.json"))
    }
    into("answer") {
        from(rootProject.file("contract/answer/system-prompt.zh.txt"))
        from(rootProject.file("contract/answer/mode-rules.zh.json"))
        from(rootProject.file("contract/answer/vectors"))
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
    implementation(project(":net"))
    implementation(project(":roundtable"))
    implementation(project(":llm"))
    implementation(project(":hearth"))
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
    // P2-2 真机复验：instrumented 测试要驱动界面，和本地单测用同一个制品、同一份 BOM 版本。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-junit4 传递依赖 espresso-idling-resource，但本机只缓存了 3.7.0（3.5.0 没有），
    // 离线构建必须在 androidTest 上显式用缓存里那个版本，否则解析不到。
    androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.7.0")
    androidTestImplementation(project(":contract"))
    androidTestImplementation(project(":hearth"))
    // 假服务 LocalHttp 从 :net 的生产源码集搬到了它的 testFixtures，测试专用、不进生产 jar。
    androidTestImplementation(testFixtures(project(":net")))
}
