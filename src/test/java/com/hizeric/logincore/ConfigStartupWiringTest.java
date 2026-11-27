package com.hizeric.logincore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStartupWiringTest {

    @Test
    void onEnablePreparesConfigurationBeforeValidation() throws IOException {
        String source = Files.readString(
            Path.of("src/main/java/com/hizeric/logincore/LoginCore.java"), StandardCharsets.UTF_8);

        int saveDefaults = source.indexOf("saveDefaultConfig()");
        int prepareCall = source.indexOf("prepareConfiguration()", saveDefaults);
        int validationCall = source.indexOf("validConfiguration()", prepareCall);
        int legacyImport = source.indexOf("LegacyAuthMeImport.importIfNeeded");
        int reconcile = source.indexOf("ConfigUpdater.reconcile");
        int reload = source.indexOf("reloadConfig()");
        int validationDecl = source.lastIndexOf("validConfiguration()");

        assertTrue(saveDefaults >= 0, "onEnable must call saveDefaultConfig()");
        assertTrue(prepareCall > saveDefaults, "saveDefaultConfig() must run before prepareConfiguration()");
        assertTrue(validationCall > prepareCall, "prepareConfiguration() must run before validConfiguration()");

        assertTrue(legacyImport >= 0, "startup must call LegacyAuthMeImport.importIfNeeded");
        assertTrue(reconcile >= 0, "startup must call ConfigUpdater.reconcile");
        assertTrue(reload >= 0, "startup must call reloadConfig()");
        assertTrue(validationDecl >= 0, "LoginCore must declare validConfiguration()");

        assertTrue(legacyImport > saveDefaults, "the legacy import must come after saveDefaultConfig()");
        assertTrue(reconcile > legacyImport, "the legacy import must run before ConfigUpdater.reconcile");
        assertTrue(reload > reconcile, "ConfigUpdater.reconcile must run before reloadConfig()");
        assertTrue(reload < validationDecl, "all configuration preparation must precede validConfiguration()");
    }
}
