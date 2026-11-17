package com.jdj125.realism.test;

import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Realism;
import com.jdj125.realism.Traits;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 4.8: the trait you pick on your first join. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class TraitGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void pickingATraitSticks(GameTestHelper helper) {
        withPlayer(helper, p -> {
            helper.assertTrue(Traits.data(p).trait.isEmpty(), "a new player has no trait yet");
            Traits.handleChoice(p, "paramedic");
            helper.assertTrue(Traits.is(p, Traits.Trait.PARAMEDIC), "picking Paramedic should make you a Paramedic");
            Traits.handleChoice(p, "anxious");
            helper.assertTrue(Traits.is(p, Traits.Trait.PARAMEDIC), "you can't swap traits without the Change Trait item");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void unknownTraitIsIgnored(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Traits.handleChoice(p, "superhero");
            helper.assertTrue(Traits.data(p).trait.isEmpty(), "a made-up trait id must be ignored");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void dronePilotFliesFurther(GameTestHelper helper) {
        withPlayer(helper, p -> {
            int normal = Traits.droneRange(p);
            Traits.handleChoice(p, "drone_pilot");
            helper.assertTrue(Traits.droneRange(p) == normal * 3 / 2, "a Drone Pilot's range should be 50% longer");
            helper.assertTrue(Traits.droneBattery(p, 1000) == 1500, "a Drone Pilot's battery should last 50% longer");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void ironStomachShrugsOffFoodPoisoning(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Traits.handleChoice(p, "iron_stomach");
            BodyEvents.body(p).foodPoisoning = 5000;
            BodyEvents.second(p);
            helper.assertTrue(BodyEvents.body(p).foodPoisoning == 0, "an Iron Stomach should clear food poisoning");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void depressionStartsLowAndTherapyHelps(GameTestHelper helper) {
        withPlayer(helper, p -> {
            BodyEvents.body(p).mood = 70;
            Traits.handleChoice(p, "depression");
            helper.assertTrue(BodyEvents.body(p).mood <= 45, "Living With Depression starts with a lower mood");
            float before = Traits.badDayChance(Traits.data(p));
            Traits.therapy(p);
            helper.assertTrue(Traits.badDayChance(Traits.data(p)) < before, "each therapy session should make bad days rarer");
            for (int i = 0; i < 20; i++) Traits.therapy(p);
            helper.assertTrue(Traits.badDayChance(Traits.data(p)) >= 0.10f, "bad days get rare but never fully disappear");
            helper.succeed();
        });
    }
}
