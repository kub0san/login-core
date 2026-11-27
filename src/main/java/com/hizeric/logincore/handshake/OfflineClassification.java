package com.hizeric.logincore.handshake;

import com.hizeric.logincore.storage.Storage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class OfflineClassification {

    private OfflineClassification() {
    }

    /**
     * The write-once setter is the TOCTOU guard: classification was read before the Mojang lookup,
     * so a concurrent handshake can commit PREMIUM in that window. ALREADY_DIFFERENT must fail
     * closed — never reinject the cracked login.
     */
    static CompletableFuture<Storage.ClassificationResult> write(Storage storage, String name,
                                                                 Runnable passThrough,
                                                                 Consumer<String> disconnect,
                                                                 Consumer<String> warn) {
        return storage.setClassification(name, "OFFLINE")
            .whenComplete((result, error) -> {
                if (error != null) {
                    warn.accept("Could not persist OFFLINE classification for " + name + ": " + error);
                    disconnect.accept("verify-failed");
                } else if (result == Storage.ClassificationResult.ALREADY_DIFFERENT) {
                    warn.accept("Classification for " + name
                        + " changed during login; refusing offline pass-through");
                    disconnect.accept("account-conflict");
                } else {
                    passThrough.run();
                }
            });
    }
}
