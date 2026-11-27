package com.hizeric.logincore.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MigrationAuditTest {

    @Test
    void migrationAuditHasNoVendorApiImport() throws Exception {
        Path source = Path.of("src/main/java/com/hizeric/logincore/migration/MigrationAudit.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertFalse(text.contains("fr.xephi"),
            "MigrationAudit must not reference vendor API types; use Storage.PlayerInfo");
    }

    @Test
    void migrationAuditHasNoExemptionApplyMethod() {
        boolean exposesApply = Arrays.stream(MigrationAudit.class.getDeclaredMethods())
            .anyMatch(method -> "applyPremiumNameExemptions".equals(method.getName()));
        assertFalse(exposesApply, "MigrationAudit must not expose an exemption-apply method");
    }
}
