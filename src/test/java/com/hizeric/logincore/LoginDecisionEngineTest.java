package com.hizeric.logincore;

import com.hizeric.logincore.decision.LoginDecisionEngine;
import com.hizeric.logincore.mojang.LookupResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginDecisionEngineTest {

    @Test
    void rejectsInvalidNames() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "!bad!",
            false,
            false,
            null,
            false,
            null,
            null,
            false,
            false,
            false,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, LoginDecisionEngine.decide(input));
    }

    @Test
    void allowsExemptNames() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Admin",
            true,
            true,
            LookupResult.notPremium(),
            false,
            null,
            null,
            false,
            false,
            false,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, LoginDecisionEngine.decide(input));
    }

    @Test
    void premiumClassifiedNameIsNeverExempt() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            true,
            true,
            LookupResult.notPremium(),
            false,
            null,
            null,
            false,
            false,
            false,
            "PREMIUM"
        );

        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

        assertEquals(LoginDecisionEngine.Decision.HANDSHAKE_REQUIRED, decision);
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision, input.lookupResult(), input.hasLink()));
    }

    @Test
    void blocksPremiumNameWithMismatchedClientUuidHint() {
        UUID premiumUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");
        UUID hintedUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");

        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            false,
            true,
            LookupResult.premium(premiumUuid, "Notch"),
            false,
            null,
            hintedUuid,
            false,
            false,
            false,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.DISCONNECT_NAME_RESERVED, LoginDecisionEngine.decide(input));
    }

    @Test
    void requiresHandshakeForPremiumNames() {
        UUID premiumUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");

        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            false,
            true,
            LookupResult.premium(premiumUuid, "Notch"),
            false,
            null,
            premiumUuid,
            false,
            false,
            false,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.HANDSHAKE_REQUIRED, LoginDecisionEngine.decide(input));
    }

    @Test
    void rejectsAccountConflictAfterVerifiedSession() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.ACCOUNT_CONFLICT,
            LoginDecisionEngine.decidePostVerify(
                true,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
                true,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LoginDecisionEngine.ExistingAccountPolicy.REQUIRE_PASSWORD_ONCE
            )
        );
    }

    @Test
    void sendsLinkedNamesThroughVerificationEvenIfLookupSaysNotPremium() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            false,
            true,
            LookupResult.notPremium(),
            true,
            UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
            null,
            false,
            false,
            true,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.HANDSHAKE_REQUIRED, LoginDecisionEngine.decide(input));
    }

    @Test
    void requiresPasswordOnceForExistingUnlinkedAccountsByDefault() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.REQUIRE_PASSWORD_ONCE,
            LoginDecisionEngine.decidePostVerify(
                true,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
                true,
                null,
                LoginDecisionEngine.ExistingAccountPolicy.REQUIRE_PASSWORD_ONCE
            )
        );
    }

    @Test
    void rejectsVerifiedIdentityWhenStoredLinkUuidDiffers() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.ACCOUNT_CONFLICT,
            LoginDecisionEngine.decidePostVerify(
                true,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
                true,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LoginDecisionEngine.ExistingAccountPolicy.REQUIRE_PASSWORD_ONCE
            )
        );
    }

    @Test
    void registersNewAuthMeAccountsAfterVerification() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.REGISTER_AND_AUTO_LOGIN,
            LoginDecisionEngine.decidePostVerify(
                true,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
                false,
                null,
                LoginDecisionEngine.ExistingAccountPolicy.DENY
            )
        );
    }

    @Test
    void denyPolicyStillRejectsUnverifiedExistingAccounts() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.DENY,
            LoginDecisionEngine.decidePostVerify(
                true,
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1"),
                true,
                null,
                LoginDecisionEngine.ExistingAccountPolicy.DENY
            )
        );
    }

    @Test
    void autoLogsInAccountsAlreadyLinkedToTheVerifiedUuid() {
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.AUTO_LOGIN,
            LoginDecisionEngine.decidePostVerify(
                true,
                uuid,
                true,
                uuid,
                LoginDecisionEngine.ExistingAccountPolicy.DENY
            )
        );
    }

    @Test
    void rejectsFailedSessionVerification() {
        assertEquals(
            LoginDecisionEngine.PostVerifyAction.VERIFY_FAILED,
            LoginDecisionEngine.decidePostVerify(
                false,
                null,
                false,
                null,
                LoginDecisionEngine.ExistingAccountPolicy.REQUIRE_PASSWORD_ONCE
            )
        );
    }

    @Test
    void unknownLookupWithoutLinkIsDeniedByDefault() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            false,
            true,
            LookupResult.unknown("timeout"),
            false,
            null,
            null,
            false,
            false,
            false,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.KICK_VERIFY_FAILED, LoginDecisionEngine.decide(input));
    }

    @Test
    void unknownLookupWithoutLinkPassesThroughWhenAllowed() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch",
            false,
            true,
            LookupResult.unknown("timeout"),
            false,
            null,
            null,
            false,
            false,
            true,
            null
        );

        assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, LoginDecisionEngine.decide(input));
    }

    @Test
    void storedOfflineCannotBecomePremium() {
        UUID premiumUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38a0e1");
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch", false, true, LookupResult.premium(premiumUuid, "Notch"),
            false, null, premiumUuid, false, false, false, "OFFLINE"
        );

        assertEquals(LoginDecisionEngine.Decision.DISCONNECT_NAME_RESERVED, LoginDecisionEngine.decide(input));
    }

    @Test
    void storedPremiumIsNotDowngradedByUnknownLookup() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch", false, true, LookupResult.unknown("timeout"),
            false, null, null, false, false, true, "PREMIUM"
        );

        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

        assertEquals(LoginDecisionEngine.Decision.HANDSHAKE_REQUIRED, decision);
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision, input.lookupResult(), input.hasLink()));
    }

    @Test
    void storedPremiumNotFoundDoesNotFallThroughToPassword() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "Notch", false, true, LookupResult.notPremium(),
            false, null, null, false, false, false, "PREMIUM"
        );

        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

        assertEquals(LoginDecisionEngine.Decision.HANDSHAKE_REQUIRED, decision);
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision, input.lookupResult(), input.hasLink()));
    }

    @Test
    void notFoundPassesThroughAndClassifiesOnlyWhenUnlinked() {
        LoginDecisionEngine.DecisionInput input = new LoginDecisionEngine.DecisionInput(
            "OfflineGuy", false, true, LookupResult.notPremium(),
            false, null, null, false, false, false, null
        );

        LoginDecisionEngine.Decision decision = LoginDecisionEngine.decide(input);

        assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, decision);
        assertTrue(LoginDecisionEngine.shouldClassifyOffline(decision, input.lookupResult(), input.hasLink()));
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision,
            LookupResult.unknown("timeout"), input.hasLink()));
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(decision,
            LookupResult.notPremium(), true));
    }

    @Test
    void unknownLookupNeverClassifiesOffline() {
        LoginDecisionEngine.DecisionInput denied = new LoginDecisionEngine.DecisionInput(
            "Notch", false, true, LookupResult.unknown("5xx"),
            false, null, null, false, false, false, null
        );
        LoginDecisionEngine.Decision deniedDecision = LoginDecisionEngine.decide(denied);
        assertEquals(LoginDecisionEngine.Decision.KICK_VERIFY_FAILED, deniedDecision);
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(deniedDecision, denied.lookupResult(), false));

        LoginDecisionEngine.DecisionInput allowed = new LoginDecisionEngine.DecisionInput(
            "Notch", false, true, LookupResult.unknown("timeout"),
            false, null, null, false, false, true, null
        );
        LoginDecisionEngine.Decision allowedDecision = LoginDecisionEngine.decide(allowed);
        assertEquals(LoginDecisionEngine.Decision.PASS_THROUGH, allowedDecision);
        assertFalse(LoginDecisionEngine.shouldClassifyOffline(allowedDecision, allowed.lookupResult(), false));
    }
}
