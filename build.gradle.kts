plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.org/repository/maven-public/")
}

base {
    archivesName.set("AuthMePAL")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("fr.xephi:authme-core:6.0.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.netty:netty-transport:4.1.72.Final")
    testImplementation("io.netty:netty-codec:4.1.72.Final")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testRuntimeOnly("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
