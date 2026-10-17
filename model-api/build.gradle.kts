plugins { kotlin("jvm"); `java-library` }
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test { useJUnitPlatform() }


