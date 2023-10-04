plugins {
    id("java")
}

group "eu.jameshamilton"
version "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation(project(":runtime"))

    implementation("com.guardsquare:proguard-core:9.1.0")
}