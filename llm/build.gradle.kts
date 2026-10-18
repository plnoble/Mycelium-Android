plugins { id("com.android.library") }
android {
    namespace = "com.xavierxia.mycelium.llm"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                targets += "mycelium_llm"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api(project(":model-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// P2-1b：警告即缺陷。Kotlin 编译一有警告就让构建失败，和 lint 的 warningsAsErrors 一致。
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
val verifyOfflineDependencies by tasks.registering {
    // 在配置阶段取好工程和配置对象：执行阶段再碰 Task.project 会被 Gradle 10 判为错误
    // （P2-1b：清掉那条 Gradle 弃用警告）。
    val targets = listOf(
        project to "debugRuntimeClasspath",
        project(":model-api") to "runtimeClasspath",
    ).map { (module, configurationName) -> module.configurations.getByName(configurationName) }
    doLast {
        val allowed = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains", "androidx.annotation")
        targets.forEach { configuration ->
            configuration.incoming.resolutionResult.allComponents.forEach {
                val id = it.id
                if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                    check(id.group in allowed) { "Offline module resolved forbidden dependency $id" }
                }
                if (id is org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                    check(id.projectPath in setOf(":llm", ":model-api")) { "Forbidden project dependency $id" }
                }
            }
        }
    }
}
tasks.matching { it.name == "testDebugUnitTest" || it.name == "check" }.configureEach {
    dependsOn(verifyOfflineDependencies)
}


