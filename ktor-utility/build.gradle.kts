val ktorVersion = "2.3.12"

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "org.darchest.kastapi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:${ktorVersion}")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(8)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}