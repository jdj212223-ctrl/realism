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

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void droneFliesWithAController(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            var d = new com.jdj125.realism.Drone.Flyer(com.jdj125.realism.Drone.TYPE.get(), level);
            d.owner = p.getUUID();
            BlockPos start = helper.absolutePos(new BlockPos(4, 3, 4));
            d.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
            level.addFreshEntity(d);
            p.moveTo(start.getX() + 2.5, start.getY(), start.getZ() + 0.5, 0, 0);
            // No controller: the drone won't listen.
            com.jdj125.realism.Drone.handlePilot(p, new com.jdj125.realism.Drone.Pilot(d.getId(), true));
            helper.assertTrue(d.pilot == null, "you need a Drone Controller");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.jdj125.realism.Drone.CONTROLLER.get()));
            com.jdj125.realism.Drone.handlePilot(p, new com.jdj125.realism.Drone.Pilot(d.getId(), true));
            helper.assertTrue(p.getUUID().equals(d.pilot), "with a controller you fly it (found=" + (p.level().getEntity(d.getId()) == d)
                    + " dist=" + p.distanceTo(d) + " hand=" + p.getMainHandItem() + " owner=" + d.owner + ")");
            int battery = d.battery();
            double y0 = d.getY();
            // Full up for a second.
            for (int i = 0; i < 20; i++) helper.runAfterDelay(i, () -> com.jdj125.realism.Drone.handleSticks(p,
                    new com.jdj125.realism.Drone.Sticks(d.getId(), 0, 0, 1, 0, 0)));
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(d.getY() > y0 + 1, "it climbs (from " + y0 + " to " + d.getY() + ")");
                helper.assertTrue(d.battery() < battery, "flying uses the battery");
                d.hurt(level.damageSources().playerAttack(p), 1);
                helper.assertTrue(d.isRemoved(), "punching it picks it up");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void fruitVomitingAndCountry(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var r = net.minecraft.util.RandomSource.create(1);
            helper.assertTrue(java.util.List.of("banana", "mango", "pineapple", "orange", "tangerine", "lemon")
                    .contains(com.jdj125.realism.Food.fruitFor(2.0f, r)), "hot places grow tropical fruit");
            helper.assertTrue(java.util.List.of("pear", "plum", "cherries", "grapes").contains(com.jdj125.realism.Food.fruitFor(0.3f, r)),
                    "cool places grow pears, plums and cherries");
            helper.assertTrue(com.jdj125.realism.Food.isFruit(new net.minecraft.world.item.ItemStack(com.jdj125.realism.Food.FRUITS.get("tangerine").get())),
                    "a tangerine is a fruit");
            helper.assertTrue(com.jdj125.realism.Food.FRUITS.containsKey(com.jdj125.realism.Food.favorite(p)), "everyone has a favorite fruit");
            p.getFoodData().setFoodLevel(18);
            helper.assertTrue(com.jdj125.realism.Food.vomit(p), "throwing up works");
            helper.assertTrue(p.getFoodData().getFoodLevel() == 14 && p.hasEffect(net.minecraft.world.effect.MobEffects.CONFUSION), "you lose food and feel sick");
            helper.assertFalse(com.jdj125.realism.Food.vomit(p), "but not again right away");
            int fruit = 0;
            BlockPos leafAt = helper.absolutePos(new BlockPos(3, 2, 3));
            var leaves = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState();
            for (int i = 0; i < 2000; i++)
                for (var drop : net.minecraft.world.level.block.Block.getDrops(leaves, helper.getLevel(), leafAt, null))
                    if (com.jdj125.realism.Food.isFruit(drop)) fruit++;
            helper.assertTrue(fruit > 20, "leaves drop fruit now and then (got " + fruit + " from 2000)");
            var server = helper.getLevel().getServer();
            helper.assertTrue(com.jdj125.realism.Era.setCountry(server, "cz"), "a world can be in Czechia");
            helper.assertTrue(com.jdj125.realism.Era.Data.get(server).country.equals("CZ"), "the country is saved");
            helper.assertFalse(com.jdj125.realism.Era.setCountry(server, "XX"), "made-up countries don't work");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void fireAndHeating(GameTestHelper helper) {
        var level = helper.getLevel();
        // A fireplace with no chimney smokes up the room; a chimney fixes it.
        BlockPos fp = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(fp, com.jdj125.realism.Fire.FIREPLACE.get().defaultBlockState());
        com.jdj125.realism.Fire.Burner.addFuel(level, fp, level.getBlockState(fp), 3);
        var lit = level.getBlockState(fp);
        helper.assertTrue(lit.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                && lit.getValue(com.jdj125.realism.Fire.FUEL) == 3, "fuel lights the fireplace");
        helper.assertTrue(com.jdj125.realism.Fire.smokyFireplace(level, fp, lit), "no chimney = smoke");
        level.setBlockAndUpdate(fp.above(), com.jdj125.realism.Fire.CHIMNEY.get().defaultBlockState());
        helper.assertFalse(com.jdj125.realism.Fire.smokyFireplace(level, fp, lit), "a chimney takes the smoke out");
        // The extinguisher's spray puts fire out.
        BlockPos f = helper.absolutePos(new BlockPos(5, 2, 5));
        level.setBlockAndUpdate(f.below(), net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState());
        level.setBlockAndUpdate(f, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
        helper.assertTrue(com.jdj125.realism.Fire.smokeAt(level, f, 3) > 0, "fire makes smoke");
        helper.assertTrue(com.jdj125.realism.Fire.extinguish(level, f, null) == 1, "one fire put out");
        helper.assertFalse(level.getBlockState(f).is(net.minecraft.world.level.block.Blocks.FIRE), "the fire is gone");
        helper.assertTrue(com.jdj125.realism.NoCheats.allowed("/fire"), "calling the fire department isn't cheating");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void smokeHurtsUnlessYouCrouch(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            BlockPos at = p.blockPosition();
            // A roof over the player, and a fire next to them.
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++) level.setBlockAndUpdate(at.offset(x, 3, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(at.offset(2, -1, 0), net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState());
            level.setBlockAndUpdate(at.offset(2, 0, 0), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
            helper.assertTrue(com.jdj125.realism.Fire.breathe(p), "standing indoors near fire = breathing smoke (indoors="
                    + com.jdj125.realism.Fire.indoors(level, p.blockPosition()) + " smoke=" + com.jdj125.realism.Fire.smokeAt(level, p.blockPosition(), 6)
                    + " fire=" + level.getBlockState(at.offset(2, 0, 0)) + " mode=" + p.gameMode.getGameModeForPlayer() + ")");
            helper.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS), "smoke stings your eyes");
            p.removeAllEffects();
            p.setShiftKeyDown(true);
            p.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            helper.assertTrue(com.jdj125.realism.Fire.breathe(p), "still in the smoke when crouching");
            helper.assertFalse(p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS), "but crouching keeps you under it");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void noCommandsForPlayers(GameTestHelper helper) {
        withPlayer(helper, p -> {
            helper.assertTrue(com.jdj125.realism.NoCheats.root("/minecraft:give @s dirt").equals("give"), "reads the command name");
            helper.assertFalse(com.jdj125.realism.NoCheats.allowed("/gamemode creative"), "/gamemode is blocked");
            helper.assertFalse(com.jdj125.realism.NoCheats.allowed("give @s diamond"), "/give is blocked");
            helper.assertTrue(com.jdj125.realism.NoCheats.allowed("/pay jdj125 5"), "/pay still works");
            // Even an op player gets nothing from /give...
            var server = helper.getLevel().getServer();
            server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), "give @s minecraft:diamond 5");
            helper.assertFalse(p.getInventory().contains(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND)),
                    "a player's /give does nothing");
            // ...but the server console still can.
            BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withLevel(helper.getLevel()),
                    "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:gold_block");
            helper.assertTrue(helper.getLevel().getBlockState(at).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK),
                    "the server console's commands still work");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void creativeSwitchesBackToSurvival(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.setGameMode(GameType.CREATIVE);
            helper.assertTrue(com.jdj125.realism.NoCheats.fixGameMode(p), "creative gets fixed");
            helper.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "back in survival");
            p.setGameMode(GameType.ADVENTURE);
            helper.assertFalse(com.jdj125.realism.NoCheats.fixGameMode(p), "adventure (the afterlife) is left alone");
            helper.succeed();
        });
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
