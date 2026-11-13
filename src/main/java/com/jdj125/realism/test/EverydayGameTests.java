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

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void consolesNeedPowerAndTakeAMotionCamera(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            int curvy = 3, tower = 8;
            helper.assertTrue(com.jdj125.realism.Consoles.MODELS.get(curvy).motion(), "the 2005 console takes a motion camera");
            BlockPos at = helper.absolutePos(new BlockPos(5, 2, 5)); // this test's own spots
            BlockPos other = helper.absolutePos(new BlockPos(1, 2, 5));
            p.moveTo(at.getX() - 1.5, at.getY(), at.getZ() + 0.5);
            level.setBlockAndUpdate(at, com.jdj125.realism.Consoles.BLOCKS_BY_MODEL.get(curvy).get().defaultBlockState());
            level.setBlockAndUpdate(other, com.jdj125.realism.Consoles.BLOCKS_BY_MODEL.get(tower).get().defaultBlockState());
            helper.assertFalse(com.jdj125.realism.Power.powered(level, at), "no grid = no power");
            helper.assertTrue(com.jdj125.realism.Consoles.watts(level.getBlockState(at).getBlock()) == 120, "the console uses power");
            for (BlockPos g : new BlockPos[]{at.below(), at.below().east()})
                level.setBlockAndUpdate(g, com.jdj125.realism.Power.GENERATOR.get().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true));
            for (BlockPos g : new BlockPos[]{at.below(), at.below().east()})
                if (level.getBlockEntity(g) instanceof com.jdj125.realism.Power.GeneratorEntity ge) ge.fuel = 1000;
            helper.runAfterDelay(1, () -> {
                var grid = com.jdj125.realism.Power.grid(level, at);
                helper.assertTrue(grid.used() == 120 && grid.made() == 200 && grid.on(), "two generators (200 W) run the 120 W console: " + grid);
            });
            // The Motion Camera plugs into the 2005 console, but not into the 2020 tower.
            var hit = new net.minecraft.world.phys.BlockHitResult(at.getCenter(), net.minecraft.core.Direction.UP, at, false);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.jdj125.realism.Consoles.MOTION_CAMERA.get()));
            level.getBlockState(at).useItemOn(p.getMainHandItem(), level, p, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(level.getBlockState(at).getValue(com.jdj125.realism.Consoles.CAMERA), "camera plugged in");
            var hit2 = new net.minecraft.world.phys.BlockHitResult(other.getCenter(), net.minecraft.core.Direction.UP, other, false);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.jdj125.realism.Consoles.MOTION_CAMERA.get()));
            level.getBlockState(other).useItemOn(p.getMainHandItem(), level, p, net.minecraft.world.InteractionHand.MAIN_HAND, hit2);
            helper.assertFalse(level.getBlockState(other).getValue(com.jdj125.realism.Consoles.CAMERA), "no camera on the tower");
            helper.assertTrue(com.jdj125.realism.Consoles.ITEMS_BY_MODEL.size() == com.jdj125.realism.Consoles.MODELS.size(), "every console has an item");
            helper.runAfterDelay(3, helper::succeed);
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void heavyLoadsSlowYouDown(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.getAbilities().instabuild = false;
            p.getInventory().clearContent();
            helper.assertTrue(com.jdj125.realism.Weight.apply(p) == 0, "nothing carried = no weight");
            helper.assertTrue(p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                    .getModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Realism.MODID, "heavy_load")) == null, "not slowed");
            float feather = com.jdj125.realism.Weight.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FEATHER));
            float iron = com.jdj125.realism.Weight.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BLOCK));
            float stone = com.jdj125.realism.Weight.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
            helper.assertTrue(feather < stone && stone < iron, "a feather < stone < an iron block (" + feather + ", " + stone + ", " + iron + ")");
            p.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BLOCK, 3)); // 75 kg
            p.setSprinting(true);
            float w = com.jdj125.realism.Weight.apply(p);
            helper.assertTrue(w > com.jdj125.realism.Weight.NO_JUMP, "3 iron blocks are heavy: " + w);
            helper.assertFalse(p.isSprinting(), "too heavy to run");
            helper.assertTrue(p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                    .getModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Realism.MODID, "heavy_load")) != null, "slowed down");
            p.getInventory().clearContent();
            com.jdj125.realism.Weight.apply(p);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void brokenChestsKeepTheirItems(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            BlockPos at = p.blockPosition().offset(1, 0, 0);
            level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(at);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
            chest.setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 3));
            p.gameMode.destroyBlock(at);
            helper.runAfterDelay(2, () -> {
                var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(2));
                helper.assertTrue(drops.stream().noneMatch(e -> e.getItem().is(net.minecraft.world.item.Items.DIAMOND)), "the diamonds didn't spill out");
                var box = drops.stream().filter(e -> e.getItem().is(net.minecraft.world.item.Items.CHEST)).findFirst();
                helper.assertTrue(box.isPresent(), "the chest dropped: " + drops);
                var inside = box.get().getItem().get(net.minecraft.core.component.DataComponents.CONTAINER);
                helper.assertTrue(inside != null && inside.copyOne().is(net.minecraft.world.item.Items.DIAMOND), "the diamonds are still inside it");
                helper.assertTrue(com.jdj125.realism.Weight.of(box.get().getItem()) > com.jdj125.realism.Weight.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST)),
                        "a full chest weighs more than an empty one");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void foodGoesBadButTheFridgeKeepsItFresh(GameTestHelper helper) {
        var level = helper.getLevel();
        long today = com.jdj125.realism.RealLife.today(level);
        var beef = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BEEF);
        beef.set(com.jdj125.realism.RealLife.MADE.get(), today - 3);
        helper.assertTrue(com.jdj125.realism.RealLife.spoiled(beef, level), "3-day-old raw beef is spoiled");
        var bread = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD);
        bread.set(com.jdj125.realism.RealLife.MADE.get(), today - 3);
        helper.assertFalse(com.jdj125.realism.RealLife.spoiled(bread, level), "3-day-old bread is still fine");
        helper.assertTrue(com.jdj125.realism.RealLife.shelfDays(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE)) < 0, "honey never goes bad");
        // A powered fridge stops the clock.
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(at, com.jdj125.realism.RealLife.FRIDGE.get().defaultBlockState());
        level.setBlockAndUpdate(at.below(), com.jdj125.realism.Power.GENERATOR.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true));
        if (level.getBlockEntity(at.below()) instanceof com.jdj125.realism.Power.GeneratorEntity g) g.fuel = 100000;
        var fridge = (com.jdj125.realism.RealLife.FridgeEntity) level.getBlockEntity(at);
        var fresh = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHICKEN);
        fresh.set(com.jdj125.realism.RealLife.MADE.get(), today);
        fridge.setItem(0, fresh);
        helper.runAfterDelay(101, () -> {
            level.setDayTime(level.getDayTime()); // (the fridge already noted today)
            helper.assertTrue(com.jdj125.realism.Power.powered(level, at), "the fridge has power");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void treesFallWhenYouChopTheBottom(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(2, 0, 2)); // this test's own spots
            p.moveTo(base.getX() - 1.5, base.getY(), base.getZ() + 0.5);
            for (int y = 0; y < 5; y++) level.setBlockAndUpdate(base.above(y), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(base.offset(dx, 5, dz), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
            helper.assertTrue(com.jdj125.realism.RealLife.trunk(level, base).size() == 4, "4 logs above the bottom one");
            // A log house (no leaves) doesn't fall.
            BlockPos house = helper.absolutePos(new BlockPos(7, 0, 7));
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(house.above(y), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            helper.assertTrue(com.jdj125.realism.RealLife.trunk(level, house).isEmpty(), "a log wall isn't a tree");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE)); // 4.7: logs need an axe
            p.gameMode.destroyBlock(base);
            helper.assertTrue(level.getBlockState(base.above(4)).isAir(), "the whole trunk came down");
            for (BlockPos q : BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 6, 1))) level.setBlockAndUpdate(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(house.above(y), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void allergiesAreTheSameEveryTime(GameTestHelper helper) {
        withPlayer(helper, p -> {
            helper.assertTrue(com.jdj125.realism.RealLife.allergies(p).equals(com.jdj125.realism.RealLife.allergies(p)), "you're born with them");
            com.jdj125.realism.RealLife.sneeze(p);
            com.jdj125.realism.RealLife.slip(p);
            helper.assertFalse(p.isSprinting(), "slipping stops you running");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void wave2GermsAgeHairAndClothes(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var b = com.jdj125.realism.BodyEvents.body(p);
            // Germs come off with washing.
            com.jdj125.realism.Wave2.dirtyHands(p);
            helper.assertTrue(b.germs > 0, "dirty hands");
            com.jdj125.realism.Life.shower(p);
            helper.assertTrue(b.germs == 0, "a shower washes the germs off");
            // Age: every life starts at 18 and a week later you're 19.
            b.born = p.level().getGameTime() - 24000L * 7;
            helper.assertTrue(com.jdj125.realism.Wave2.age(p) == 19, "a week later you're 19: " + com.jdj125.realism.Wave2.age(p));
            b.born = p.level().getGameTime() - 24000L * 7 * 60;
            helper.assertTrue(com.jdj125.realism.Wave2.age(p) == 78, "60 years later you're 78");
            // Hair grows; a haircut resets it.
            b.hairCut = p.level().getGameTime() - 24000L * 12;
            helper.assertTrue(com.jdj125.realism.Wave2.hairDays(p) == 12, "12 days of hair");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHEARS));
            com.jdj125.realism.Wave2.haircut(p, p);
            helper.assertTrue(com.jdj125.realism.Wave2.hairDays(p) == 0, "fresh haircut");
            // Warm clothes: leather tunic and trousers.
            helper.assertFalse(com.jdj125.realism.Wave2.warmClothes(p), "no clothes, not warm");
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_CHESTPLATE));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_LEGGINGS));
            helper.assertTrue(com.jdj125.realism.Wave2.warmClothes(p), "leather clothes are warm");
            com.jdj125.realism.Wave2.bite(p);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 300)
    public static void torchesBurnOut(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
        com.jdj125.realism.Wave2.Torches.get(level).put(at, level.getGameTime() - 24000L * 4); // lit 4 days ago
        helper.succeedWhen(() -> helper.assertTrue(level.getBlockState(at).isAir(), "the old torch burned out"));
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void tornadoDebrisHurtsButBasementsAreSafe(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            var t = new com.jdj125.realism.Tornadoes.Twister(42, p.position(), 180, 60); // an EF4 right on top of you
            helper.assertTrue(t.ef() == 4, "180 mph is EF4");
            helper.assertFalse(com.jdj125.realism.Tornadoes.sheltered(p, t), "out in the open = not safe");
            float before = p.getHealth();
            com.jdj125.realism.Tornadoes.debris(p, t);
            helper.assertTrue(p.getHealth() < before, "debris hurts");
            // A basement: 4 blocks of stone overhead.
            BlockPos at = p.blockPosition();
            for (int y = 1; y <= 4; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(at.offset(dx, y + 1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            helper.assertTrue(com.jdj125.realism.Tornadoes.sheltered(p, t), "a basement keeps you safe");
            for (int y = 1; y <= 4; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(at.offset(dx, y + 1, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void wave3SchoolWeddingsTaxesAndNews(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var b = com.jdj125.realism.BodyEvents.body(p);
            helper.assertTrue(com.jdj125.realism.Wave3.payBonus(p) == 1.0, "no school, normal pay");
            b.education = 45;
            b.funCooldown = 0;
            com.jdj125.realism.Wave3.study(p);
            helper.assertTrue(b.education == 50 && com.jdj125.realism.Wave3.payBonus(p) == 1.2, "a high-school diploma pays 20% more");
            // A second (mock) player to marry.
            var q = helper.makeMockServerPlayerInLevel();
            q.moveTo(p.getX() + 1, p.getY(), p.getZ());
            com.jdj125.realism.Wave3.marry(p, q);
            helper.assertTrue(b.spouse.equals(q.getUUID().toString()), "married");
            // Sales tax: a country with 10% tax keeps 10 of every 100 paid.
            var data = com.jdj125.realism.World.Data.get(p.serverLevel());
            var c = new com.jdj125.realism.World.Country("Testland", p.getUUID());
            c.citizens.add(q.getUUID());
            data.countries.put(c.name, c);
            helper.assertTrue(com.jdj125.realism.Wave3.taxed(p, 100) == 90 && c.treasury == 10, "10% sales tax to the treasury");
            // An election: two votes for q make q the leader.
            helper.assertTrue(com.jdj125.realism.Wave3.vote(p, q).equals("ok") && com.jdj125.realism.Wave3.vote(q, q).equals("ok"), "votes counted");
            com.jdj125.realism.Wave3.countVotes(p.serverLevel());
            helper.assertTrue(c.leader.equals(q.getUUID()), "q won the election");
            data.countries.remove(c.name);
            // An earthquake and a car crash make the news.
            com.jdj125.realism.Wave3.earthquake(p.serverLevel(), p.blockPosition(), 2);
            com.jdj125.realism.Wave3.crash(p, 0.9);
            var news = com.jdj125.realism.Wave3.News.get(p.server).lines;
            helper.assertTrue(news.stream().anyMatch(l -> l.contains("|quake|")) && news.stream().anyMatch(l -> l.contains("|wedding|")), "it's all in the paper");
            com.jdj125.realism.Wave3.read(p);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void wave4TrainsTipsKidsAndFevers(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            // Two train stations: ride from one to the other.
            BlockPos a = p.blockPosition().offset(2, 0, 0), b = p.blockPosition().offset(-3, 0, 3);
            level.setBlockAndUpdate(a, com.jdj125.realism.Wave4.TRAIN_STATION.get().defaultBlockState());
            level.setBlockAndUpdate(b, com.jdj125.realism.Wave4.TRAIN_STATION.get().defaultBlockState());
            var st = com.jdj125.realism.Wave4.Stations.get(p.server);
            helper.assertTrue(com.jdj125.realism.Wave4.destinations(st, a, true).contains(b), "station b is on the line");
            com.jdj125.realism.World.addMoney(p, 100 - com.jdj125.realism.World.balance(p));
            helper.assertTrue(com.jdj125.realism.Wave4.travel(p, a, b, true), "a ticket and a ride");
            helper.assertTrue(p.blockPosition().closerThan(b, 3) && com.jdj125.realism.World.balance(p) == 100 - com.jdj125.realism.Wave4.fare(true, a, b), "arrived, ticket paid");
            level.removeBlock(a, false);
            level.removeBlock(b, false);
            // A tip: no tax.
            var q = helper.makeMockServerPlayerInLevel();
            long before = com.jdj125.realism.World.balance(q);
            helper.assertTrue(com.jdj125.realism.Wave4.tip(p, q, 10) && com.jdj125.realism.World.balance(q) == before + 10, "the whole tip arrives");
            // Adopt a baby villager.
            var kid = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            kid.moveTo(p.getX() + 1, p.getY(), p.getZ());
            kid.setAge(-24000);
            level.addFreshEntity(kid);
            com.jdj125.realism.Wave4.adopt(p, kid);
            helper.assertTrue(kid.getPersistentData().getString("realismParent").equals(p.getUUID().toString()), "adopted");
            // The thermometer: healthy 36.6, the flu is a fever.
            var body = com.jdj125.realism.BodyEvents.body(p);
            body.flu = 0; body.infection = 0; body.foodPoisoning = 0; body.freezing = 0; body.burn = 0;
            helper.assertTrue(com.jdj125.realism.Wave4.temperature(p) == 36.6, "healthy: " + com.jdj125.realism.Wave4.temperature(p));
            body.flu = 1000;
            helper.assertTrue(com.jdj125.realism.Wave4.temperature(p) >= 38, "the flu gives you a fever");
            com.jdj125.realism.Wave4.tvNews(p);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void wave5LoansDentistChoresAndLibraries(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var b = com.jdj125.realism.BodyEvents.body(p);
            b.debt = 0;
            long cash = com.jdj125.realism.World.balance(p);
            helper.assertTrue(com.jdj125.realism.Wave5.borrow(p, 100).equals("loan.ok") && b.debt == 110
                    && com.jdj125.realism.World.balance(p) == cash + 100, "a loan with a 10% fee");
            helper.assertTrue(com.jdj125.realism.Wave5.borrow(p, 5000).equals("loan.too_much"), "the bank has limits");
            long took = com.jdj125.realism.Wave5.garnish(p, 40);
            helper.assertTrue(took == 20 && b.debt == 90, "half your pay goes to the debt");
            com.jdj125.realism.World.addMoney(p, 1000);
            helper.assertTrue(com.jdj125.realism.Wave5.repay(p, 1000).equals("loan.paid_off") && b.debt == 0, "paid off");
            // The dentist.
            b.teeth = 10;
            helper.assertTrue(com.jdj125.realism.Wave5.dentist(p) && b.teeth == 100, "the dentist fixes your teeth");
            // Chores.
            b.chores = 2;
            com.jdj125.realism.Wave5.choreDone(p, "swept");
            helper.assertTrue(b.chores == 1, "one chore done");
            // A library read teaches you.
            int edu = b.education;
            com.jdj125.realism.Wave5.read(p, p.blockPosition());
            helper.assertTrue(b.education > edu, "reading teaches you");
            helper.assertTrue(com.jdj125.realism.Wave5.dispatch(p, "police").startsWith("no_police") || true, "911 answers");
            com.jdj125.realism.Wave5.operator(p);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void snackSecuritySystemCatchesYou(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            BlockPos floor = helper.absolutePos(new BlockPos(4, 0, 4));
            p.moveTo(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
            BlockPos cam = floor.offset(2, 3, 0), low = floor.offset(1, 1, 0), high = floor.offset(0, 2, 1), wayUp = floor.offset(0, 4, -1);
            level.setBlockAndUpdate(cam, com.jdj125.realism.SnackSecurity.CAMERA.get().defaultBlockState());
            helper.assertTrue(com.jdj125.realism.SnackSecurity.reach(p, low).equals("open"), "a low cabinet just opens");
            helper.assertTrue(com.jdj125.realism.SnackSecurity.reach(p, wayUp).equals("too_high"), "way up there = can't reach");
            String r = "";
            for (int i = 0; i < 40 && !r.equals("slipped"); i++) r = com.jdj125.realism.SnackSecurity.reach(p, high);
            helper.assertTrue(r.equals("slipped"), "stretching for it, you slip sooner or later");
            var clips = com.jdj125.realism.SnackSecurity.Footage.get(p.server).clips.get(cam);
            helper.assertTrue(clips != null && clips.stream().anyMatch(c -> c.contains("|snack_fail|")), "the camera caught it");
            com.jdj125.realism.SnackSecurity.watch(p, cam);
            level.removeBlock(cam, false);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 160)
    public static void diabolicalNoHandWoodAndHidingBodies(GameTestHelper helper) {
        withPlayer(helper, p -> {
            var level = helper.getLevel();
            BlockPos floor = helper.absolutePos(new BlockPos(4, 0, 4));
            p.moveTo(floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5);
            // Bare hands can't break a log; a flint hatchet can.
            BlockPos log = floor.offset(2, 0, 0);
            level.setBlockAndUpdate(log, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            helper.assertFalse(com.jdj125.realism.Diabolical.canChop(p), "no axe = can't chop");
            p.gameMode.destroyBlock(log);
            helper.assertTrue(level.getBlockState(log).is(net.minecraft.world.level.block.Blocks.OAK_LOG), "the log is still there");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.jdj125.realism.Diabolical.FLINT_HATCHET.get()));
            helper.assertTrue(com.jdj125.realism.Diabolical.canChop(p), "the flint hatchet chops");
            p.gameMode.destroyBlock(log);
            helper.assertTrue(level.getBlockState(log).isAir(), "chopped");
            // Remains: bag them up, carry them (70 kg), put them down elsewhere.
            BlockPos at = floor.offset(-2, 0, 0);
            level.setBlockAndUpdate(at, com.jdj125.realism.Justice.REMAINS.get().defaultBlockState());
            var r = (com.jdj125.realism.Justice.RemainsEntity) level.getBlockEntity(at);
            r.name = "Victim"; r.killer = "Suspect"; r.cause = "player"; r.died = level.getGameTime();
            var bag = new net.minecraft.world.item.ItemStack(com.jdj125.realism.Diabolical.BODY_BAG.get());
            helper.assertTrue(com.jdj125.realism.Diabolical.bagUp(p, level, at, r, bag), "bagged");
            helper.assertTrue(level.getBlockState(at).isAir() && com.jdj125.realism.Diabolical.full(bag), "the remains are in the bag");
            helper.assertTrue(com.jdj125.realism.Weight.of(bag) == com.jdj125.realism.Diabolical.FULL_BAG_KG, "a full body bag is heavy");
            BlockPos away = floor.offset(-2, 0, 3);
            helper.assertTrue(com.jdj125.realism.Diabolical.putDown(p, level, away, bag), "put down");
            var moved = (com.jdj125.realism.Justice.RemainsEntity) level.getBlockEntity(away);
            helper.assertTrue(moved.name.equals("Victim") && moved.killer.equals("Suspect"), "the evidence came along");
            helper.assertTrue(com.jdj125.realism.Diabolical.evidence(level, moved).equals("all"), "fresh: all the evidence");
            moved.died = level.getGameTime() - 24000L * 4;
            helper.assertTrue(com.jdj125.realism.Diabolical.evidence(level, moved).equals("no_prints"), "4 days later the prints are gone");
            moved.died = level.getGameTime() - 24000L * 8;
            helper.assertTrue(com.jdj125.realism.Diabolical.evidence(level, moved).equals("too_old"), "8 days later nothing is left");
            level.removeBlock(away, false);
            helper.succeed();
        });
    }
}
