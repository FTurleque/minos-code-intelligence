import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
    // Audit only (MINOS-AUD-H09, docs/quality/code-audit.md): their tasks run when called by name, never in
    // `test buildPlugin`. Versions and settings mirror the reactor's audit profiles. Dependency-Check is left out
    // until it can refresh its database (H04): its Gradle task does not reuse the Maven one.
    id("com.github.spotbugs") version "6.5.12"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.minos"
version = providers.gradleProperty("minosVersion").orElse("1.0.1-SNAPSHOT").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    intellijPlatform {
        intellijIdea("2026.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.minos.codeintelligence"
        name = "MINOS Code Intelligence"
        version = project.version.toString()
        description = "Native IntelliJ client for local-first MINOS Code Intelligence."
        vendor {
            name = "MINOS"
        }
        ideaVersion {
            sinceBuild = "261"
        }
    }
    pluginVerification {
        ides {
            current()
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "261"
                untilBuild = "261.*"
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
    }
}

spotbugs {
    toolVersion = "4.10.4"
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    ignoreFailures = true
    excludeFilter = rootProject.file("../quality/spotbugs-exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("xml") { required = true }
    reports.create("html") { required = true }
}


pitest {
    pitestVersion = "1.30.0"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.minos.intellij.*")
    targetTests = setOf("com.minos.intellij.*")
    threads = 4
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    failWhenNoMutations = false
    jvmArgs = listOf("-Xmx512m")
    // recalcitrantProcessForcedKill goes through IntelliJ's UnixProcessManager, whose native C library the PIT minion
    // cannot load on Linux ("Couldn't load c library"); it passes under `gradle test`. PIT refuses a red baseline.
    excludedTestClasses = setOf("com.minos.intellij.protocol.MinosProcessSupervisorTest")
}

// PIT runs the tests outside the IntelliJ test sandbox: it needs the platform classes the plugin compiles against.
tasks.named<info.solidsoft.gradle.pitest.PitestTask>("pitest") {
    additionalClasspath.from(configurations.named("intellijPlatformClasspath"))
}
