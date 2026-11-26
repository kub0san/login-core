package com.hizeric.logincore;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import com.hizeric.logincore.handshake.CipherInstaller;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CipherInstallerTest {

    @Test
    void encryptsOutboundAndDecryptsInboundWithContinuousCfb8State() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        CipherInstaller.install(channel, new SecretKeySpec(new byte[16], "AES"));
        byte[] plaintext = "login-core handshake test".getBytes(StandardCharsets.UTF_8);

        try {
            assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(plaintext)));
            ByteBuf ciphertext = channel.readOutbound();
            assertNotNull(ciphertext);
            byte[] encrypted = new byte[ciphertext.readableBytes()];
            ciphertext.readBytes(encrypted);
            ciphertext.release();
            assertFalse(Arrays.equals(plaintext, encrypted));

            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(encrypted)));
            ByteBuf decrypted = channel.readInbound();
            assertNotNull(decrypted);
            byte[] actual = new byte[decrypted.readableBytes()];
            decrypted.readBytes(actual);
            decrypted.release();
            assertArrayEquals(plaintext, actual);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
