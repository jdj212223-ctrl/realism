package com.jdj125.realism.test;

import com.jdj125.realism.Blood;
import com.jdj125.realism.Body;
import com.jdj125.realism.Hospital;
import com.jdj125.realism.Mind;
import com.jdj125.realism.Tech;
import com.jdj125.realism.Rescue;
import com.jdj125.realism.World;
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
            helper.assertTrue((p.getHealth() <= 0 || Rescue.collapsed(p)), "3 days without water should kill");
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
            helper.assertTrue((p.getHealth() <= 0 || Rescue.collapsed(p)), "11 days without sleep should kill");
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
            helper.assertTrue((p.getHealth() <= 0 || Rescue.collapsed(p)), "under 2 liters you bleed out");
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
    public static void surgeryFixesSeriousInjuries(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.brokenLeg = 99999;
            b.armTear = 99999;
            b.badTear = true;
            b.burn = 99999;
            b.burnDegree = 3;
            b.frostbite = 99999;
            b.bleeding = 3;
            b.blood = 3.0f;
            Hospital.operate(p);
            helper.assertTrue(b.brokenLeg == 0 && b.armTear == 0 && !b.badTear && b.burn == 0 && b.frostbite == 0 && b.bleeding == 0,
                    "surgery fixes every serious injury");
            helper.assertTrue(b.blood > 3.0f, "surgery gives some blood back");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void brokenLegStopsJumpingInsteadOfLaunching(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            double normal = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
            b.brokenLeg = 99999;
            BodyEvents.second(p);
            double hurt = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
            helper.assertTrue(hurt <= 0.0001, "a broken leg means no jumping at all, got jump strength " + hurt);
            helper.assertTrue(p.getEffect(net.minecraft.world.effect.MobEffects.JUMP) == null, "no Jump Boost effect (it launched players)");
            b.brokenLeg = 0;
            BodyEvents.second(p);
            double healed = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
            helper.assertTrue(Math.abs(healed - normal) < 0.0001, "jumping comes back when the leg heals, got " + healed);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void lowMoodSlowsButNeverHurts(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.mood = 5;
            float health = p.getHealth();
            second(p);
            helper.assertTrue(b.moodStage == 2, "very low mood is depression, got stage " + b.moodStage);
            helper.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "depression slows you");
            helper.assertTrue(p.getHealth() >= health, "mood never hurts you by itself");
            Mind.lift(p, 40);
            helper.assertTrue(b.mood >= 44 && b.moodStage == 0, "therapy-sized lift brings you back up, got " + b.mood);
            Mind.lift(p, 500);
            helper.assertTrue(b.mood == 100, "mood tops out at 100");
            helper.succeed();
        });
    }

    @GameTest(template = "arena")
    public static void pcNeedsEveryPartAndGpuSagsWithoutBracket(GameTestHelper helper) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(2, 1, 2);
        helper.setBlock(pos, Tech.PC_CASE.get());
        Tech.PcCaseEntity pc = (Tech.PcCaseEntity) helper.getBlockEntity(pos);
        for (Tech.Part p : Tech.Part.values()) if (p != Tech.Part.GPU_BRACKET && p != Tech.Part.GPU) pc.install(p);
        helper.assertFalse(pc.complete(), "no GPU = can't boot");
        pc.install(Tech.Part.GPU);
        helper.assertTrue(pc.complete(), "all parts in = it boots");
        helper.assertFalse(pc.install(Tech.Part.GPU), "can't install the same part twice");
        helper.assertTrue(pc.boot().equals("sag") && pc.boot().equals("sag"), "no bracket: the GPU sags");
        helper.assertTrue(pc.boot().equals("cracked") && !pc.has(Tech.Part.GPU), "third boot without a bracket cracks the GPU");
        pc.install(Tech.Part.GPU);
        pc.install(Tech.Part.GPU_BRACKET);
        helper.assertTrue(pc.boot().equals("ok") && pc.boot().equals("ok") && pc.boot().equals("ok"), "with a bracket the GPU is safe");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void youCollapseInsteadOfDying(GameTestHelper helper) {
        withPlayer(helper, p -> {
            p.hurt(p.damageSources().generic(), 1000);
            helper.assertTrue(p.isAlive() && Rescue.collapsed(p), "a deadly hit makes you collapse, not die");
            helper.assertTrue(p.getForcedPose() == net.minecraft.world.entity.Pose.SLEEPING, "collapsed players lie down");
            p.hurt(p.damageSources().generic(), 1000);
            helper.assertTrue(p.isAlive(), "nothing hurts you while collapsed: the clock decides");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void defibInTheRightOrderSavesYou(GameTestHelper helper) {
        withPlayer(helper, patient -> {
            @SuppressWarnings("removal") ServerPlayer medic = helper.makeMockServerPlayerInLevel();
            medic.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(Rescue.DEFIBRILLATOR.get()));
            medic.teleportTo(patient.getX() + 1, patient.getY(), patient.getZ());
            Rescue.collapse(patient);
            Rescue.defib(medic, 3, patient);
            helper.assertTrue(Rescue.collapsed(patient), "shock before charging does nothing");
            Rescue.defib(medic, 1, patient);
            Rescue.defib(medic, 2, patient);
            Rescue.defib(medic, 3, patient);
            helper.assertFalse(Rescue.collapsed(patient), "charge, clear, shock brings them back");
            helper.assertTrue(patient.getHealth() > 1 && patient.getForcedPose() == null, "revived and standing");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void runningOutOfTimeIsForGood(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Rescue.collapse(p);
            BodyEvents.body(p).collapse = 20;
            second(p);
            helper.assertTrue(p.getHealth() <= 0, "when the clock runs out you really die");
            helper.assertTrue(p.getData(Realism.GONE), "and you're marked gone for good");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void brokenNeckIsSeriousButSurvivable(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Body b = BodyEvents.body(p);
            b.brokenNeck = true;
            b.paralysed = true;
            second(p);
            helper.assertTrue(p.getHealth() > 0 && !Rescue.collapsed(p), "a broken neck doesn't kill you by itself");
            helper.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN), "but you can barely move");
            Hospital.operate(p);
            helper.assertTrue(!b.brokenNeck && !b.paralysed, "surgery fixes the neck and the spinal cord");
            helper.succeed();
        });
    }

    @GameTest(template = "arena")
    public static void readyBuiltMacsBootAndDiscsInstallApps(GameTestHelper helper) {
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(3, 1, 3);
        helper.setBlock(pos, Tech.MODEL_BLOCKS.get(0).get());
        Tech.PcCaseEntity pc = (Tech.PcCaseEntity) helper.getBlockEntity(pos);
        helper.assertTrue(pc.complete() && pc.has(Tech.Part.GPU_BRACKET), "a ready-built computer has every part");
        helper.assertTrue(pc.hasApp(Tech.App.NOTES) && pc.hasApp(Tech.App.CALC) && pc.hasApp(Tech.App.TERMINAL), "it comes with Notes, Calculator, Terminal");
        helper.assertFalse(pc.hasApp(Tech.App.SNAKE), "Snake needs its disc");
        helper.assertTrue(Tech.MODEL_BLOCKS.size() == 13, "13 computers from 1984 to the M6");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void countriesTaxMoneyAndPhonesNeedSignal(GameTestHelper helper) {
        withPlayer(helper, leader -> {
            @SuppressWarnings("removal") ServerPlayer friend = helper.makeMockServerPlayerInLevel();
            World.Data data = World.Data.get(leader.serverLevel());
            data.countries.keySet().removeIf(k -> k.startsWith("testland")); // leftovers from an earlier failed run
            String name = "Testland" + leader.getId();
            World.Country c = new World.Country(name, leader.getUUID());
            c.tax = 20;
            c.flags.add(leader.blockPosition());
            data.countries.put(name.toLowerCase(), c);
            helper.assertTrue(data.countryAt(leader.blockPosition()) == c, "the flag claims the land around it (found " + (data.countryAt(leader.blockPosition()) == null ? "none" : data.countryAt(leader.blockPosition()).name + " flags=" + data.countryAt(leader.blockPosition()).flags) + ", countries=" + data.countries.keySet() + ")");
            helper.assertTrue(data.countryAt(leader.blockPosition().offset(200, 0, 200)) != c, "but not far away");
            long kept = World.earn(leader, 100);
            helper.assertTrue(kept == 80 && c.treasury == 20 && World.balance(leader) == 80, "20% tax goes to the treasury");
            helper.assertTrue(World.pay(leader, friend, 30) && World.balance(friend) == 24 && World.balance(leader) == 50 && c.treasury == 26,
                    "paying moves money (4.3: minus 20% sales tax to the treasury)");
            helper.assertFalse(World.pay(leader, friend, 999), "you can't pay more than you have");
            helper.assertFalse(data.signal(leader.blockPosition().offset(5000, 0, 5000)), "no tower = no signal");
            data.towers.add(leader.blockPosition());
            helper.assertTrue(data.signal(leader.blockPosition()), "near a tower = signal");
            data.towers.remove(leader.blockPosition());
            data.countries.remove(name.toLowerCase());
            helper.succeed();
        });
    }

    @GameTest(template = "hospital", timeoutTicks = 60)
    public static void hospitalBuildingHasEverything(GameTestHelper helper) {
        helper.assertBlockPresent(Hospital.OPERATING_TABLE.get(), 6, 2, 6); // test structures sit 1 block higher
        helper.assertBlockPresent(Hospital.MEDICAL_STATION.get(), 6, 2, 9);
        helper.assertBlockPresent(Rescue.AED_HOLDER.get(), 1, 2, 1);
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.CHEST, 10, 2, 8);
        helper.succeedWhen(() -> helper.assertEntityPresent(net.minecraft.world.entity.EntityType.VILLAGER));
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void ambulanceCallNeedsAPhoneAndSignalThenBringsYouToTheTable(GameTestHelper helper) {
        withPlayer(helper, patient -> {
            @SuppressWarnings("removal") ServerPlayer caller = helper.makeMockServerPlayerInLevel();
            caller.teleportTo(patient.getX() + 2, patient.getY(), patient.getZ());
            Rescue.collapse(patient);
            helper.assertTrue(World.callAmbulance(caller).equals("phone.none"), "no phone, no call");
            caller.getInventory().add(new net.minecraft.world.item.ItemStack(World.PHONE.get()));
            World.Data data = World.Data.get(caller.serverLevel());
            helper.assertTrue(World.callAmbulance(caller).equals("phone.no_signal") || data.signal(caller.blockPosition()), "no tower, no signal");
            net.minecraft.core.BlockPos tower = caller.blockPosition();
            data.towers.add(tower);
            // Mock players stand at the world's center, not in the test area: put the table next to them.
            net.minecraft.core.BlockPos table = patient.blockPosition().offset(3, 0, 3);
            helper.getLevel().setBlock(table, Hospital.OPERATING_TABLE.get().defaultBlockState(), 3);
            Rescue.Tables.get(helper.getLevel()).add(table);
            String r = World.callAmbulance(caller);
            helper.assertTrue(r.equals("phone.ok"), "phone + signal + table = the ambulance comes, got " + r);
            // The nearest table wins (the test world can hold tables from earlier runs), so just check they're on one.
            helper.assertTrue(helper.getLevel().getBlockState(patient.blockPosition().below()).is(Hospital.OPERATING_TABLE.get()),
                    "the patient is taken onto an Operating Table");
            data.towers.remove(tower);
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
            helper.assertTrue((p.getHealth() <= 0 || Rescue.collapsed(p)), "pushing on with no stamina should end in exhaustion");
            helper.succeed();
        });
    }
}
