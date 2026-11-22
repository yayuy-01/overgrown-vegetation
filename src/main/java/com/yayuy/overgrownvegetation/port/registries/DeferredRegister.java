package com.yayuy.overgrownvegetation.port.registries;

import com.yayuy.overgrownvegetation.port.attachment.AttachmentType;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code net.neoforged.neoforge.registries.DeferredRegister} と同じ名前・同じ使い方の物。
 *
 * <ul>
 *   <li>バニラの登録簿({@code Registries.BLOCK} など)は、中で Forge の {@link net.minecraftforge.registries.DeferredRegister} に渡す。</li>
 *   <li>登録簿の無い物({@link NeoForgeRegistries.Keys#ATTACHMENT_TYPES}・{@link #createDataComponents})は、この中だけで持つ。
 *       {@link #register(IEventBus)} のときに全部作り、名前を結び付ける(保存の鍵になる)。</li>
 * </ul>
 * 本流のコードは import を {@code com.yayuy.overgrownvegetation.port.registries.*} に変えるだけで同じに書ける(port-tools/imports.sed)。
 */
public class DeferredRegister<T> {

    protected final ResourceKey<? extends Registry<T>> registryKey;
    protected final String namespace;
    @Nullable
    private final net.minecraftforge.registries.DeferredRegister<T> forge;
    @Nullable
    private final BiConsumer<ResourceLocation, Object> localBinder;
    private final List<DeferredHolder<T, ? extends T>> entries = new ArrayList<>();
    private boolean registered;

    protected DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String namespace) {
        this.registryKey = registryKey;
        this.namespace = namespace;
        this.localBinder = localBinderFor(registryKey);
        this.forge = localBinder == null ? net.minecraftforge.registries.DeferredRegister.create(registryKey, namespace) : null;
    }

    @Nullable
    private static BiConsumer<ResourceLocation, Object> localBinderFor(ResourceKey<?> key) {
        if (key == NeoForgeRegistries.Keys.ATTACHMENT_TYPES) {
            return (id, v) -> ((AttachmentType<?>) v).bind(id);
        }
        if (key == NeoForgeRegistries.Keys.DATA_COMPONENT_TYPE) {
            return (id, v) -> ((DataComponentType<?>) v).bind(id);
        }
        return null;
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        return new DeferredRegister<>(key, namespace);
    }

    @SuppressWarnings("unchecked")
    public static <T> DeferredRegister<T> create(Registry<T> registry, String namespace) {
        return new DeferredRegister<>((ResourceKey<? extends Registry<T>>) registry.key(), namespace);
    }

    public static Blocks createBlocks(String namespace) {
        return new Blocks(namespace);
    }

    public static Items createItems(String namespace) {
        return new Items(namespace);
    }

    /** NeoForge の {@code createDataComponents(Registries.DATA_COMPONENT_TYPE, modid)}。1.20.1 にはその登録簿が無いので、鍵は受け取るだけ。 */
    public static DataComponents createDataComponents(Object ignoredRegistryKey, String namespace) {
        return new DataComponents(namespace);
    }

    public static DataComponents createDataComponents(String namespace) {
        return new DataComponents(namespace);
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> sup) {
        return register(name, id -> sup.get());
    }

    @SuppressWarnings("unchecked")
    public <I extends T> DeferredHolder<T, I> register(String name, Function<ResourceLocation, ? extends I> func) {
        if (registered && localBinder != null) {
            throw new IllegalStateException("late registration: " + name);
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, name);
        ResourceKey<T> key = ResourceKey.create(registryKey, id);
        DeferredHolder<T, I> holder;
        if (forge != null) {
            RegistryObject<I> ro = forge.register(name, () -> func.apply(id));
            holder = createHolder(key, ro);
        } else {
            BiConsumer<ResourceLocation, Object> binder = localBinder;
            holder = new DeferredHolder<>(key, () -> {
                I v = func.apply(id);
                binder.accept(id, v);
                return v;
            });
        }
        entries.add(holder);
        return holder;
    }

    /** 登録簿の型に合った入れ物を作る(Blocks・Items が上書きする)。 */
    protected <I extends T> DeferredHolder<T, I> createHolder(ResourceKey<T> key, RegistryObject<I> ro) {
        return new DeferredHolder<>(key, ro);
    }

    public void register(IEventBus bus) {
        if (registered) {
            throw new IllegalStateException("already registered: " + registryKey);
        }
        registered = true;
        if (forge != null) {
            forge.register(bus);
        } else {
            // 登録簿の無い物は今ここで作る(名前を結び付け、保存のときに id から引けるようにする)
            for (DeferredHolder<T, ? extends T> h : entries) {
                h.get();
            }
        }
    }

    public Collection<DeferredHolder<T, ? extends T>> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public ResourceKey<? extends Registry<T>> getRegistryKey() {
        return registryKey;
    }

    public ResourceLocation getRegistryName() {
        return registryKey.location();
    }

    public String getNamespace() {
        return namespace;
    }

    // ── ブロック ──
    public static class Blocks extends DeferredRegister<Block> {
        protected Blocks(String namespace) {
            super(Registries.BLOCK, namespace);
        }

        @Override
        public <B extends Block> DeferredBlock<B> register(String name, Supplier<? extends B> sup) {
            return (DeferredBlock<B>) super.register(name, sup);
        }

        @Override
        public <B extends Block> DeferredBlock<B> register(String name, Function<ResourceLocation, ? extends B> func) {
            return (DeferredBlock<B>) super.register(name, func);
        }

        @Override
        protected <I extends Block> DeferredHolder<Block, I> createHolder(ResourceKey<Block> key, RegistryObject<I> ro) {
            return new DeferredBlock<>(key, ro);
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> func, BlockBehaviour.Properties props) {
            return register(name, () -> func.apply(props));
        }

        public <B extends Block> DeferredBlock<B> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends B> func) {
            return registerBlock(name, func, BlockBehaviour.Properties.of());
        }

        public DeferredBlock<Block> registerSimpleBlock(String name, BlockBehaviour.Properties props) {
            return registerBlock(name, Block::new, props);
        }

        public DeferredBlock<Block> registerSimpleBlock(String name) {
            return registerSimpleBlock(name, BlockBehaviour.Properties.of());
        }
    }

    // ── アイテム ──
    public static class Items extends DeferredRegister<Item> {
        protected Items(String namespace) {
            super(Registries.ITEM, namespace);
        }

        @Override
        public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> sup) {
            return (DeferredItem<I>) super.register(name, sup);
        }

        @Override
        public <I extends Item> DeferredItem<I> register(String name, Function<ResourceLocation, ? extends I> func) {
            return (DeferredItem<I>) super.register(name, func);
        }

        @Override
        protected <I extends Item> DeferredHolder<Item, I> createHolder(ResourceKey<Item> key, RegistryObject<I> ro) {
            return new DeferredItem<>(key, ro);
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> func, Item.Properties props) {
            return register(name, () -> func.apply(props));
        }

        public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> func) {
            return registerItem(name, func, new Item.Properties());
        }

        public DeferredItem<Item> registerSimpleItem(String name, Item.Properties props) {
            return registerItem(name, Item::new, props);
        }

        public DeferredItem<Item> registerSimpleItem(String name) {
            return registerSimpleItem(name, new Item.Properties());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block, Item.Properties props) {
            return register(name, () -> new BlockItem(block.get(), props));
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block) {
            return registerSimpleBlockItem(name, block, new Item.Properties());
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(DeferredHolder<Block, ?> block, Item.Properties props) {
            return registerSimpleBlockItem(block.getId().getPath(), block, props);
        }

        public DeferredItem<BlockItem> registerSimpleBlockItem(DeferredHolder<Block, ?> block) {
            return registerSimpleBlockItem(block, new Item.Properties());
        }
    }

    // ── データコンポーネント(1.20.1 ではアイテムの NBT に書く。port.component) ──
    public static class DataComponents extends DeferredRegister<DataComponentType<?>> {
        protected DataComponents(String namespace) {
            super(NeoForgeRegistries.Keys.DATA_COMPONENT_TYPE, namespace);
        }

        public <D> DeferredHolder<DataComponentType<?>, DataComponentType<D>> registerComponentType(
                String name, UnaryOperator<DataComponentType.Builder<D>> builder) {
            return register(name, () -> builder.apply(DataComponentType.builder()).build());
        }
    }
}
