package com.jdj125.realism.test;

import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Cars;
import com.jdj125.realism.Jobs;
import com.jdj125.realism.Life;
import com.jdj125.realism.Realism;
import com.jdj125.realism.World;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 2.0.2: daily life, jobs, cars and roads. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class EverydayGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void bathroomFixesDailyLife(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.hygiene = 10;
            b.bladder = 90;
            b.teeth = 5;
            Life.shower(p);
            Life.toilet(p);
            Life.brush(p);
            helper.assertTrue(b.hygiene == 100 && b.bladder == 0 && b.teeth == 100, "shower, toilet and toothbrush fix everything");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void waitingTooLongIsAnAccident(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.hygiene = 100;
            b.bladder = 99.99f;
            BodyEvents.second(p);
            helper.assertTrue(b.bladder < 1, "the bladder emptied itself");
            helper.assertTrue(b.hygiene <= 51, "and now you need a shower");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void jobsPayForTheirOwnWork(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.setData(Jobs.JOB, "miner");
            Jobs.work(p, "farmer", 10);
            helper.assertTrue(Jobs.wage(p) == 0, "farm work doesn't pay a miner");
            Jobs.work(p, "miner", 10);
            helper.assertTrue(Jobs.wage(p) == 30, "10 ores at 3 each");
            long before = World.balance(p);
            Jobs.payday(p);
            helper.assertTrue(World.balance(p) == before + 30, "payday puts it in the bank (no country, no tax)");
            helper.assertTrue(Jobs.wage(p) == 0, "and a new day starts");
            Jobs.work(p, "miner", 1000);
            helper.assertTrue(Jobs.wage(p) == Jobs.DAILY_CAP, "one day can only pay so much");
            helper.succeed();
        });
    }

    @GameTest(template = "arena")
    public static void carsNeedFuelAndLikeAsphalt(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        helper.setBlock(at.below(), Cars.ASPHALT.get());
        Cars.Car car = helper.spawn(Cars.CAR.get(), at);
        car.setFuel(Cars.TANK * 3);
        helper.assertTrue(car.fuel() == Cars.TANK, "a tank only holds so much");
        car.setFuel(-5);
        helper.assertTrue(car.fuel() == 0, "and can't go below empty");
        car.setColor(6);
        helper.assertTrue(car.color() == 2, "colours wrap around");
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void trafficLightsCycle(GameTestHelper helper) {
        BlockPos at = new BlockPos(2, 2, 2);
        helper.setBlock(at, Cars.TRAFFIC_LIGHT.get());
        BlockPos abs = helper.absolutePos(at);
        BlockState s = helper.getLevel().getBlockState(abs);
        helper.assertTrue(s.getValue(Cars.TrafficLight.LIGHT) == 0, "starts green");
        s.tick(helper.getLevel(), abs, helper.getLevel().random);
        helper.assertTrue(helper.getLevel().getBlockState(abs).getValue(Cars.TrafficLight.LIGHT) == 1, "then yellow");
        helper.getLevel().getBlockState(abs).tick(helper.getLevel(), abs, helper.getLevel().random);
        helper.assertTrue(helper.getLevel().getBlockState(abs).getValue(Cars.TrafficLight.LIGHT) == 2, "then red");
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void newThingsAreInTheGame(GameTestHelper helper) {
        for (String id : new String[]{"toilet", "shower", "sink", "toothbrush", "asphalt", "road_line", "traffic_light", "gas_pump", "gas_can", "car_red"}) {
            var key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Realism.MODID, id);
            helper.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key), id + " is an item");
        }
        helper.succeed();
    }
}
