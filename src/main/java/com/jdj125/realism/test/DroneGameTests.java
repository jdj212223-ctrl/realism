package com.jdj125.realism.test;

import com.jdj125.realism.Drone;
import com.jdj125.realism.Realism;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/** Realism 4.10: drones, controllers, goggles and the propeller rule. Run with ./gradlew runGameTestServer. */
@GameTestHolder(Realism.MODID)
@PrefixGameTestTemplate(false)
public class DroneGameTests {

    @SuppressWarnings("removal")
    private static void withPlayer(GameTestHelper helper, Consumer<ServerPlayer> check) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        helper.runAfterDelay(70, () -> check.accept(p));
    }

    private static Drone.Flyer drone(GameTestHelper helper, ServerPlayer owner, Drone.Model model) {
        Drone.Flyer d = new Drone.Flyer(Drone.TYPE.get(), helper.getLevel());
        Vec3 at = helper.absoluteVec(new Vec3(3, 2, 3));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        d.owner = owner.getUUID();
        owner.moveTo(at.x + 1, at.y, at.z); // stand next to it (test players start far away)
        try {
            var m = Drone.Flyer.class.getDeclaredMethod("setModel", Drone.Model.class);
            m.setAccessible(true);
            m.invoke(d, model);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void controllersPairOnlyWithTheirDrones(GameTestHelper helper) {
        withPlayer(helper, p -> {
            ItemStack basic = new ItemStack(Drone.CONTROLLER.get()), enterprise = new ItemStack(Drone.ENTERPRISE_CONTROLLER.get());
            helper.assertTrue(Drone.pairs(basic, Drone.Model.CAMERA), "a basic controller flies a camera drone");
            helper.assertFalse(Drone.pairs(basic, Drone.Model.ENTERPRISE), "a basic controller can't fly the enterprise drone");
            helper.assertTrue(Drone.pairs(enterprise, Drone.Model.ENTERPRISE), "the enterprise controller flies the enterprise drone");
            helper.assertFalse(Drone.pairs(enterprise, Drone.Model.POCKET), "and nothing else");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void fpvNeedsGoggles(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Drone.Flyer d = drone(helper, p, Drone.Model.CINE);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Drone.CONTROLLER.get()));
            helper.assertTrue("need_goggles".equals(Drone.canFly(p, d)), "an FPV drone needs goggles");
            p.getInventory().add(new ItemStack(Drone.FPV_GOGGLES.get()));
            helper.assertTrue(Drone.canFly(p, d) == null, "with goggles you can fly it, but got: " + Drone.canFly(p, d));
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void wrongPropellersFlipOnTakeOff(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Drone.Flyer d = drone(helper, p, Drone.Model.CAMERA);
            d.markedOn = d.plainOn = false;
            helper.assertTrue("first_wrong".equals(d.installProps(false)), "plain props first land on the marked motors");
            d.installProps(true);
            helper.assertTrue(d.hasProps() && d.wrongMotors, "all props on, but on the wrong motors");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Drone.CONTROLLER.get()));
            Drone.handlePilot(p, new Drone.Pilot(d.getId(), true));
            helper.assertFalse(d.hasProps(), "it flips on take-off and breaks its propellers");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void markedPropellersFirstIsRight(GameTestHelper helper) {
        withPlayer(helper, p -> {
            Drone.Flyer d = drone(helper, p, Drone.Model.CAMERA);
            d.markedOn = d.plainOn = false;
            helper.assertTrue("first_ok".equals(d.installProps(true)), "marked props go on the marked motors");
            helper.assertTrue("done".equals(d.installProps(false)), "then the plain ones: all correct");
            helper.assertTrue(d.hasProps() && !d.wrongMotors, "ready to fly");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 120)
    public static void enterpriseDroneReachesFurther(GameTestHelper helper) {
        withPlayer(helper, p -> {
            helper.assertTrue(Drone.Model.ENTERPRISE.range > Drone.Model.CAMERA.range, "the enterprise drone has a longer range");
            helper.assertTrue(Drone.Model.POCKET.range < Drone.Model.CAMERA.range, "the pocket drone a shorter one");
            helper.assertTrue(Drone.controllerRange(new ItemStack(Drone.PRO_CONTROLLER.get())) > Drone.controllerRange(new ItemStack(Drone.CONTROLLER.get())),
                    "the pro controller reaches further than the basic one");
            helper.succeed();
        });
    }
}
