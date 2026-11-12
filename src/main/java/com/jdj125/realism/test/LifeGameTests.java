package com.jdj125.realism.test;

import com.jdj125.realism.Ambulance;
import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Era;
import com.jdj125.realism.Health;
import com.jdj125.realism.Hospital;
import com.jdj125.realism.Justice;
import com.jdj125.realism.Power;
import com.jdj125.realism.Realism;
import com.jdj125.realism.Rescue;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 1.0.0: eras, radiation and diseases, electricity, the law, the ambulance and the patient monitor. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class LifeGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    // ---- Eras (their own batch: the year is world-wide, so nothing else may run alongside) ----

    @GameTest(template = "arena", batch = "era", timeoutTicks = 140)
    public static void inventionsMoveHistoryForward(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Era.choose(helper.getLevel().getServer(), Era.YEAR_0);
            helper.assertTrue(Era.year(p) == 0 && Era.oldTimes(p), "starts at year 0");
            Era.invent(p, new ItemStack(net.minecraft.world.item.Items.BOOK));
            helper.assertTrue(Era.year(p) == 1450, "a book invents printing (1450), got " + Era.year(p));
            Era.invent(p, new ItemStack(com.jdj125.realism.Hospital.MEDICAL_STATION_ITEM.get()));
            helper.assertFalse(Era.oldTimes(p), "a Medical Station brings modern medicine");
            Era.invent(p, new ItemStack(net.minecraft.world.item.Items.BOOK));
            helper.assertTrue(Era.year(p) == 1900, "an old invention doesn't move time back");
            // Back to normal for every other test.
            Era.choose(helper.getLevel().getServer(), Era.TODAY);
            Era.Data.get(helper.getLevel().getServer()).chosen = false;
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "era2", timeoutTicks = 140)
    public static void oldTimesAreBrutalAndFancy(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Era.choose(helper.getLevel().getServer(), Era.YEAR_0);
            BodyEvents.body(p).dressed = false;
            p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            com.jdj125.realism.test.LifeGameTests.dress(p);
            helper.assertTrue(p.getItemBySlot(EquipmentSlot.HEAD).is(Era.TOP_HAT.get()), "old times: a top hat");
            float before = p.getHealth();
            p.hurt(p.damageSources().generic(), 2);
            helper.assertTrue(before - p.getHealth() >= 2.9f, "old times hurt 1.5x, lost " + (before - p.getHealth()));
            helper.assertTrue(Era.collapseSeconds(p) < com.jdj125.realism.RealismConfig.COLLAPSE_SECONDS.get(), "less time to be saved");
            Era.choose(helper.getLevel().getServer(), Era.TODAY);
            Era.Data.get(helper.getLevel().getServer()).chosen = false;
            helper.succeed();
        });
    }

    /** Era.dress is package-private on purpose (only joins call it); tests reach it through reflection. */
    static void dress(ServerPlayer p) {
        try {
            var m = Era.class.getDeclaredMethod("dress", ServerPlayer.class);
            m.setAccessible(true);
            m.invoke(null, p);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Radiation and diseases ----

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void uraniumIsRadioactive(GameTestHelper helper) {
        withPlayer(helper, p -> {
            BlockPos ore = p.blockPosition().east();
            helper.getLevel().setBlockAndUpdate(ore, Health.URANIUM_ORE.get().defaultBlockState());
            helper.assertTrue(Health.radiationRate(p) > 0, "standing next to uranium ore gives radiation");
            Body b = BodyEvents.body(p);
            BodyEvents.second(p);
            helper.assertTrue(b.dose > 0, "the dose adds up");
            helper.getLevel().removeBlock(ore, false);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void lethalRadiationKills(GameTestHelper helper) {
        withPlayer(helper, p -> {
            BodyEvents.body(p).dose = Health.LETHAL;
            BodyEvents.second(p);
            helper.assertTrue(p.getHealth() <= 0 || Rescue.collapsed(p), "8 Sv is deadly");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void untreatedWoundsGetInfected(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.bleeding = 2;
            b.untreatedBleed = 119 * 20;
            BodyEvents.second(p);
            helper.assertTrue(b.infection > 0, "2 minutes of bleeding without a bandage: infected");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Health.ANTIBIOTICS.get()));
            p.getMainHandItem().use(p.level(), p, InteractionHand.MAIN_HAND);
            helper.assertTrue(b.infection == 0, "antibiotics cure it");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void surgeryRemovesEarlyCancer(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.cancer = 2;
            b.cancerFound = true;
            Hospital.operate(p);
            helper.assertTrue(b.cancer == 0, "stage 2 cancer, found by a scan, is removed by surgery");
            b.cancer = 3;
            b.cancerFound = true;
            Hospital.operate(p);
            helper.assertTrue(b.cancer == 3, "stage 3 needs chemotherapy, not just surgery");
            helper.succeed();
        });
    }

    // ---- Electricity ----

    @GameTest(template = "arena")
    public static void generatorPowersTheGrid(GameTestHelper helper) {
        BlockPos gen = new BlockPos(1, 2, 1);
        helper.setBlock(gen, Power.GENERATOR.get().defaultBlockState().setValue(BlockStateProperties.LIT, true));
        if (helper.getLevel().getBlockEntity(helper.absolutePos(gen)) instanceof Power.GeneratorEntity g) g.fuel = 10000;
        helper.setBlock(gen.east(), Power.CABLE.get());
        helper.setBlock(gen.east(2), Power.LAMP.get());
        helper.assertTrue(Power.powered(helper.getLevel(), helper.absolutePos(gen.east(2))), "a burning generator powers a lamp through a cable");
        // 11 lamps (110 W) on one 100 W generator: blackout.
        for (int i = 3; i <= 12; i++) helper.setBlock(gen.east(i), Power.LAMP.get());
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(Power.powered(helper.getLevel(), helper.absolutePos(gen.east(2))), "too much on the grid: blackout");
            helper.succeed();
        });
    }

    // ---- The law, the ambulance and the monitor ----

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void executionIsFinal(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.hurt(Realism.source(p, Realism.EXECUTED), Float.MAX_VALUE);
            helper.assertTrue(p.getHealth() <= 0 && !Rescue.collapsed(p), "an execution skips the collapse");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void remainsStayBehind(GameTestHelper helper) {
        withPlayer(helper, p -> {
            // Test players all start on the same spot: clear anything an earlier test left there.
            BlockPos own = helper.absolutePos(new BlockPos(3, 0, 3)); // this test's own spot (test players all start on the same one)
            p.moveTo(own.getX() + 0.5, own.getY(), own.getZ() + 0.5, 0, 0);
            Justice.leaveRemains(p);
            helper.assertTrue(helper.getLevel().getBlockState(p.blockPosition()).is(Justice.REMAINS.get()), "remains where they fell");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void ambulanceKeepsPatientsAlive(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Ambulance.Van van = Ambulance.TYPE.get().create(helper.getLevel());
            van.moveTo(p.getX(), p.getY(), p.getZ());
            helper.getLevel().addFreshEntity(van);
            Rescue.collapse(p);
            p.startRiding(van, true);
            Body b = BodyEvents.body(p);
            int before = b.collapse;
            BodyEvents.second(p);
            helper.assertTrue(before - b.collapse == 5, "in the ambulance the collapse clock runs 4x slower, went " + (before - b.collapse));
            helper.assertTrue(Hospital.MonitorEntity.heartRate(p) == 0, "a collapsed patient flatlines on the monitor");
            Rescue.revive(p);
            b.blood = 3;
            helper.assertTrue(Hospital.MonitorEntity.heartRate(p) > 100, "blood loss makes the heart race");
            van.discard();
            helper.succeed();
        });
    }
}
