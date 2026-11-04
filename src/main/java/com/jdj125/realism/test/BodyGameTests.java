package com.jdj125.realism.test;

import com.jdj125.realism.Blood;
import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Run with ./gradlew runGameTestServer. Checks the phase 1 body rules on a mock survival player. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class BodyGameTests {

    /**
     * A fresh survival player, handed to the check once its 3 s of spawn protection are over (a protected player
     * can't be hurt, so nothing could kill it yet).
     */
    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    // One "second" of the body rules (the mock player counts as creative, which onTick skips).
    private static void second(ServerPlayer p) {
        BodyEvents.second(p);
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void dehydrationKills(GameTestHelper helper) {
        withPlayer(helper, p -> {
            BodyEvents.body(p).thirst = RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks() - 10;
            second(p);
            helper.assertTrue(p.getHealth() <= 0, "3 days without water should kill");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void thirstWarnsBeforeDeath(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.thirst = (long) (RealismConfig.THIRST_DAYS.get() * RealismConfig.dayTicks() * 0.7);
            second(p);
            helper.assertTrue(p.getHealth() > 0, "still alive at 70% thirst");
            helper.assertTrue(b.thirstStage == 2, "dizzy stage at 70%, got " + b.thirstStage);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void sleeplessnessKills(GameTestHelper helper) {
        withPlayer(helper, p -> {
            BodyEvents.body(p).awake = RealismConfig.SLEEP_DAYS.get() * RealismConfig.dayTicks();
            second(p);
            helper.assertTrue(p.getHealth() <= 0, "11 days without sleep should kill");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void thirdDegreeBurnHurtsAndLasts(GameTestHelper helper) {
        withPlayer(helper, p -> {
            float before = p.getHealth();
            BodyEvents.burn(p, 3);
            Body b = BodyEvents.body(p);
            helper.assertTrue(b.burnDegree == 3 && b.burn > 0, "3rd degree burn recorded");
            helper.assertTrue(p.getHealth() < before, "a burn hurts");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void severeBleedingDrainsBlood(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            Blood.bleed(p, 3);
            float before = b.blood;
            second(p);
            helper.assertTrue(b.blood < before, "severe bleeding loses blood every second");
            b.tourniquet = 1;
            float held = b.blood;
            second(p);
            helper.assertTrue(b.blood == held, "a tourniquet holds severe bleeding");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void bleedingOutKills(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.blood = Blood.DEAD + 0.01f;
            Blood.bleed(p, 3);
            second(p);
            helper.assertTrue(p.getHealth() <= 0, "under 2 liters you bleed out");
            helper.succeed();
        });
    }

    @GameTest(template = "arena")
    public static void bloodTypesMatchLikeRealLife(GameTestHelper helper) {
        helper.assertTrue(Blood.compatible("O-", "AB+"), "O- gives to everyone");
        helper.assertTrue(Blood.compatible("A+", "AB+"), "AB+ takes from everyone");
        helper.assertTrue(Blood.compatible("B-", "B+"), "Rh- gives to Rh+ of the same group");
        helper.assertFalse(Blood.compatible("A+", "A-"), "Rh+ can't go to Rh-");
        helper.assertFalse(Blood.compatible("A+", "B+"), "A can't go to B");
        helper.assertFalse(Blood.compatible("AB-", "O-"), "AB can't go to O");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void transfusionAddsBloodOrReacts(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.setData(Realism.BLOOD_TYPE, "A+");
            Body b = BodyEvents.body(p);
            b.blood = 3.8f;
            helper.assertTrue(Blood.transfuse(p, "O-"), "O- into A+ works");
            helper.assertTrue(Math.abs(b.blood - 4.3f) < 0.01f, "adds half a liter, got " + b.blood);
            helper.assertFalse(Blood.transfuse(p, "B+"), "B+ into A+ is a reaction");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void exhaustionKillsWhenPushing(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.stamina = 0;
            b.overexertion = 89 * 20;
            p.setSprinting(true);
            b.handStrain = 5; // still working hard
            second(p);
            helper.assertTrue(p.getHealth() <= 0, "pushing on with no stamina should end in exhaustion");
            helper.succeed();
        });
    }
}
