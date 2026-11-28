package com.hizeric.logincore.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AccountDialogsTest {

    @Test
    void supportCheckNeverThrows() {
        assertDoesNotThrow(AccountDialogs::isSupported);
    }

    @Test
    void buildsBothDialogsWhenApiIsPresent() {
        assumeTrue(AccountDialogs.isSupported(), "Paper dialog API is not on the classpath");
        assumeTrue(AccountDialogs.canBuild(), "Dialog provider is installed only on a running Paper server");
        assertNotNull(AccountDialogs.buildRegisterDialog());
        assertNotNull(AccountDialogs.buildLoginDialog());
    }
}
