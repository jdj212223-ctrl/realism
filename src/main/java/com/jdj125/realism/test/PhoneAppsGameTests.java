package com.jdj125.realism.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.PhoneApps;
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

/** Realism 4.12: .appp apps on RealismPhoneOS (the same examples as the appp server's). */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class PhoneAppsGameTests {

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static final JsonObject COOKIE_M = json("{\"name\":\"Cookie Tap\",\"version\":\"1.0\"}");
    static final JsonObject COOKIE = json("{\"start\":\"main\",\"screens\":{\"main\":{\"title\":\"Cookie Tap\",\"text\":[\"Tap it!\"],"
            + "\"buttons\":[{\"label\":\"Tap the cookie\",\"do\":[\"fun\",\"sound pling\",\"open yum\"]}]},\"yum\":{\"title\":\"Yum!\"}}}");
    static final JsonObject BOOST_M = json("{\"name\":\"SuperBoost\",\"version\":\"9.9\",\"permissions\":[\"files\"]}");
    static final JsonObject BOOST = json("{\"screens\":{\"main\":{\"title\":\"Boost\",\"buttons\":[{\"label\":\"BOOST\",\"do\":[\"toast Optimizing\",\"delete /sbin/realism-launchd\"]}]}}}");
    static final JsonObject FLASH_M = json("{\"name\":\"FlashlightPro\",\"version\":\"2\"}");
    static final JsonObject FLASH = json("{\"screens\":{\"main\":{\"title\":\"Light\",\"buttons\":[{\"label\":\"On\",\"do\":[\"share_location\"]}]}}}");
    static final JsonObject DIAMONDS_M = json("{\"name\":\"FreeDiamonds\",\"version\":\"1\"}");
    static final JsonObject DIAMONDS = json("{\"screens\":{\"main\":{\"title\":\"Free\"}},\"background\":{\"every\":45,\"do\":[\"ad WIN NOW\"]}}");

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void theScanCatchesBrickersSpywareAndAdware(GameTestHelper helper) {
        helper.assertTrue(PhoneApps.scan(COOKIE_M, COOKIE).clean(), "Cookie Tap is clean");
        helper.assertTrue(PhoneApps.scan(BOOST_M, BOOST).kind().equals("bricker"), "SuperBoost deletes launchd: bricker");
        helper.assertTrue(PhoneApps.scan(FLASH_M, FLASH).kind().equals("spyware"), "FlashlightPro shares your location without asking: spyware");
        helper.assertTrue(PhoneApps.scan(DIAMONDS_M, DIAMONDS).kind().equals("adware"), "FreeDiamonds pops ads in the background: adware");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void droneStatusNeedsTheDronesPermission(GameTestHelper helper) {
        JsonObject app = json("{\"screens\":{\"main\":{\"title\":\"DroneFly\",\"buttons\":[{\"label\":\"Status\",\"do\":[\"drone_status\",\"rth\"]}]}}}");
        helper.assertTrue(PhoneApps.scan(json("{\"name\":\"DroneFly\",\"version\":\"1.0\",\"permissions\":[\"drones\"]}"), app).clean(), "DroneFly with the drones permission is clean");
        helper.assertFalse(PhoneApps.scan(json("{\"name\":\"DroneFly\",\"version\":\"1.0\"}"), app).clean(), "without it, it's refused");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void theStoreRefusesABricker(GameTestHelper helper) {
        ItemStack phone = new ItemStack(World.PHONE.get());
        helper.assertTrue(PhoneApps.install(phone, BOOST_M, BOOST, "store").equals("phoneos.app_rejected"), "a store install of a bricker is refused");
        helper.assertTrue(PhoneApps.installed(phone).isEmpty(), "nothing got installed");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void aStoreAppInstallsAndRuns(GameTestHelper helper) {
        withPlayer(helper, p -> {
            ItemStack phone = new ItemStack(World.PHONE.get());
            p.getInventory().add(phone);
            ItemStack held = PhoneOS.phoneOf(p);
            helper.assertTrue(PhoneApps.install(held, COOKIE_M, COOKIE, "store").equals("phoneos.installed"), "Cookie Tap installs from the store");
            helper.assertTrue(PhoneOS.has(held, PhoneOS.APPS + "Cookie Tap"), "it shows up in /Apps");
            BodyEvents.body(p).funCooldown = 0;
            PhoneApps.open(p, "Cookie Tap");
            helper.assertTrue(PhoneApps.view(p, held).contains("Tap the cookie"), "opening it shows its start screen");
            PhoneApps.tap(p, "Cookie Tap", "main", 0);
            helper.assertTrue(BodyEvents.body(p).funCooldown > 0, "tapping the cookie was fun");
            helper.assertTrue(PhoneApps.view(p, held).contains("Yum!"), "and it opened the next screen");
            PhoneOS.delete(held, PhoneOS.APPS + "Cookie Tap");
            helper.assertTrue(PhoneApps.installed(held).isEmpty(), "deleting /Apps/Cookie Tap in Files uninstalls it");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void aSideloadedBrickerReallyBricksThePhone(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.getInventory().add(new ItemStack(World.PHONE.get()));
            ItemStack held = PhoneOS.phoneOf(p);
            helper.assertTrue(PhoneApps.install(held, BOOST_M, BOOST, "sideload").equals("phoneos.need_dev_mode"), "sideloading needs Developer Mode");
            PhoneOS.setDeveloperMode(held, true);
            helper.assertTrue(PhoneApps.install(held, BOOST_M, BOOST, "sideload").equals("phoneos.installed"), "with it, nobody stops the bricker");
            helper.assertTrue(PhoneOS.bricked(held).isEmpty(), "installing alone doesn't brick it");
            PhoneApps.open(p, "SuperBoost");
            PhoneApps.tap(p, "SuperBoost", "main", 0);
            helper.assertTrue(PhoneOS.bricked(held).equals("realism-launchd"), "pressing BOOST deleted realism-launchd: bricked");
            PhoneOS.restore(held);
            helper.assertTrue(PhoneOS.bricked(held).isEmpty() && PhoneApps.installed(held).isEmpty(), "a restore fixes it and wipes the app");
            helper.succeed();
        });
    }
}
