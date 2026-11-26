package com.jdj125.realism.test;

import com.jdj125.realism.PhoneLife;
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

/** Realism 4.14: RealismPhoneOS 2: battery, updates, saved messages. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class PhoneLifeGameTests {

    @SuppressWarnings("removal")
    private static void withPhone(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().add(new ItemStack(World.PHONE.get()));
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void batteryDrainsAndADeadPhoneCantCall(GameTestHelper helper) {
        withPhone(helper, p -> {
            ItemStack phone = PhoneOS.phoneOf(p);
            helper.assertTrue(PhoneLife.battery(phone) == 100, "a new phone is fully charged");
            for (int i = 0; i < PhoneLife.DRAIN_SECONDS; i++) PhoneLife.second(p, phone);
            helper.assertTrue(PhoneLife.battery(phone) == 99, "a minute in your pocket uses 1% (" + PhoneLife.battery(phone) + ")");
            PhoneLife.setBattery(phone, 0);
            helper.assertTrue(PhoneLife.dead(phone), "0% is a dead phone");
            helper.assertFalse(PhoneOS.canCall(p), "a dead phone can't call");
            helper.assertTrue(World.callAmbulance(p).equals("phoneos.no_service"), "so no ambulance by phone");
            PhoneLife.charge(p, 100);
            helper.assertTrue(PhoneLife.battery(phone) == 100 && PhoneOS.canCall(p), "a night's sleep charges it back up");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void aDyingBatteryBricksAnUpdate(GameTestHelper helper) {
        withPhone(helper, p -> {
            ItemStack phone = PhoneOS.phoneOf(p);
            PhoneLife.setBattery(phone, 2);
            helper.assertTrue(PhoneLife.startUpdate(p, phone).equals("phonelife.update_low"), "updating on low battery warns you");
            for (int i = 0; i < 12; i++) PhoneLife.second(p, phone);
            helper.assertTrue(PhoneOS.bricked(phone).equals("update"), "the battery died mid-update: bricked (" + PhoneOS.bricked(phone) + ")");
            PhoneOS.restore(phone);
            helper.assertTrue(PhoneOS.bricked(phone).isEmpty() && PhoneLife.battery(phone) == 100, "a restore at a computer fixes it");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 800)
    public static void aFullUpdateInstallsTheNewVersion(GameTestHelper helper) {
        withPhone(helper, p -> {
            ItemStack phone = PhoneOS.phoneOf(p);
            helper.assertTrue(PhoneLife.startUpdate(p, phone).equals("phonelife.update_started"), "a charged phone starts updating");
            helper.runAfterDelay(PhoneLife.UPDATE_SECONDS * 20 + 5, () -> {
                PhoneLife.second(p, phone);
                helper.assertTrue(PhoneLife.version(PhoneOS.os(phone)).equals(PhoneLife.NEXT_VERSION), "the update installed");
                helper.assertTrue(PhoneOS.bricked(phone).isEmpty(), "and the phone still works");
                helper.assertTrue(PhoneLife.startUpdate(p, phone).equals("phonelife.up_to_date"), "now it's up to date");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void messagesAreSavedOnThePhone(GameTestHelper helper) {
        withPhone(helper, p -> {
            ItemStack phone = PhoneOS.phoneOf(p);
            PhoneLife.record(phone, "Alex", false, "hi; want = to play?");
            PhoneLife.record(phone, "Alex", true, "yes!");
            String s = PhoneLife.summary(p, phone);
            helper.assertTrue(s.contains(";unread=1"), "one unread message (" + s + ")");
            helper.assertFalse(s.substring(s.indexOf(";msgs=") + 6).split(";")[0].isEmpty(), "the messages are in the summary");
            helper.assertTrue(s.split(";").length == PhoneLife.summary(p, phone).split(";").length && !s.contains("hi; want"),
                    "a ; in a text can't break the phone's data");
            PhoneLife.handle(p, phone, "read");
            helper.assertTrue(PhoneLife.summary(p, phone).contains(";unread=0"), "opening Messages reads them");
            for (int i = 0; i < 50; i++) PhoneLife.record(phone, "Alex", true, "spam " + i);
            helper.assertTrue(PhoneOS.os(phone).getList("msgs", 10).size() == PhoneLife.MAX_MESSAGES, "only the last 40 are kept");
            helper.succeed();
        });
    }
}
