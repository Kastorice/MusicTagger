import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Pure JVM library: no Android dependencies. Used by the app module and by unit tests.
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    systemProperty("ffmpeg.path", System.getenv("FFMPEG_PATH") ?: "D:\\ffmpeg-8.1-essentials_build\\bin\\ffmpeg.exe")
    systemProperty("ffprobe.path", System.getenv("FFPROBE_PATH") ?: "D:\\ffmpeg-8.1-essentials_build\\bin\\ffprobe.exe")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
