plugins {
    kotlin("multiplatform") version "2.1.20"
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvmToolchain(21)
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
