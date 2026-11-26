package com.hizeric.logincore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCoreConfigurationTest {

    @Test
    void adoptPolicyIsRejectedAtStartup() {
        assertFalse(LoginCore.validExistingAccountPolicy("ADOPT"));
        assertTrue(LoginCore.validExistingAccountPolicy("REQUIRE_PASSWORD_ONCE"));
        assertTrue(LoginCore.validExistingAccountPolicy("DENY"));
    }
}
