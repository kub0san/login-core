package com.hizeric.logincore.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Proxy-based command seam, matching AdminCommandTest: no MockBukkit bootstrap. */
final class PasswordCommandTestSupport {

    private PasswordCommandTestSupport() {
    }

    static Recorder player(String name) {
        List<String> messages = new CopyOnWriteArrayList<>();
        List<String> kicks = new CopyOnWriteArrayList<>();
        Player proxy = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (self, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "hasPermission" -> Boolean.TRUE;
                case "isOnline" -> Boolean.TRUE;
                case "sendMessage" -> {
                    messages.add(text(args[args.length - 1]));
                    yield null;
                }
                case "kick", "kickPlayer" -> {
                    kicks.add(text(args[0]));
                    yield null;
                }
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString" -> "Player[" + name + "]";
                default -> defaultValue(method.getReturnType());
            });
        return new Recorder(proxy, messages, kicks);
    }

    private static String text(Object value) {
        if (value instanceof Component component) {
            return PlainTextComponentSerializer.plainText().serialize(component);
        }
        if (value instanceof String[] parts) {
            return String.join(" ", parts);
        }
        return String.valueOf(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    record Recorder(Player player, List<String> messages, List<String> kicks) {
        boolean said(String fragment) {
            return messages.stream().anyMatch(message -> message.contains(fragment));
        }

        String transcript() {
            return String.join(" | ", messages);
        }
    }
}
