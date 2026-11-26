package com.hizeric.logincore.decision;

import com.hizeric.logincore.mojang.LookupResult;

import java.util.UUID;

public final class LoginDecisionEngine {

    public enum Decision {
        PASS_THROUGH,
        DISCONNECT_NAME_RESERVED,
        HANDSHAKE_REQUIRED,
        KICK_VERIFY_FAILED,
        KICK_ACCOUNT_CONFLICT
    }

    public enum PostVerifyAction {
        REGISTER_AND_AUTO_LOGIN,
        AUTO_LOGIN,
        LINK_AND_AUTO_LOGIN,
        REQUIRE_PASSWORD_ONCE,
        DENY,
        VERIFY_FAILED,
        ACCOUNT_CONFLICT
    }

    public enum ExistingAccountPolicy {
        REQUIRE_PASSWORD_ONCE,
        ADOPT,
        DENY
    }

    public record DecisionInput(
        String username,
        boolean exempt,
        boolean validName,
        LookupResult lookupResult,
        boolean hasLink,
        UUID linkedUuid,
        UUID uuidHint,
        boolean handshakeSuccess,
        boolean shadowMode,
        boolean allowLookupFailure,
        String storedClassification
    ) {
    }

    private LoginDecisionEngine() {
    }

    public static Decision decide(DecisionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        if (!input.validName()) {
            return Decision.PASS_THROUGH;
        }
        if (input.exempt()) {
            return Decision.PASS_THROUGH;
        }
        // OFFLINE cannot become PREMIUM, even if a handshake somehow succeeded.
        if ("OFFLINE".equals(input.storedClassification())
            && input.lookupResult() != null && input.lookupResult().isPremium()) {
            return Decision.DISCONNECT_NAME_RESERVED;
        }
        if (input.handshakeSuccess()) {
            return Decision.PASS_THROUGH;
        }
        // A stored PREMIUM name never falls through to password/pass-through on a live
        // result that is not premium; only a completed handshake grants it. If no UUID
        // is available, beginHandshake fails closed before any grant.
        if ("PREMIUM".equals(input.storedClassification())
            && input.lookupResult() != null && !input.lookupResult().isPremium()) {
            return Decision.HANDSHAKE_REQUIRED;
        }
        if (input.lookupResult() != null && input.lookupResult().isNotPremium()) {
            return input.hasLink() ? Decision.HANDSHAKE_REQUIRED : Decision.PASS_THROUGH;
        }
        if (input.lookupResult() != null && input.lookupResult().isUnknown() && input.hasLink()) {
            return Decision.HANDSHAKE_REQUIRED;
        }
        if (input.lookupResult() != null && input.lookupResult().isUnknown() && !input.hasLink()) {
            return input.allowLookupFailure() ? Decision.PASS_THROUGH : Decision.KICK_VERIFY_FAILED;
        }
        if (input.lookupResult() != null && input.lookupResult().isPremium()) {
            if (input.uuidHint() != null && !input.uuidHint().equals(input.lookupResult().mojangUuid())) {
                return Decision.DISCONNECT_NAME_RESERVED;
            }
            return Decision.HANDSHAKE_REQUIRED;
        }
        if (input.shadowMode()) {
            return Decision.PASS_THROUGH;
        }
        return Decision.KICK_VERIFY_FAILED;
    }

    /**
     * OFFLINE is an authoritative-absence classification: only an allowed, unlinked login whose
     * lookup proved absence writes it. An UNKNOWN lookup is inconclusive and stays unclassified.
     */
    public static boolean shouldClassifyOffline(Decision decision, LookupResult lookupResult, boolean hasLink) {
        return decision == Decision.PASS_THROUGH
            && lookupResult != null
            && lookupResult.isNotPremium()
            && !hasLink;
    }

    public static PostVerifyAction decidePostVerify(boolean handshakeSucceeded, UUID verifiedUuid,
                                                     boolean authMeRegistered, UUID linkedUuid,
                                                     ExistingAccountPolicy policy) {
        if (!handshakeSucceeded || verifiedUuid == null) {
            return PostVerifyAction.VERIFY_FAILED;
        }
        if (linkedUuid != null && !linkedUuid.equals(verifiedUuid)) {
            return PostVerifyAction.ACCOUNT_CONFLICT;
        }
        if (!authMeRegistered) {
            return PostVerifyAction.REGISTER_AND_AUTO_LOGIN;
        }
        if (linkedUuid != null) {
            return PostVerifyAction.AUTO_LOGIN;
        }
        return switch (policy) {
            case REQUIRE_PASSWORD_ONCE -> PostVerifyAction.REQUIRE_PASSWORD_ONCE;
            case ADOPT -> PostVerifyAction.LINK_AND_AUTO_LOGIN;
            case DENY -> PostVerifyAction.DENY;
        };
    }
}
