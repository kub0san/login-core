import java.nio.charset.StandardCharsets

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.org/repository/maven-public/")
}

base {
    archivesName.set("login-core")
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
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // Plain jar writes the same filename and would clobber the shaded jar.
    named<Jar>("jar") { enabled = false }

    shadowJar {
        archiveClassifier.set("")
        // do not relocate org.sqlite — JNI loads by class name
    }

    named("build") { dependsOn("shadowJar") }

    // Guard against silent source corruption. Windows PowerShell 5.1 writes files as
    // ASCII by default, which turns characters like the section sign into '?' and
    // quietly breaks regexes and message formatting. Any source file that is not
    // valid UTF-8, or that contains the replacement character, fails the build.
    register("checkSourceEncoding") {
        group = "verification"
        description = "Fails the build if any source or resource file is not valid UTF-8."
        val sourceDirs = listOf("src/main/java", "src/main/resources")
        val files = sourceDirs.map { dir ->
            fileTree(dir) { include("**/*.java", "**/*.yml", "**/*.yaml", "**/*.md") }
        }
        inputs.files(files)
        outputs.upToDateWhen { false }
        doLast {
            val utf8 = StandardCharsets.UTF_8
            val problems = mutableListOf<String>()
            files.flatMap { it.files }.forEach { file ->
                val bytes = file.readBytes()
                // Decoding invalid UTF-8 yields U+FFFD, so this single check catches both
                // malformed byte sequences and literal replacement characters already in the file.
                val text = String(bytes, utf8)
                if (text.contains('\uFFFD')) {
                    problems += "${file.path} is not valid UTF-8 or contains the replacement character (U+FFFD)"
                }
            }
            if (problems.isNotEmpty()) {
                problems.forEach { logger.error(it) }
                throw GradleException("Source encoding check failed:\n" + problems.joinToString("\n"))
            }
        }
    }

    named("check") { dependsOn("checkSourceEncoding") }

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
