package com.jdj125.realism.test;

import com.jdj125.realism.PhoneOS;
import com.jdj125.realism.Realism;
import com.jdj125.realism.World;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 4.11: RealismPhoneOS, bricking and sideloading. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class PhoneOSGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void aNewPhoneBootsAndHasService(GameTestHelper helper) {
        ItemStack phone = new ItemStack(World.PHONE.get());
        helper.assertTrue(PhoneOS.bricked(phone).isEmpty(), "a new phone boots");
        helper.assertTrue(PhoneOS.service(phone, PhoneOS.TELEPHONY), "and can make calls");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void deletingLaunchdBricksAndARestoreFixesIt(GameTestHelper helper) {
        ItemStack phone = new ItemStack(World.PHONE.get());
        helper.assertTrue(PhoneOS.delete(phone, PhoneOS.LAUNCHD).equals("phoneos.deleted_bricked"), "deleting realism-launchd says the phone died");
        helper.assertTrue(PhoneOS.bricked(phone).equals("realism-launchd"), "no realism-launchd: boot loop");
        helper.assertFalse(PhoneOS.service(phone, PhoneOS.TELEPHONY), "a bricked phone has no service");
        PhoneOS.restore(phone);
        helper.assertTrue(PhoneOS.bricked(phone).isEmpty(), "a restore at a computer fixes it");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void noTelephonydNoCalls(GameTestHelper helper) {
        withPlayer(helper, p -> {
            ItemStack phone = new ItemStack(World.PHONE.get());
            PhoneOS.delete(phone, PhoneOS.TELEPHONY);
            helper.assertTrue(PhoneOS.bricked(phone).isEmpty(), "missing one service doesn't brick the phone");
            p.getInventory().add(phone);
            helper.assertTrue(World.callAmbulance(p).equals("phoneos.no_service"), "but without telephonyd you can't call");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void sideloadingNeedsDeveloperModeAndBrickersBrick(GameTestHelper helper) {
        ItemStack phone = new ItemStack(World.PHONE.get());
        helper.assertTrue(PhoneOS.install(phone, PhoneOS.FINE).equals("phoneos.need_dev_mode"), "sideloading needs Developer Mode");
        PhoneOS.setDeveloperMode(phone, true);
        helper.assertTrue(PhoneOS.install(phone, PhoneOS.FINE).equals("phoneos.installed"), "with Developer Mode it installs");
        helper.assertTrue(PhoneOS.bricked(phone).isEmpty(), "a fine app doesn't hurt");
        PhoneOS.install(phone, PhoneOS.BRICKER);
        helper.assertFalse(PhoneOS.bricked(phone).isEmpty(), "a bricker app bricks the phone");
        helper.succeed();
    }
}
