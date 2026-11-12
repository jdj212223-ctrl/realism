package com.jdj125.realism;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Realism: real life in Minecraft, built in phases. Phase 1 is the body: thirst, sleep, stamina and exhaustion,
 * muscle tears, broken legs, burns and frostbite ({@link BodyEvents}). Phase 2 is blood: bleeding, blood types,
 * bandages, tourniquets and transfusions ({@link Blood}, {@link RealismItems}). Phase 3 is the hospital: Doctor
 * villagers, surgery on the Operating Table, burn cream, splints and fiberglass insulation ({@link Hospital}).
 * Phase 4 is the mind: mood and depression, games, TV, therapy and a real-date calendar ({@link Mind}). Phase 5 is
 * tech: build your own PC from parts, operating systems, laptops and the AI data center ({@link Tech}). Phase 6 is
 * no reviving: you collapse instead of dying, and someone must defib you or call an ambulance in time ({@link Rescue}).
 * Phase 7 is real computers with apps ({@link Tech}). Phase 8 is the world: countries, money and phones ({@link World}).
 */
@Mod(Realism.MODID)
public class Realism {
    public static final String MODID = "realism";

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MODID);
    /** Not copied on death: you respawn with a fresh, healthy body. */
    public static final Supplier<AttachmentType<Body>> BODY = ATTACHMENTS.register("body", () -> AttachmentType.serializable(Body::new).build());
    /** Your blood type ("" until picked): kept for good, even after death. */
    public static final Supplier<AttachmentType<String>> BLOOD_TYPE = ATTACHMENTS.register("blood_type",
            () -> AttachmentType.builder(() -> "").serialize(com.mojang.serialization.Codec.STRING).copyOnDeath().build());
    /** Your bank balance (phase 8): kept after death, like a real bank account. */
    public static final Supplier<AttachmentType<Long>> MONEY = ATTACHMENTS.register("money",
            () -> AttachmentType.builder(() -> 0L).serialize(com.mojang.serialization.Codec.LONG).copyOnDeath().build());
    /** Set when a player died for good (no reviving); read once on respawn. */
    public static final Supplier<AttachmentType<Boolean>> GONE = ATTACHMENTS.register("gone",
            () -> AttachmentType.builder(() -> false).serialize(com.mojang.serialization.Codec.BOOL).copyOnDeath().build());

    /** 1.2.1: Realism's own creative tab with every block and item of the mod. */
    private static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final Supplier<net.minecraft.world.item.CreativeModeTab> TAB = TABS.register("realism", () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup.realism"))
            .icon(() -> new net.minecraft.world.item.ItemStack(Rescue.DEFIBRILLATOR.get()))
            .displayItems((params, out) -> net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet().stream()
                    .filter(e -> e.getKey().location().getNamespace().equals(MODID))
                    .sorted(java.util.Comparator.comparingInt(e -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(e.getValue())))
                    .forEach(e -> out.accept(e.getValue())))
            .build());

    public static final ResourceKey<DamageType> DEHYDRATION = damage("dehydration"), SLEEPLESSNESS = damage("sleeplessness"),
            EXHAUSTION = damage("exhaustion"), BURN = damage("burn"), FROSTBITE = damage("frostbite"),
            BLED_OUT = damage("bled_out"), BROKEN_NECK = damage("broken_neck"), COLLAPSED = damage("collapsed"),
            RADIATION = damage("radiation"), INFECTION = damage("infection"), CANCER = damage("cancer"), EXECUTED = damage("executed"),
            SMOKE = damage("smoke");

    public Realism(IEventBus modBus, ModContainer container) {
        ATTACHMENTS.register(modBus);
        TABS.register(modBus);
        RealismItems.register(modBus);
        Hospital.register(modBus);
        Tech.register(modBus);
        Rescue.register(modBus);
        World.register(modBus);
        Era.register(modBus);
        Health.register(modBus);
        Power.register(modBus);
        Justice.register(modBus);
        Home.register(modBus);
        Ambulance.register(modBus);
        Surgeon.register(modBus);
        Kitchen.register(modBus);
        Life.register(modBus);
        Fire.register(modBus);
        Food.register(modBus);
        Drone.register(modBus);
        Consoles.register(modBus);
        RealLife.register(modBus);
        Jobs.register(modBus);
        Cars.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, RealismConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, RealismConfig.CLIENT_SPEC);
        modBus.addListener(Realism::registerPayloads);
        NeoForge.EVENT_BUS.register(BodyEvents.class);
        NeoForge.EVENT_BUS.register(Blood.class);
        NeoForge.EVENT_BUS.register(Hospital.class);
        NeoForge.EVENT_BUS.register(Mind.class);
        NeoForge.EVENT_BUS.register(Tech.class);
        NeoForge.EVENT_BUS.register(Rescue.class);
        NeoForge.EVENT_BUS.register(World.class);
        NeoForge.EVENT_BUS.register(Era.class);
        NeoForge.EVENT_BUS.register(Health.class);
        NeoForge.EVENT_BUS.register(Justice.class);
        NeoForge.EVENT_BUS.register(Home.class);
        NeoForge.EVENT_BUS.register(Surgeon.class);
        NeoForge.EVENT_BUS.register(Kitchen.class);
        NeoForge.EVENT_BUS.register(Afterlife.class);
        NeoForge.EVENT_BUS.register(Life.class);
        NeoForge.EVENT_BUS.register(Jobs.class);
        NeoForge.EVENT_BUS.register(NoCheats.class);
        NeoForge.EVENT_BUS.register(Fire.class);
        NeoForge.EVENT_BUS.register(Food.class);
        NeoForge.EVENT_BUS.register(Consoles.class);
        NeoForge.EVENT_BUS.register(Weight.class);
        NeoForge.EVENT_BUS.register(RealLife.class);
        NeoForge.EVENT_BUS.addListener(Fire::commands);
        NeoForge.EVENT_BUS.addListener(Jobs::commands);
        NeoForge.EVENT_BUS.addListener(Era::commands);
        NeoForge.EVENT_BUS.addListener(Justice::commands);
        NeoForge.EVENT_BUS.addListener(World::commands);
        NeoForge.EVENT_BUS.addListener(RealismCommand::register);
    }

    private static ResourceKey<DamageType> damage(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, name));
    }

    public static DamageSource source(Entity victim, ResourceKey<DamageType> type) {
        return new DamageSource(victim.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(BodySync.TYPE, BodySync.CODEC, (payload, ctx) -> BodySync.latest = payload);
        r.playToClient(LifeSync.TYPE, LifeSync.CODEC, (payload, ctx) -> LifeSync.latest = payload);
        r.playToClient(Afterlife.Judgment.TYPE, Afterlife.Judgment.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> com.jdj125.realism.client.AfterlifeClient.start(payload)));
        r.playToClient(Era.OpenChoice.TYPE, Era.OpenChoice.CODEC, (payload, ctx) -> ctx.enqueueWork(com.jdj125.realism.client.EraScreen::open));
        r.playToServer(Era.Choose.TYPE, Era.Choose.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) Era.handleChoice(sp, payload.year(), payload.country());
        }));
        // Only ever runs on a client, so the client-only screen class is never loaded on a server.
        r.playToClient(Tech.OpenComputer.TYPE, Tech.OpenComputer.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> com.jdj125.realism.client.OsScreen.open(payload)));
        r.playToServer(Tech.SaveComputer.TYPE, Tech.SaveComputer.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) Tech.save(sp, payload);
        }));
        r.playToClient(Consoles.OpenConsole.TYPE, Consoles.OpenConsole.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> com.jdj125.realism.client.ConsoleScreen.open(payload)));
        r.playToServer(Tech.PlayGame.TYPE, Tech.PlayGame.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> Tech.playGame(ctx.player())));
        r.playToClient(World.OpenPhone.TYPE, World.OpenPhone.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> com.jdj125.realism.client.PhoneScreen.open(payload)));
        r.playToServer(World.CallAmbulance.TYPE, World.CallAmbulance.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                String result = World.callAmbulance(sp);
                if (!result.equals("phone.ok")) sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("realism." + result), true);
            }
        }));
        r.playToServer(World.CallFire.TYPE, World.CallFire.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                String result = Fire.callFireDepartment(sp);
                if (!result.equals("phone.ok")) sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("realism." + result), true);
            }
        }));
        r.playBidirectional(Drone.Pilot.TYPE, Drone.Pilot.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) Drone.handlePilot(sp, payload);
            else com.jdj125.realism.client.DroneClient.serverSaid(payload);
        }));
        r.playToServer(Drone.Sticks.TYPE, Drone.Sticks.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) Drone.handleSticks(sp, payload);
        }));
        r.playToServer(World.SendText.TYPE, World.SendText.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                String result = World.text(sp, payload.to(), payload.text());
                if (!result.equals("phone.ok")) sp.displayClientMessage(net.minecraft.network.chat.Component.translatable("realism." + result), true);
            }
        }));
        r.playToServer(Rescue.DefibStep.TYPE, Rescue.DefibStep.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)
                Rescue.defib(sp, payload.step(), sp.level().getEntity(payload.target()));
        }));
    }
}
