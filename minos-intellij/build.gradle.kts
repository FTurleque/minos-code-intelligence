import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

// AUD-DEP-04: the classpath of this build script (the Gradle plugins below and what they bring) is locked in
// buildscript-gradle.lockfile, strictly: a resolution that differs from the lock fails the build.
buildscript {
    dependencyLocking {
        lockMode = LockMode.STRICT
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    // AUD-DEP-04: the Gradle plugins below bring Jackson 2.21.4 and jsoup 1.22.2, both with published advisories
    // (osv-scan). Not shipped by MINOS, but run by the build: raised to their first fixed versions until the plugins
    // bring them. undertow-core has no fixed version: its exception is minos-intellij/osv-scanner.toml.
    dependencies {
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
        constraints {
            classpath("org.jsoup:jsoup:1.23.2")
        }
    }
}

plugins {
    java
    // AUD-TST-05: the coverage of the plugin is measured by Gradle's own JaCoCo plugin (no third-party plugin).
    jacoco
    id("org.jetbrains.intellij.platform") version "2.19.0"
    // Audit only (MINOS-AUD-H09, docs/developer/quality/code-audit.md): their tasks run when called by name, never in
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

// AUD-DEP-04: what the plugin ships (runtimeClasspath), compiles against and tests with is locked in gradle.lockfile,
// strictly. Not lockAllConfigurations(): the intellijPlatform* configurations resolve IDE distributions that differ by
// operating system and change with every IDE bug-fix release (the verifier selects 261.*), and the classes of an IDE
// are not shipped by MINOS. For the same reason the IDE that compileClasspath and testCompileClasspath reach through
// the local `idea:idea` coordinate (intellijIdea below) is ignored by the lock.
dependencyLocking {
    lockMode = LockMode.STRICT
    ignoredDependencies.add("idea:*")
}

configurations {
    listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
        named(name) { resolutionStrategy.activateDependencyLocking() }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    intellijPlatform {
        intellijIdea("2026.1")
        // The plugin verifier is pinned: left to itself the plugin resolves the latest release, whose checksums
        // gradle/verification-metadata.xml cannot know in advance.
        pluginVerifier("1.410")
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
        // The IDE's class loaders define the plugin classes without a code source location, which the JaCoCo agent
        // skips by default: count them, and leave the JDK internals out.
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
    // AUD-TST-05: XML (for the gates and the CI artifacts) and HTML report of the plugin's own classes.
    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required = true
            html.required = true
        }
        // The tests run against the classes the IntelliJ Platform Gradle Plugin rewrites (instrumentCode, @NotNull
        // checks): the report reads those, or JaCoCo would match no execution data and report everything at 0.
        dependsOn("instrumentCode")
        classDirectories.setFrom(layout.buildDirectory.dir("instrumented/instrumentCode").map {
            fileTree(it) { include("com/minos/intellij/**") }
        })
    }
    // AUD-TST-05: an erosion of the plugin coverage fails the build, judged on Linux (the job of IntelliJ plugin
    // (gate)); the Windows job publishes its report, where the strong process launcher also runs (decision D3).
    jacocoTestCoverageVerification {
        dependsOn(jacocoTestReport)
        executionData.setFrom(jacocoTestReport.get().executionData)
        classDirectories.setFrom(jacocoTestReport.get().classDirectories)
        // Measured on Linux (WSL Ubuntu, JDK 21) on 2026-10-15: plugin line 637/1776 (0.359), branch 336/940 (0.357);
        // protocol/ line 459/922 (0.498), branch 238/568 (0.419). Windows measures more (0.390 / 0.381, protocol/
        // 0.557 / 0.458: the strong process launcher runs there). Floor = Linux measure minus 2 points, rounded down to the
        // hundredth (0.339 -> 0.33, 0.337 -> 0.33, 0.478 -> 0.47, 0.399 -> 0.39), raised with the coverage, never
        // lowered without a reason written in docs/project/audits/2026-10-10/SUIVI.md.
        violationRules {
            rule {
                limit { counter = "LINE"; minimum = "0.33".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "0.33".toBigDecimal() }
            }
            rule {
                element = "PACKAGE"
                includes = listOf("com.minos.intellij.protocol")
                limit { counter = "LINE"; minimum = "0.47".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "0.39".toBigDecimal() }
            }
        }
    }
}

jacoco {
    // The version of the Maven reactor (pom.xml, jacoco.version).
    toolVersion = "0.8.15"
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
