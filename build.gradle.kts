import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get()
        )

        // We parse and navigate the YAML via the bundled YAML plugin's PSI.
        bundledPlugin("org.jetbrains.plugins.yaml")

        // Needed to resolve Java references for "jump to Java class".
        bundledPlugin("com.intellij.java")

        instrumentationTools()
        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

// IDEA 2024.2.4 predates Java 25. On startup its bundled Gradle plugin
// (com.intellij.gradle) downloads JetBrains' current Gradle/JVM compatibility
// matrix, which now lists Java 25, and 2024.2.4's JavaVersion.parse("25") throws
// (IllegalArgumentException: 25), also provoking a JreHiDpiUtil startup assertion.
// This plugin has no dependency on Gradle integration, so we disable that bundled
// plugin in the runIde sandbox to keep the sandbox IDE clean. Remove this once the
// development platformVersion is a build that understands Java 25.
val sandboxConfigDir = layout.buildDirectory.dir(
    "idea-sandbox/${providers.gradleProperty("platformType").get()}-${providers.gradleProperty("platformVersion").get()}/config"
)
val disableBundledGradlePlugin by tasks.registering {
    val disabledPluginsFile = sandboxConfigDir.map { it.file("disabled_plugins.txt") }
    outputs.file(disabledPluginsFile)
    doLast {
        val file = disabledPluginsFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("com.intellij.gradle\n")
    }
}

tasks {
    named("runIde") {
        dependsOn(disableBundledGradlePlugin)
    }
    named("disableBundledGradlePlugin") {
        mustRunAfter("prepareSandbox")
    }
    wrapper {
        gradleVersion = "8.10.2"
    }
}
