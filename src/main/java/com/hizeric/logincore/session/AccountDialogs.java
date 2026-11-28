package com.hizeric.logincore.session;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

/** In-memory vanilla dialogs for the post-join register/login prompts. */
public final class AccountDialogs {

    private static final boolean SUPPORTED = detectDialogApi();
    // Paper exposes Player#showDialog(DialogLike) only on newer Adventure builds; our 1.21.11
    // API jar predates it, so the call is bridged reflectively and falls back to plain commands.
    private static final Method SHOW_DIALOG = findShowDialog();

    private AccountDialogs() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    /** True only when the runtime Player class exposes a showDialog method. */
    public static boolean canShow() {
        return SUPPORTED && SHOW_DIALOG != null;
    }

    /** True when Paper's dialog provider is installed (only on a running server). */
    public static boolean canBuild() {
        if (!SUPPORTED) {
            return false;
        }
        try {
            io.papermc.paper.registry.data.dialog.DialogInstancesProvider.instance();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static Dialog buildRegisterDialog(Plugin plugin) {
        DialogBase base = DialogBase.builder(Component.text("Register"))
            .inputs(List.of(
                DialogInput.text("password", Component.text("Password")).maxLength(100).build(),
                DialogInput.text("confirm", Component.text("Confirm password")).maxLength(100).build()))
            .body(List.of(DialogBody.plainMessage(
                Component.text("Enter a new password twice to secure your account."))))
            .canCloseWithEscape(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .build();
        return create(base, "Register", "register $(password) $(confirm)");
    }

    public static Dialog buildLoginDialog(Plugin plugin) {
        DialogBase base = DialogBase.builder(Component.text("Login"))
            .inputs(List.of(DialogInput.text("password", Component.text("Password")).maxLength(100).build()))
            .body(List.of(DialogBody.plainMessage(
                Component.text("Enter your account password to log in."))))
            .canCloseWithEscape(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .build();
        return create(base, "Login", "login $(password)");
    }

    /** Best-effort show on the main thread; returns false when the runtime has no dialog API. */
    public static boolean show(Player player, Dialog dialog) {
        if (!canShow() || player == null || dialog == null) {
            return false;
        }
        try {
            SHOW_DIALOG.invoke(player, dialog);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static Dialog create(DialogBase base, String label, String commandTemplate) {
        ActionButton button = ActionButton.builder(Component.text(label))
            .action(DialogAction.commandTemplate(commandTemplate))
            .build();
        return Dialog.create(factory -> factory.empty()
            .base(base)
            .type(DialogType.multiAction(List.of(button)).columns(1).build()));
    }

    private static boolean detectDialogApi() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static Method findShowDialog() {
        try {
            return Player.class.getMethod("showDialog", DialogLike.class);
        } catch (NoSuchMethodException ignored) {
            // Fall through to a name/arity scan for runtimes with a differently typed parameter.
        }
        for (Method method : Player.class.getMethods()) {
            if (method.getName().equals("showDialog") && method.getParameterCount() == 1) {
                Class<?> parameter = method.getParameterTypes()[0];
                if (parameter.isAssignableFrom(DialogLike.class)
                    || DialogLike.class.isAssignableFrom(parameter)) {
                    return method;
                }
            }
        }
        return null;
    }
}
