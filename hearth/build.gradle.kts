plugins { kotlin("jvm"); `java-library` }

// Same cached 2.4.20 the other pure-Kotlin modules compile with. No plugin-portal handshake.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
    repositories { mavenCentral() }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
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

tasks.test { useJUnitPlatform() }