package org.authmePAL.handshake;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;

public final class CipherInstaller {

    private CipherInstaller() {
    }

    public static void install(Channel channel, SecretKeySpec secretKeySpec) throws Exception {
        if (channel == null || secretKeySpec == null) {
            throw new IllegalArgumentException("channel and secretKeySpec are required");
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get("authmePAL_decrypt") != null || pipeline.get("authmePAL_encrypt") != null) {
            throw new IllegalStateException("Encryption handlers are already installed");
        }

        byte[] secret = secretKeySpec.getEncoded();
        if (secret == null || secret.length != 16) {
            throw new IllegalArgumentException("AES shared secret must be exactly 16 bytes");
        }
        IvParameterSpec iv = new IvParameterSpec(secret);
        Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, iv);
        Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, iv);

        if (pipeline.get("splitter") == null || pipeline.get("prepender") == null) {
            throw new IllegalStateException("Expected Minecraft framing handlers 'splitter' and 'prepender' were not found");
        }
        pipeline.addBefore("splitter", "authmePAL_decrypt", new DecryptHandler(decryptCipher));
        pipeline.addBefore("prepender", "authmePAL_encrypt", new EncryptHandler(encryptCipher));
    }

    private static final class DecryptHandler extends MessageToMessageDecoder<ByteBuf> {

        private final Cipher cipher;

        private DecryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf message, List<Object> output) throws Exception {
            byte[] encrypted = new byte[message.readableBytes()];
            message.readBytes(encrypted);
            byte[] decrypted = cipher.update(encrypted);
            if (decrypted != null && decrypted.length > 0) {
                output.add(context.alloc().buffer(decrypted.length).writeBytes(decrypted));
            }
        }
    }

    private static final class EncryptHandler extends MessageToMessageEncoder<ByteBuf> {

        private final Cipher cipher;

        private EncryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf message, List<Object> output) throws Exception {
            byte[] plaintext = new byte[message.readableBytes()];
            message.readBytes(plaintext);
            byte[] encrypted = cipher.update(plaintext);
            if (encrypted != null && encrypted.length > 0) {
                output.add(context.alloc().buffer(encrypted.length).writeBytes(encrypted));
            }
        }
    }
}
