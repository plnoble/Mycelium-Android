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

// P2-1d 运行时指令集挑选（2026-10-23 夜）：主库 `libmycelium_llm.so` 是**基线**变体（armv8-a，
// 任何 arm64 设备都能跑），这里再编一份 `libmycelium_llm_fast.so`（armv8.2-a+dotprod+i8mm+fp16，
// 真机实测提示读取快 3 倍）。两份都进 APK，运行时由 `NativeLibraryVariant` 按 CPU 特性挑一份加载。
//
// 为什么用第二个 CMake 构建而不是 ggml 自己的变体机制：见 CMakeLists.txt 顶部的说明
// （arm64 上 GGML_CPU_ALL_VARIANTS 只出一个变体）。
val fastVariantAbi = "arm64-v8a"
val fastVariantName = "mycelium_llm_fast"
// 输出到模块的默认 jniLibs 目录：AGP 这个版本对库模块的 `jniLibs.srcDir(...)` 会抛
// `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`，
// 而 `src/main/jniLibs` 是 AGP 默认就会扫描的目录，不必再注册。这份 .so 是**生成物**，
// 已在 .gitignore 里，不进版本库。
val fastVariantOutput = providers.provider { File(projectDir, "src/main/jniLibs/$fastVariantAbi") }

/** 两个变体唯一的差别就在这里；改它必须同时改这里的对照测试。 */
val fastVariantMarch = "armv8.2-a+dotprod+i8mm+fp16"
val baselineVariantMarch = "armv8-a"

/**
 * 定位 SDK / NDK。
 *
 * 不依赖 AGP 的 `android.sdkDirectory` 之类的属性（这个 AGP 版本上不存在），改成和别的构建脚本一样
 * 从 `local.properties` 的 `sdk.dir` 或环境变量取——两处都没有就直接失败，别静默编出一份错的基线库。
 */
fun androidSdkDirectory(): File {
    val localProperties = rootProject.file("local.properties")
    val configured = if (localProperties.exists()) {
        localProperties.readLines()
            .firstOrNull { it.trim().startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.trim()
    } else null
    val path = configured
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("找不到 Android SDK：local.properties 里没有 sdk.dir，环境也没有 ANDROID_HOME")
    return File(path)
}

val nativeNdkVersion = "30.0.16248370"

val cmakeExecutable = providers.provider {
    val fromSdk = File(androidSdkDirectory(), "cmake/3.22.1/bin/cmake.exe")
    if (fromSdk.exists()) fromSdk.absolutePath else "cmake"
}

val configureFastNative by tasks.registering(Exec::class) {
    val ndk = File(androidSdkDirectory(), "ndk/$nativeNdkVersion")
    val source = file("src/main/cpp")
    val buildDir = layout.buildDirectory.dir("cmake-fast").get().asFile
    inputs.dir(source)
    inputs.property("march", fastVariantMarch)
    commandLine(
        cmakeExecutable.get(),
        "-S", source.absolutePath,
        "-B", buildDir.absolutePath,
        // 显式指定 Ninja：Windows 上 CMake 默认挑 MSBuild，配 Android 工具链会直接失败。
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=" + cmakeExecutable.get().substringBeforeLast('\\') + "\\ninja.exe",
        "-DCMAKE_TOOLCHAIN_FILE=${File(ndk, "build/cmake/android.toolchain.cmake").absolutePath}",
        "-DANDROID_NDK=${ndk.absolutePath}",
        "-DANDROID_ABI=$fastVariantAbi",
        "-DANDROID_PLATFORM=android-29",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        // **静态**把 ggml/llama 链进这一个 .so：否则第二份构建也会产出
        // libggml-cpu.so / libggml.so / libllama.so，和基线那一份同名，打进 APK 就互相覆盖。
        "-DBUILD_SHARED_LIBS=OFF",
        "-DLLAMA_BUILD_COMMON=OFF",
        "-DMYCELIUM_LIB_NAME=$fastVariantName",
        "-DMYCELIUM_ARM_ARCH=$fastVariantMarch",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${fastVariantOutput.get().absolutePath}",
    )
}

val buildFastNative by tasks.registering(Exec::class) {
    dependsOn(configureFastNative)
    val buildDir = layout.buildDirectory.dir("cmake-fast").get().asFile
    val outDir = fastVariantOutput.get()
    inputs.dir(file("src/main/cpp"))
    outputs.file(File(outDir, "lib$fastVariantName.so"))
    commandLine(
        cmakeExecutable.get(),
        "--build", buildDir.absolutePath,
        "--target", fastVariantName,
        "--config", "Release",
        "-j", "4",
    )
}

// 快变体写进 src/main/jniLibs 之后，还必须保证「合并原生库」在它编完之后才跑。
tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }
    .configureEach { dependsOn(buildFastNative) }

// 基线变体用的 -march 也在这里声明一次，让「两份变体确实不同」有单一出处可查。
check(baselineVariantMarch.isNotBlank() && baselineVariantMarch != fastVariantMarch) {
    "两个指令集变体必须真的不同：baseline=$baselineVariantMarch fast=$fastVariantMarch"
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


