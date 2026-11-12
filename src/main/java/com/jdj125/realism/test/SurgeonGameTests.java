package com.jdj125.realism.test;

import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Era;
import com.jdj125.realism.Hospital;
import com.jdj125.realism.Justice;
import com.jdj125.realism.Kitchen;
import com.jdj125.realism.Realism;
import com.jdj125.realism.Rescue;
import com.jdj125.realism.Surgeon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Realism 1.2.0: player surgeons, dragging, knives and Czech food, murder evidence. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class SurgeonGameTests {

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = player(helper);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    private static void withTwo(GameTestHelper helper, BiConsumer<ServerPlayer, ServerPlayer> check) {
        ServerPlayer a = player(helper), b = player(helper);
        helper.runAfterDelay(70, () -> check.accept(a, b));
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void playerSurgeonFixesPatients(GameTestHelper helper) {
        withTwo(helper, (surgeon, patient) -> {
            helper.getLevel().setBlockAndUpdate(patient.blockPosition().east(), Hospital.OPERATING_TABLE.get().defaultBlockState());
            surgeon.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Surgeon.SURGERY_KIT.get()));
            surgeon.setData(Surgeon.SURGERIES, 100);
            Body b = BodyEvents.body(patient);
            boolean fixed = false;
            for (int i = 0; i < 6 && !fixed; i++) {
                b.brokenLeg = 20000;
                helper.assertTrue(Surgeon.begin(surgeon, patient).isEmpty(), "surgery starts at the table");
                fixed = Surgeon.finish(surgeon, patient) && b.brokenLeg == 0;
            }
            helper.assertTrue(fixed, "a skilled surgeon fixes the broken leg");
            helper.assertTrue(Surgeon.successChance(surgeon) >= 0.98f, "experience makes you better (up to 98%)");
            helper.getLevel().removeBlock(patient.blockPosition().east(), false);
            helper.assertFalse(Surgeon.begin(surgeon, patient).isEmpty(), "no table, no surgery");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void draggingPullsThePatient(GameTestHelper helper) {
        withTwo(helper, (dragger, patient) -> {
            patient.teleportTo(dragger.getX() + 1, dragger.getY(), dragger.getZ());
            Rescue.collapse(patient);
            helper.assertTrue(Surgeon.startDrag(dragger, patient), "you can drag a collapsed player");
            dragger.teleportTo(dragger.getX() + 2, dragger.getY(), dragger.getZ());
            Surgeon.onTick(new PlayerTickEvent.Post(dragger));
            helper.assertTrue(patient.distanceTo(dragger) < 1.6f, "the patient follows right behind, at " + patient.distanceTo(dragger));
            Rescue.revive(patient);
            Surgeon.onTick(new PlayerTickEvent.Post(dragger));
            helper.assertTrue(Surgeon.dragged(dragger) == null, "once they wake up you let go");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "era3", timeoutTicks = 140)
    public static void modernSwordsBecomeKnives(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Era.choose(helper.getLevel().getServer(), Era.YEAR_0);
            helper.assertFalse(Kitchen.modernKnives(p), "ancient times: swords stay swords");
            Era.invent(p, new ItemStack(Hospital.MEDICAL_STATION_ITEM.get()));
            helper.assertTrue(Kitchen.modernKnives(p), "from 1900 swords become knives");
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.set(DataComponents.DAMAGE, 17);
            p.getInventory().setItem(3, sword);
            Kitchen.swapSwords(p);
            ItemStack knife = p.getInventory().getItem(3);
            helper.assertTrue(knife.is(Kitchen.IRON_KNIFE.get()), "an iron sword becomes an iron knife");
            helper.assertTrue(knife.getDamageValue() == 17, "it keeps its wear");
            Era.choose(helper.getLevel().getServer(), Era.TODAY);
            Era.Data.get(helper.getLevel().getServer()).chosen = false;
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void knivesCutFood(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Kitchen.IRON_KNIFE.get()));
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Kitchen.BREAD_DUMPLING.get()));
            p.getMainHandItem().use(p.level(), p, InteractionHand.MAIN_HAND);
            helper.assertTrue(p.getInventory().countItem(Kitchen.DUMPLING_SLICE.get()) == 6, "a bread dumpling cuts into 6 slices");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void remainsHoldTheEvidence(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.attacker = java.util.UUID.randomUUID().toString();
            b.attackerName = "Suspect";
            b.attackerWeapon = "realism:iron_knife";
            b.attackerTime = p.level().getGameTime();
            b.collapseCause = "player";
            BlockPos own = helper.absolutePos(new BlockPos(3, 0, 3)); // this test's own spot (test players all start on the same one)
            p.moveTo(own.getX() + 0.5, own.getY(), own.getZ() + 0.5, 0, 0);
            Justice.leaveRemains(p);
            BlockPos at = p.blockPosition();
            helper.assertTrue(helper.getLevel().getBlockEntity(at) instanceof Justice.RemainsEntity r && r.killer.equals("Suspect")
                    && r.cause.equals("weapon") && r.weapon.equals("realism:iron_knife"), "the remains show the killer's fingerprints and the weapon");
            helper.getLevel().removeBlock(at, false);
            helper.succeed();
        });
    }
}
