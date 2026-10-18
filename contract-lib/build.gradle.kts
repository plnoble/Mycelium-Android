plugins {
    kotlin("jvm")
}

// The Android plugin brings org.jetbrains.kotlin.jvm onto the build classpath without a version
// Gradle can check. Resolve the same 2.4.20 the app compiles with from Maven Central, which is
// already cached; the plugin portal handshake fails on this machine.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
    repositories {
        mavenCentral()
    }
}

// The machine JDK is Android Studio's JBR 25; there is no separate JDK 17 to provision.
// Bytecode still targets 17, the same level :app compiles to.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // P2-1b：警告即缺陷，编译一有警告就失败。
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.53.0.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
}

tasks.test {
    useJUnitPlatform()
    // JDK 24 起，JNI 加载本地库会报「restricted method」警告；sqlite-jdbc 在测试里就会触发。
    // 只在会出现该警告的 JVM 上加这个参数：旧 JDK 不认识它，也没有这条警告。
    val testJvm = javaLauncher.orNull?.metadata?.languageVersion?.asInt()
        ?: JavaVersion.current().majorVersion.toInt()
    if (testJvm >= 24) jvmArgs("--enable-native-access=ALL-UNNAMED")
}
