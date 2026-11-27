package com.hizeric.logincore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformBaselineTest {

    @Test
    void pluginYmlDeclaresTargetApiVersion() throws IOException {
        var lines = Files.readAllLines(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(lines.stream().anyMatch(line -> line.trim().equals("api-version: '1.21.11'")),
                "plugin.yml must declare api-version: '1.21.11'");
    }

    @Test
    void buildGradleTargetsPaperAndMockBukkitBaseline() throws IOException {
        var build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("paper-api:1.21.11-R0.1-SNAPSHOT"),
                "build.gradle.kts must compile against paper-api:1.21.11-R0.1-SNAPSHOT");
        assertTrue(build.contains("mockbukkit-v1.21:4.116.3"),
                "build.gradle.kts must test against mockbukkit-v1.21:4.116.3");
    }

    @Test
    void ciWorkflowBuildsWithJava25() throws IOException {
        var workflow = Path.of(".github/workflows/build.yml");
        assertTrue(Files.exists(workflow), "CI workflow must exist at .github/workflows/build.yml");
        if (!Files.exists(workflow)) {
            return;
        }
        var content = Files.readString(workflow, StandardCharsets.UTF_8);
        assertTrue(content.contains("clean check shadowJar"),
                "CI workflow must run clean check shadowJar");
        assertTrue(content.contains("java-version: '25'"),
                "CI workflow must set up Java 25");
    }
}
