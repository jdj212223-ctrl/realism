package com.jdj125.realism.test;

import com.jdj125.realism.Bullying;
import com.jdj125.realism.Realism;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/** Realism 4.8: bullying, and what makes it stop. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class BullyingGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    /** A villager with a given job whose hidden attitude roll (UUID hash mod 10) is {@code roll}. */
    private static Villager villager(GameTestHelper helper, VillagerProfession job, int roll) {
        Villager v = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        v.setVillagerData(v.getVillagerData().setProfession(job));
        for (long i = 0; ; i++) {
            UUID id = new UUID(i, i * 31 + 7);
            if (Math.floorMod(id.hashCode(), 10) == roll) {
                v.setUUID(id);
                return v;
            }
        }
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void helplineAlwaysStopsIt(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Bullying.start(p, villager(helper, VillagerProfession.NONE, 0));
            helper.assertTrue(Bullying.bullied(p), "the kid should start bullying");
            p.getInventory().add(new ItemStack(com.jdj125.realism.World.PHONE.get())); // the helpline needs a working phone
            Bullying.helpline(p);
            helper.assertFalse(Bullying.bullied(p), "the helpline always makes it stop");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void stolenStuffComesBack(GameTestHelper helper) {
        withPlayer(helper, p -> {
            // A villager with its own real ID (so the game can find it again to give the stuff back).
            Villager bully = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
            Bullying.start(p, bully);
            bully.getInventory().addItem(new ItemStack(Items.DIAMOND));
            Bullying.stop(p, "helpline.help");
            helper.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 1, "the bully should give back what they stole");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void mostTeachersDontHelp(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Bullying.start(p, villager(helper, VillagerProfession.NONE, 0));
            Villager ignores = villager(helper, VillagerProfession.LIBRARIAN, 0);
            helper.assertTrue(Bullying.role(ignores) == Bullying.Role.TEACHER, "a librarian is a teacher");
            helper.assertTrue(Bullying.tellAdult(p, ignores) == Bullying.Reaction.IGNORE, "this teacher just says 'ignore them'");
            helper.assertTrue(Bullying.bullied(p), "so the bullying goes on");
            Bullying.data(p).tellCooldown = 0;
            Villager helps = villager(helper, VillagerProfession.CLERIC, 9);
            helper.assertTrue(Bullying.tellAdult(p, helps) == Bullying.Reaction.HELP, "this counselor actually helps");
            helper.assertFalse(Bullying.bullied(p), "and then it stops");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void teachersAndCounselorsAreMostlyUnhelpful(GameTestHelper helper) {
        withPlayer(helper, p -> {
            int teacherHelps = 0, adultHelps = 0;
            for (int roll = 0; roll < 10; roll++) {
                if (Bullying.attitude(villager(helper, VillagerProfession.LIBRARIAN, roll)) == Bullying.Reaction.HELP) teacherHelps++;
                if (Bullying.attitude(villager(helper, VillagerProfession.FARMER, roll)) == Bullying.Reaction.HELP) adultHelps++;
            }
            helper.assertTrue(teacherHelps == 2, "2 in 10 teachers help");
            helper.assertTrue(adultHelps == 5, "half of other grown-ups help");
            helper.succeed();
        });
    }
}
