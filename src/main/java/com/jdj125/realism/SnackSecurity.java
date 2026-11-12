package com.jdj125.realism;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Realism 4.6: the Snack Security System.
 * <ul>
 * <li>The Snack Cabinet: hang it up high. If it's 2 blocks above you, you have to stretch and climb for it, and you
 * might slip off the counter and crash to the floor. 3+ blocks up, you just can't reach.</li>
 * <li>The Security Camera records every fail around it (slipping for snacks, slipping on ice, someone else raiding your
 * snacks...). Right-click it to watch the latest clips. The best ones make the newspaper.</li>
 * </ul>
 */
public final class SnackSecurity {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Realism.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Realism.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Realism.MODID);

    public static final DeferredBlock<Block> SNACK_CABINET = BLOCKS.register("snack_cabinet",
            () -> new SnackCabinet(BlockBehaviour.Properties.of().strength(1.5f).sound(SoundType.WOOD)));
    public static final DeferredBlock<Block> CAMERA = BLOCKS.register("security_camera",
            () -> new Camera(BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> SNACK_CABINET_ITEM = ITEMS.registerSimpleBlockItem(SNACK_CABINET);
    public static final DeferredItem<BlockItem> CAMERA_ITEM = ITEMS.registerSimpleBlockItem(CAMERA);
    public static final Supplier<BlockEntityType<CabinetEntity>> CABINET_ENTITY = BLOCK_ENTITIES.register("snack_cabinet",
            () -> BlockEntityType.Builder.of(CabinetEntity::new, SNACK_CABINET.get()).build(null));

    public static final int CAMERA_RANGE = 10;

    private SnackSecurity() {
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                e.accept(SNACK_CABINET_ITEM.get());
                e.accept(CAMERA_ITEM.get());
            }
        });
    }

    static boolean on() {
        return RealLife.on(RealismConfig.SNACK_SECURITY);
    }

    // ---- The snack cabinet ----

    /** What happens when you reach for it: "open", "too_high", or "slipped". */
    public static String reach(ServerPlayer p, BlockPos cabinet) {
        double dy = cabinet.getY() - p.getY();
        if (!on() || dy < 2) return "open";
        if (dy >= 3) {
            p.displayClientMessage(Component.translatable("realism.snack.too_high").withStyle(ChatFormatting.GOLD), true);
            return "too_high";
        }
        // Stretching up off the counter...
        if (p.getRandom().nextFloat() < 0.35f) {
            slip(p, cabinet);
            return "slipped";
        }
        return "open";
    }

    public static void slip(ServerPlayer p, BlockPos where) {
        p.hurt(p.damageSources().fall(), 3);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2, false, false, true));
        p.setDeltaMovement(p.getDeltaMovement().add(0, -0.4, 0));
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1, 1);
        p.level().playSound(null, where, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 1, 0.7f);
        Mind.lift(p, -2);
        p.displayClientMessage(Component.translatable("realism.snack.slipped").withStyle(ChatFormatting.RED), false);
        record(p.serverLevel(), p.blockPosition(), "snack_fail", p.getGameProfile().getName());
    }

    static class SnackCabinet extends BaseEntityBlock {
        static final MapCodec<SnackCabinet> CODEC = simpleCodec(SnackCabinet::new);

        SnackCabinet(Properties props) {
            super(props);
        }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() {
            return CODEC;
        }

        @Override
        protected RenderShape getRenderShape(BlockState s) {
            return RenderShape.MODEL;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new CabinetEntity(pos, state);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (p instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof CabinetEntity c && reach(sp, pos).equals("open")) {
                sp.openMenu(c);
                level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.6f, 1.3f);
                if (!c.owner.isEmpty() && !c.owner.equals(sp.getUUID().toString()))
                    record(sp.serverLevel(), pos, "snack_thief", sp.getGameProfile().getName());
            }
            return InteractionResult.CONSUME;
        }

        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity who, ItemStack stack) {
            if (who instanceof Player p && level.getBlockEntity(pos) instanceof CabinetEntity c) c.owner = p.getUUID().toString();
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CabinetEntity c)
                net.minecraft.world.Containers.dropContents(level, pos, c);
            super.onRemove(state, level, pos, newState, moved);
        }
    }

    public static class CabinetEntity extends BaseContainerBlockEntity {
        private NonNullList<ItemStack> items = NonNullList.withSize(9, ItemStack.EMPTY);
        public String owner = "";

        public CabinetEntity(BlockPos pos, BlockState state) {
            super(CABINET_ENTITY.get(), pos, state);
        }

        @Override
        protected Component getDefaultName() {
            return Component.translatable("block.realism.snack_cabinet");
        }

        @Override
        protected NonNullList<ItemStack> getItems() {
            return items;
        }

        @Override
        protected void setItems(NonNullList<ItemStack> list) {
            items = list;
        }

        @Override
        protected AbstractContainerMenu createMenu(int id, Inventory inv) {
            return new ChestMenu(MenuType.GENERIC_9x1, id, inv, this, 1);
        }

        @Override
        public int getContainerSize() {
            return 9;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack s) {
            return s.has(net.minecraft.core.component.DataComponents.FOOD); // snacks only
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
            super.loadAdditional(tag, reg);
            items = NonNullList.withSize(9, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items, reg);
            owner = tag.getString("owner");
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
            super.saveAdditional(tag, reg);
            ContainerHelper.saveAllItems(tag, items, reg);
            tag.putString("owner", owner);
        }
    }

    // ---- The security camera ----

    /** Cameras and the clips they recorded: camera position -> clips ("game time|key|name"). */
    public static class Footage extends SavedData {
        public final java.util.Map<BlockPos, List<String>> clips = new java.util.LinkedHashMap<>();

        public static Footage get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Footage::new, Footage::load, null), "realism_footage");
        }

        static Footage load(CompoundTag tag, HolderLookup.Provider reg) {
            Footage f = new Footage();
            for (Tag t : tag.getList("c", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                List<String> l = new ArrayList<>();
                for (Tag s : c.getList("l", Tag.TAG_STRING)) l.add(s.getAsString());
                NbtUtils.readBlockPos(c, "p").ifPresent(p -> f.clips.put(p, l));
            }
            return f;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            ListTag all = new ListTag();
            clips.forEach((p, l) -> {
                CompoundTag c = new CompoundTag();
                c.put("p", NbtUtils.writeBlockPos(p));
                ListTag lt = new ListTag();
                for (String s : l) lt.add(net.minecraft.nbt.StringTag.valueOf(s));
                c.put("l", lt);
                all.add(c);
            });
            tag.put("c", all);
            return tag;
        }
    }

    /** Every camera that can see this spot records the clip. Returns how many cameras caught it. */
    public static int record(ServerLevel level, BlockPos at, String key, String name) {
        if (level.dimension() != Level.OVERWORLD) return 0;
        Footage f = Footage.get(level.getServer());
        int caught = 0;
        for (var e : f.clips.entrySet()) {
            if (e.getKey().distSqr(at) > CAMERA_RANGE * CAMERA_RANGE || !level.getBlockState(e.getKey()).is(CAMERA.get())) continue;
            e.getValue().add(level.getGameTime() + "|" + key + "|" + name);
            while (e.getValue().size() > 20) e.getValue().remove(0);
            caught++;
        }
        if (caught > 0) {
            f.setDirty();
            if (key.equals("snack_fail")) Wave3.headline(level.getServer(), "caught_on_camera", name);
        }
        return caught;
    }

    static class Camera extends Block {
        Camera(Properties props) {
            super(props);
        }

        @Override
        protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter l, BlockPos p,
                                                                     net.minecraft.world.phys.shapes.CollisionContext c) {
            return Block.box(4, 4, 4, 12, 12, 12);
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (level instanceof ServerLevel sl && level.dimension() == Level.OVERWORLD) {
                Footage f = Footage.get(sl.getServer());
                f.clips.putIfAbsent(pos.immutable(), new ArrayList<>());
                f.setDirty();
            }
        }

        @Override
        protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) {
                Footage f = Footage.get(sl.getServer());
                if (f.clips.remove(pos) != null) f.setDirty();
            }
            super.onRemove(state, level, pos, newState, moved);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player p, BlockHitResult hit) {
            if (p instanceof ServerPlayer sp) watch(sp, pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public static void watch(ServerPlayer p, BlockPos camera) {
        List<String> clips = Footage.get(p.server).clips.getOrDefault(camera, List.of());
        p.sendSystemMessage(Component.translatable("realism.snack.footage").withStyle(ChatFormatting.BOLD));
        if (clips.isEmpty()) {
            p.sendSystemMessage(Component.translatable("realism.snack.nothing").withStyle(ChatFormatting.GRAY));
            return;
        }
        long now = p.level().getGameTime();
        for (String c : clips.subList(Math.max(0, clips.size() - 6), clips.size())) {
            String[] s = c.split("\\|");
            long ago = Math.max(0, (now - Long.parseLong(s[0])) / 1200);
            p.sendSystemMessage(Component.literal("📹 ").append(Component.translatable("realism.snack.clip." + s[1], s[2], ago)));
        }
        Mind.lift(p, 3); // watching fails is funny
    }
}
