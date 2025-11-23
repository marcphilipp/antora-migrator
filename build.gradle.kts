plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.zeroturnaround:zt-exec:1.12")
    implementation("org.apache.commons:commons-lang3:3.20.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "org.junit.antora.Migrator"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.run {
    args("../junit-framework/antora-migration-base")
}
