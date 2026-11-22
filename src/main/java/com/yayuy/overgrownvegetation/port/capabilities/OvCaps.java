package com.yayuy.overgrownvegetation.port.capabilities;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の口(Capability)を引く所と、Forge の Capability へのつなぎ。
 *
 * <p>本流の {@code level.getCapability(Capabilities.X.BLOCK, pos, side)} は {@code OvCaps.getCapability(level, Capabilities.X.BLOCK, pos, side)}、
 * {@code stack.getCapability(Capabilities.X.ITEM)} は {@code OvCaps.getCapability(stack, Capabilities.X.ITEM)} に書き換える(port-rewrite.pl)。
 * 見つからなければ null(NeoForge と同じ)。ほかの MOD のブロックエンティティ・アイテムは Forge の Capability から引く。
 */
public final class OvCaps {

    private OvCaps() {
    }

    private record Entry<T, C>(BlockCapability<T, C> cap, ICapabilityProvider<Object, C, T> provider) {
    }

    private record ItemEntry<T, C>(ItemCapability<T, C> cap, ICapabilityProvider<ItemStack, C, T> provider) {
    }

    private record BlockEntry<T, C>(BlockCapability<T, C> cap, IBlockCapabilityProvider<T, C> provider) {
    }

    private static final Map<BlockEntityType<?>, List<Entry<?, ?>>> BY_BE = new IdentityHashMap<>();
    private static final Map<Block, List<BlockEntry<?, ?>>> BY_BLOCK = new IdentityHashMap<>();
    private static final Map<Item, List<ItemEntry<?, ?>>> BY_ITEM = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    static <T, C, BE extends BlockEntity> void addBlockEntity(BlockCapability<T, C> cap, BlockEntityType<BE> type, ICapabilityProvider<? super BE, C, T> p) {
        BY_BE.computeIfAbsent(type, k -> new ArrayList<>()).add(new Entry<>(cap, (ICapabilityProvider<Object, C, T>) p));
    }

    static <T, C> void addBlock(BlockCapability<T, C> cap, Block block, IBlockCapabilityProvider<T, C> p) {
        BY_BLOCK.computeIfAbsent(block, k -> new ArrayList<>()).add(new BlockEntry<>(cap, p));
    }

    static <T, C> void addItem(ItemCapability<T, C> cap, Item item, ICapabilityProvider<ItemStack, C, T> p) {
        BY_ITEM.computeIfAbsent(item, k -> new ArrayList<>()).add(new ItemEntry<>(cap, p));
    }

    /** {@code PortSetup} が起動のときに呼ぶ。 */
    public static void init(IEventBus modBus) {
        modBus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> modBus.post(new RegisterCapabilitiesEvent())));
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, OvCaps::attachBlockEntity);
        MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, OvCaps::attachItem);
    }

    // ── 引く ──

    @Nullable
    public static <T, C> T getCapability(Level level, BlockCapability<T, C> cap, BlockPos pos, @Nullable C side) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        return getCapability(level, cap, pos, state, be, side);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T, C> T getCapability(Level level, BlockCapability<T, C> cap, BlockPos pos, BlockState state, @Nullable BlockEntity be, @Nullable C side) {
        List<BlockEntry<?, ?>> bl = BY_BLOCK.get(state.getBlock());
        if (bl != null) {
            for (BlockEntry<?, ?> e : bl) {
                if (e.cap() == cap) {
                    T v = ((BlockEntry<T, C>) e).provider().getCapability(level, pos, state, be, side);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        if (be == null) {
            return null;
        }
        List<Entry<?, ?>> l = BY_BE.get(be.getType());
        if (l != null) {
            for (Entry<?, ?> e : l) {
                if (e.cap() == cap) {
                    return ((Entry<T, C>) e).provider().getCapability(be, side);
                }
            }
        }
        return be.getCapability(cap.forge(), side instanceof Direction d ? d : null).resolve().orElse(null);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T, C> T getCapability(ItemStack stack, ItemCapability<T, C> cap) {
        if (stack.isEmpty()) {
            return null;
        }
        List<ItemEntry<?, ?>> l = BY_ITEM.get(stack.getItem());
        if (l != null) {
            for (ItemEntry<?, ?> e : l) {
                if (e.cap() == cap) {
                    return ((ItemEntry<T, C>) e).provider().getCapability(stack, null);
                }
            }
        }
        return stack.getCapability(cap.forge()).resolve().orElse(null);
    }

    // ── Forge の Capability へのつなぎ(ほかの MOD から見えるように) ──

    private static void attachBlockEntity(AttachCapabilitiesEvent<BlockEntity> e) {
        List<Entry<?, ?>> l = BY_BE.get(e.getObject().getType());
        if (l == null) {
            return;
        }
        BlockEntity be = e.getObject();
        e.addCapability(OvergrownVegetation.id("port_caps"), new net.minecraftforge.common.capabilities.ICapabilityProvider() {
            @SuppressWarnings("unchecked")
            @Override
            public @NotNull <X> LazyOptional<X> getCapability(@NotNull Capability<X> forgeCap, @Nullable Direction side) {
                for (Entry<?, ?> en : l) {
                    if (en.cap().forge() == forgeCap) {
                        Object v = ((Entry<Object, Direction>) en).provider().getCapability(be, side);
                        return v == null ? LazyOptional.empty() : LazyOptional.of(() -> (X) v);
                    }
                }
                return LazyOptional.empty();
            }
        });
    }

    private static void attachItem(AttachCapabilitiesEvent<ItemStack> e) {
        List<ItemEntry<?, ?>> l = BY_ITEM.get(e.getObject().getItem());
        if (l == null) {
            return;
        }
        ItemStack stack = e.getObject();
        e.addCapability(OvergrownVegetation.id("port_caps"), new net.minecraftforge.common.capabilities.ICapabilityProvider() {
            @SuppressWarnings("unchecked")
            @Override
            public @NotNull <X> LazyOptional<X> getCapability(@NotNull Capability<X> forgeCap, @Nullable Direction side) {
                for (ItemEntry<?, ?> en : l) {
                    if (en.cap().forge() == forgeCap) {
                        Object v = ((ItemEntry<Object, Object>) en).provider().getCapability(stack, null);
                        return v == null ? LazyOptional.empty() : LazyOptional.of(() -> (X) v);
                    }
                }
                return LazyOptional.empty();
            }
        });
    }
}
