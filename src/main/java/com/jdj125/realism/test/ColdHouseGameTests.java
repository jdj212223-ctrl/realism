package com.jdj125.realism.test;

import com.jdj125.realism.Body;
import com.jdj125.realism.BodyEvents;
import com.jdj125.realism.ColdHouse;
import com.jdj125.realism.Fire;
import com.jdj125.realism.Hospital;
import com.jdj125.realism.Realism;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Realism 4.13: an uninsulated house lets the weather in. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class ColdHouseGameTests {

    private static void seconds(ServerPlayer p, Body b, int n) {
        for (int i = 0; i < n; i++) ColdHouse.second(p, b);
    }

    @SuppressWarnings("removal")
    @GameTest(template = "arena", timeoutTicks = 160)
    public static void coldHouseChillsYouUntilItsHeated(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> {
            try {
                ColdHouse.testWeather = 1.0;
                BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
                p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                Body b = BodyEvents.body(p);
                b.houseChill = 0;
                seconds(p, b, 30);
                helper.assertTrue(b.houseChill == 0, "outside isn't a house (" + b.houseChill + ")");

                // A plank roof over your head: now you're in a house with nothing heating it.
                for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++) helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_PLANKS);
                helper.assertTrue(ColdHouse.inHouse(p), "under a plank roof you're in a house (me=" + p.blockPosition() + " roof=" + helper.absolutePos(new BlockPos(4, 4, 4)) + " sky=" + p.level().canSeeSky(BlockPos.containing(p.getEyePosition())) + " top=" + p.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()) + ")");
                helper.assertTrue(ColdHouse.insulation(p, true) == ColdHouse.Insulation.NONE, "nothing insulates it yet");
                seconds(p, b, 160);
                helper.assertTrue(b.houseChill >= 50, "a freezing house chills you (" + b.houseChill + " w=" + ColdHouse.weather(p) + " in=" + ColdHouse.inHouse(p) + " ins=" + ColdHouse.insulation(p, true) + " same=" + (b == BodyEvents.body(p)) + " gm=" + p.gameMode.getGameModeForPlayer() + " on=" + com.jdj125.realism.RealismConfig.COLD_HOUSE.get() + " cr=" + p.isCreative() + " sp=" + p.isSpectator() + ")");
                float stamina = b.stamina;
                seconds(p, b, 5);
                helper.assertTrue(b.stamina < stamina, "shivering drains stamina");

                // Fiberglass in the walls slows the cold down a lot.
                float chill = b.houseChill = 30;
                for (int z = 2; z <= 7; z++) helper.setBlock(new BlockPos(7, 1, z), Hospital.FIBERGLASS.get());
                helper.assertTrue(ColdHouse.insulation(p, true) == ColdHouse.Insulation.WALLS, "fiberglass insulates the walls");
                seconds(p, b, 10);
                helper.assertTrue(b.houseChill > chill && b.houseChill < chill + 2, "insulated walls keep most of the cold out (" + b.houseChill + ")");

                // A burning coal heater heats the house: the chill goes away.
                helper.setBlock(new BlockPos(2, 1, 2), Fire.COAL_HEATER.get().defaultBlockState().setValue(BlockStateProperties.LIT, true));
                helper.assertTrue(ColdHouse.insulation(p, true) == ColdHouse.Insulation.HEATED, "a lit heater heats the house");
                chill = b.houseChill;
                seconds(p, b, 10);
                helper.assertTrue(b.houseChill < chill, "a heated house warms you back up");

                // A baking house: the heater doesn't cool, so it gets hot and you get thirsty.
                ColdHouse.testWeather = -1.0;
                helper.setBlock(new BlockPos(2, 1, 2), Blocks.AIR);
                for (int z = 2; z <= 7; z++) helper.setBlock(new BlockPos(7, 1, z), Blocks.AIR);
                b.houseChill = 0;
                long thirst = b.thirst;
                seconds(p, b, 160);
                helper.assertTrue(b.houseChill <= -50, "a scorching house overheats you (" + b.houseChill + ")");
                helper.assertTrue(b.thirst > thirst, "and makes you thirsty");
                helper.succeed();
            } finally {
                ColdHouse.testWeather = null;
            }
        });
    }
}
