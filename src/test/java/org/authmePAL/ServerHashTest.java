package org.authmePAL;

import org.authmePAL.handshake.ServerHash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerHashTest {

    @Test
    void computesKnownMinecraftServerHashValues() {
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", ServerHash.compute("Notch"));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", ServerHash.compute("jeb_"));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", ServerHash.compute("simon"));
    }
}
