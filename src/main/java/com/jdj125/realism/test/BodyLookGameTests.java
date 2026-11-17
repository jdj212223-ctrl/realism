package com.jdj125.realism.test;

import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.BodyLook;
import com.jdj125.realism.Life;
import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 4.9: injuries show on your skin. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class BodyLookGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void woundsAndBandagesShow(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            helper.assertTrue(BodyLook.flags(p, b) == 0, "a healthy player shows nothing");
            b.bleeding = 2;
            helper.assertTrue(BodyLook.bleeding(BodyLook.flags(p, b)) == 2, "heavy bleeding shows heavy cuts");
            b.bleeding = 0;
            b.bandaged = 1000;
            b.tourniquet = 10;
            int f = BodyLook.flags(p, b);
            helper.assertTrue((f & BodyLook.BANDAGE) != 0 && (f & BodyLook.TOURNIQUET) != 0, "bandage and tourniquet show");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void paleTiredAndSweaty(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.blood = 3.5f;
            b.sleepStage = 2;
            b.stamina = 5;
            int f = BodyLook.flags(p, b);
            helper.assertTrue((f & BodyLook.PALE) != 0, "low blood makes you pale");
            helper.assertTrue((f & BodyLook.EYE_BAGS) != 0, "no sleep gives eye bags");
            helper.assertTrue((f & BodyLook.SWEAT) != 0, "no stamina makes you sweat");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void bruisesChangeColourAsTheyHeal(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            long full = RealismConfig.dayTicks() * 3;
            b.bruise = (int) full;
            helper.assertTrue(BodyLook.bruise(BodyLook.flags(p, b)) == 1, "a fresh bruise is red");
            b.bruise = (int) (full / 2);
            helper.assertTrue(BodyLook.bruise(BodyLook.flags(p, b)) == 2, "a healing bruise is purple");
            b.bruise = (int) (full / 5);
            helper.assertTrue(BodyLook.bruise(BodyLook.flags(p, b)) == 3, "an old bruise is yellow");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void accidentDarkensPantsAndShowerFixesIt(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.bladder = 100;
            BodyEvents.second(p);
            helper.assertTrue((BodyLook.flags(p, b) & BodyLook.WET) != 0, "an accident makes your pants dark and wet");
            Life.shower(p);
            helper.assertTrue((BodyLook.flags(p, b) & BodyLook.WET) == 0, "a shower fixes it");
            helper.succeed();
        });
    }
}
