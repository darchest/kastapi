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
    implementation(project(":annotations"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.10-1.0.24")

    implementation("io.swagger.core.v3:swagger-core:2.2.25")
    implementation("io.swagger.core.v3:swagger-models:2.2.25")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")


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
        maven {
            name = "darchest"
            url = uri("https://mvn.darchest.org/repository/snapshots/")
            credentials {
                username = findProperty("mvn.darchest.user") as String? ?: ""
                password = findProperty("mvn.darchest.password") as String? ?: ""
            }
        }
    }
}