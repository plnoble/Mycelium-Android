plugins { kotlin("jvm"); `java-library` }

// Same cached 2.4.20 the other pure-Kotlin modules compile with. No plugin-portal handshake.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
    repositories { mavenCentral() }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // P2-1b：警告即缺陷，编译一有警告就失败（本模块是并入 P2-1b 之后补上的）。
        allWarningsAsErrors.set(true)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The public API carries ChatMessage/ChatRole from :model-api.
    api(project(":model-api"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.xerial:sqlite-jdbc:3.53.0.0")
}

tasks.test {
    useJUnitPlatform()
    // JDK 24 起，JNI 加载本地库会报「restricted method」警告；sqlite-jdbc 在测试里就会触发。
    // 只在会出现该警告的 JVM 上加这个参数：旧 JDK 不认识它，也没有这条警告。
    val testJvm = javaLauncher.orNull?.metadata?.languageVersion?.asInt()
        ?: JavaVersion.current().majorVersion.toInt()
    if (testJvm >= 24) jvmArgs("--enable-native-access=ALL-UNNAMED")
}