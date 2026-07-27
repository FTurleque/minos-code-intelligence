import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.minos"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")

    intellijPlatform {
        intellijIdea("2026.1")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.minos.intellij"
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
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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
