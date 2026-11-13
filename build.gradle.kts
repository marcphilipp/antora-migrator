plugins {
    application
}

repositories {
    mavenCentral()
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
    args("../doc", "../assertj/docs", "../assertj-db/docs", "../docs.assertj.dev")
}
