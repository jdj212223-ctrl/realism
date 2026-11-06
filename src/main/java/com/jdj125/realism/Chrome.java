package com.jdj125.realism;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 1.0.0: hospital surgery for the Chromeflesh mod's implants (only when Chromeflesh is installed; found by
 * reflection, so Realism doesn't need it). Holding an implant and using the Operating Table with a Doctor nearby
 * installs it under anesthetic.
 */
final class Chrome {
    private static boolean tried;
    private static Class<?> implantItem;
    private static Field implantField;
    private static Method has, install;

    private Chrome() {
    }

    private static boolean load() {
        if (!tried) {
            tried = true;
            try {
                implantItem = Class.forName("com.chromeflesh.item.ImplantItem");
                implantField = implantItem.getField("implant");
                Class<?> implant = Class.forName("com.chromeflesh.implant.Implant");
                Class<?> implants = Class.forName("com.chromeflesh.implant.Implants");
                has = implants.getMethod("has", net.minecraft.world.entity.player.Player.class, implant);
                install = implants.getMethod("install", ServerPlayer.class, implant);
            } catch (ReflectiveOperationException e) {
                implantItem = null;
            }
        }
        return implantItem != null;
    }

    static boolean isImplant(ItemStack stack) {
        return load() && implantItem.isInstance(stack.getItem());
    }

    /** Installs the implant in this stack; false if it isn't one, or the player already has it. */
    static boolean install(ServerPlayer p, ItemStack stack) {
        if (!isImplant(stack)) return false;
        try {
            Object implant = implantField.get(stack.getItem());
            if ((boolean) has.invoke(null, p, implant)) return false;
            return (boolean) install.invoke(null, p, implant);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
