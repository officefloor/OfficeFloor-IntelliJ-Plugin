import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
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

    // Plugin signing. JetBrains Marketplace strongly recommends signed plugins.
    // The Release workflow supplies these from the OfficeFloor organisation's
    // secrets (see officefloor/OfficeFloorMaintenance). A secret that is not set
    // arrives as an empty variable, which counts as unset; publishPlugin refuses
    // to run without all of them (below), so nothing is published unsigned.
    signing {
        certificateChain = secret("CERTIFICATE_CHAIN")
        privateKey = secret("PRIVATE_KEY")
        password = secret("PRIVATE_KEY_PASSWORD")
    }

    // Publishing via `./gradlew publishPlugin`. PUBLISH_TOKEN is a Marketplace
    // permanent token, supplied as a repository secret by the Release workflow.
    publishing {
        token = secret("PUBLISH_TOKEN")
    }

    // Run the JetBrains Plugin Verifier against the IDEs recommended for the
    // plugin's compatibility range. publishPlugin depends on verifyPlugin below,
    // so verification runs before every publish.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

fun secret(name: String) = providers.environmentVariable(name).filter { it.isNotBlank() }
val publishingSecrets = listOf("PUBLISH_TOKEN", "CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD")

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
    // Never publish a build that has not passed the Plugin Verifier.
    named("publishPlugin") {
        dependsOn("verifyPlugin")
        // Never publish without the token, or unsigned
        val secrets = publishingSecrets.associateWith { secret(it) }
        doFirst {
            val missing = secrets.filterValues { !it.isPresent }.keys
            if (missing.isNotEmpty()) {
                throw GradleException("publishPlugin needs ${missing.joinToString()} (nothing was published)")
            }
        }
    }
    wrapper {
        gradleVersion = "8.10.2"
    }
}
