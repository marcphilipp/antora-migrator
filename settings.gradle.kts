plugins {
    id("com.gradle.develocity") version "4.2.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "antora-migrator"

develocity {
    server = "https://ge.junit.org"
}
