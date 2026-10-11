import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "tcds"
version = "0.4.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2026.2.1") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.database")
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.13.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = provider { "262" }
            // semi-internal com.intellij.database API: opt out of future lines until verified
            untilBuild = provider { "262.*" }
        }
    }
    pluginVerification {
        ides {
            // CI exposes -PtcdsLatestIde=<version> to check the next Rider line (non-blocking step)
            if (project.hasProperty("tcdsLatestIde")) {
                create(IntelliJPlatformType.Rider, project.property("tcdsLatestIde") as String)
            } else {
                recommended()
            }
        }
    }
    instrumentCode = false
    buildSearchableOptions = false
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // metadata readable by the Kotlin runtime bundled in Rider 2026.2
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    toolchain {
        // Gradle daemon runs on JBR 25; pin so JavaCompile uses the same VM
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
        systemProperty("idea.log.debug.categories", "#tcds")
    }
    withType<RunIdeTask>().configureEach {
        // ./gradlew runIde -PtcdsOpenProject=<abs path> opens that project in the sandbox IDE
        if (project.hasProperty("tcdsOpenProject")) {
            args = listOf(project.property("tcdsOpenProject").toString())
        }
    }
}
