package com.jdj125.realism.test;

import com.jdj125.realism.Afterlife;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.Realism;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.BiConsumer;

/** Realism 2.0: the afterlife (judgment, heaven, hell, the dark, being born again). */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class AfterlifeGameTests {

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static void withTwo(GameTestHelper helper, BiConsumer<ServerPlayer, ServerPlayer> check) {
        ServerPlayer a = player(helper), b = player(helper);
        helper.runAfterDelay(70, () -> check.accept(a, b));
    }

    @GameTest(template = "arena")
    public static void verdictsFollowTheRecord(GameTestHelper helper) {
        Afterlife.Soul murderer = new Afterlife.Soul();
        murderer.murders = 1;
        murderer.saves = 5;
        helper.assertTrue(Afterlife.verdict(murderer, 0f).equals("hell"), "a murderer goes to hell, even with good deeds");
        Afterlife.Soul butcher = new Afterlife.Soul();
        butcher.noConsent = 1;
        helper.assertTrue(Afterlife.verdict(butcher, 0f).equals("hell"), "surgery without consent sends you to hell");
        Afterlife.Soul hero = new Afterlife.Soul();
        hero.saves = 1;
        helper.assertTrue(Afterlife.verdict(hero, 0.99f).equals("heaven"), "saving a life gets you into heaven");
        Afterlife.Soul defender = new Afterlife.Soul();
        defender.defense = 1;
        helper.assertTrue(Afterlife.verdict(defender, 0.99f).equals("heaven"), "self-defense isn't a sin");
        Afterlife.Soul nobody = new Afterlife.Soul();
        helper.assertTrue(Afterlife.verdict(nobody, 0.05f).equals("heaven"), "10% luck: heaven");
        helper.assertTrue(Afterlife.verdict(nobody, 0.5f).equals("dark"), "otherwise only darkness");
        Afterlife.Soul liar = new Afterlife.Soul();
        liar.saves = 3;
        liar.lies = 1;
        helper.assertTrue(Afterlife.verdict(liar, 0f).equals("dark"), "a liar never gets into heaven, even after saving lives");
        liar.lies = 3;
        helper.assertTrue(Afterlife.verdict(liar, 0f).equals("hell"), "three lies and it's hell");
        helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void selfDefenseIsNotMurder(GameTestHelper helper) {
        withTwo(helper, (a, b) -> {
            // b attacked a first, so a killing b is self-defense.
            BodyEvents.body(a).attacker = b.getUUID().toString();
            BodyEvents.body(a).attackerTime = a.level().getGameTime();
            Afterlife.killed(a.server, a.getUUID(), b);
            Afterlife.Soul sa = Afterlife.soul(a.server, a.getUUID());
            helper.assertTrue(sa.defense == 1 && sa.murders == 0, "self-defense is recorded as such");
            // b killing a, who never attacked b: murder.
            Afterlife.killed(b.server, b.getUUID(), a);
            helper.assertTrue(Afterlife.soul(b.server, b.getUUID()).murders == 1, "an unprovoked kill is murder");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void surgeryNeedsConsent(GameTestHelper helper) {
        withTwo(helper, (surgeon, patient) -> {
            patient.setShiftKeyDown(true);
            helper.assertFalse(Afterlife.surgery(surgeon, patient), "a crouching patient said yes");
            patient.setShiftKeyDown(false);
            helper.assertTrue(Afterlife.surgery(surgeon, patient), "a standing, conscious patient didn't");
            helper.assertTrue(Afterlife.soul(surgeon.server, surgeon.getUUID()).noConsent == 1, "and it's on the record");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void hellThenBornAgain(GameTestHelper helper) {
        withTwo(helper, (p, unused) -> {
            Afterlife.Soul s = Afterlife.soul(p.server, p.getUUID());
            s.murders = 2;
            Afterlife.judge(p);
            helper.assertTrue(s.place.equals("judgment:hell"), "the judgment starts");
            helper.assertTrue(Afterlife.inAfterlife(p), "and the soul is out of the living world");
            boolean hasHell = p.server.getLevel(Afterlife.HELL) != null; // the GameTest server has no datapack dimensions
            Afterlife.arrive(p, s, "hell");
            if (hasHell) {
                helper.assertTrue(p.level().dimension() == Afterlife.HELL, "a murderer ends up in hell");
                helper.assertTrue(s.place.equals("hell"), "and stays there for a while");
            }
            Afterlife.reborn(p, s);
            helper.assertTrue(p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD, "born again in the overworld");
            helper.assertTrue(s.place.isEmpty() && s.murders == 0, "a new person with a clean record");
            helper.assertTrue(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "playing again");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void heavenAndDarkExist(GameTestHelper helper) {
        withTwo(helper, (a, b) -> {
            if (a.server.getLevel(Afterlife.HEAVEN) == null) {
                // The GameTest server only has the vanilla dimensions: then souls are simply born again.
                Afterlife.arrive(a, Afterlife.soul(a.server, a.getUUID()), "heaven");
                helper.assertTrue(!Afterlife.inAfterlife(a) && a.level().dimension() == net.minecraft.world.level.Level.OVERWORLD, "no heaven loaded: born again");
                helper.succeed();
                return;
            }
            Afterlife.arrive(a, Afterlife.soul(a.server, a.getUUID()), "heaven");
            helper.assertTrue(a.level().dimension() == Afterlife.HEAVEN, "heaven is a real place");
            Afterlife.arrive(b, Afterlife.soul(b.server, b.getUUID()), "dark");
            helper.assertTrue(b.level().dimension() == Afterlife.DARK, "so is the dark");
            Afterlife.reborn(a, Afterlife.soul(a.server, a.getUUID()));
            Afterlife.reborn(b, Afterlife.soul(b.server, b.getUUID()));
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 140)
    public static void denyingWhatYouDidIsALie(GameTestHelper helper) {
        withTwo(helper, (a, b) -> {
            helper.assertFalse(Afterlife.said(a, "it wasn't me!"), "an innocent person isn't lying");
            Afterlife.soul(a.server, a.getUUID()).murders = 1;
            helper.assertFalse(Afterlife.said(a, "nice weather today"), "small talk isn't a lie");
            helper.assertTrue(Afterlife.said(a, "It wasn't me, I'm innocent"), "a murderer saying it wasn't them is lying");
            helper.assertTrue(Afterlife.said(a, "nebyl jsem to"), "in Czech too");
            helper.assertTrue(Afterlife.soul(a.server, a.getUUID()).lies == 2, "both lies are written down");
            helper.succeed();
        });
    }
}
