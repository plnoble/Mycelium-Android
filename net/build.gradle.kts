plugins {
    kotlin("jvm")
    // 测试假服务（LocalHttp）搬进 testFixtures 源码集：它只随 net-test-fixtures 变体发布，
    // 生产 jar 里不再有它，但它仍能被本模块的测试、:roundtable 的测试和 :app 的 androidTest
    // 通过 testFixtures(project(":net")) 共用。
    id("java-test-fixtures")
}

// Same version resolution as :contract. The Android plugin does not publish a version Gradle can
// check, and the plugin portal handshake fails on this machine.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
    repositories {
        mavenCentral()
    }
}

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

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
}

tasks.test {
    useJUnitPlatform()
}
