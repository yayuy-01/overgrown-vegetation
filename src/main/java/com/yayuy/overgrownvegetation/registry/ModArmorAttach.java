package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachmentItem;
import com.yayuy.overgrownvegetation.gear.attach.AttachBenchBlock;
import com.yayuy.overgrownvegetation.gear.attach.AttachBenchMenu;
import com.yayuy.overgrownvegetation.gear.attach.BeltPouchMenu;
import com.yayuy.overgrownvegetation.gear.attach.MouthClothItem;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import com.yayuy.overgrownvegetation.port.component.ItemContainerContents;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.extensions.IForgeMenuType;
import com.yayuy.overgrownvegetation.port.registries.DeferredBlock;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

/**
 * <b>防具の付け物</b>(2026-11-21 夜)の登録: 付け物の枠のコンポーネント・付け物 4 種・付け物の台・画面 2 つ。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ({@link ModDecon} と同じ作り)。
 * 仕様は {@link com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments}。名前・作り方・働きはどれも【仮】。
 */
public final class ModArmorAttach {

    private ModArmorAttach() {
    }

    /** MOD の構築子から呼ぶ。 */
    public static void init() {
    }

    // ---- コンポーネント ----

    /** 防具に付いた付け物(枠 3 つ。空いた枠は空のスタック)。クライアントへも送る(体の見た目・説明)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> ATTACHMENTS =
            ModDataComponents.COMPONENTS.registerComponentType("armor_attachments", b -> b
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC)
                    .cacheEncoding());

    /** 収納付きベルトの小物入れの中身。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> BELT_CONTENTS =
            ModDataComponents.COMPONENTS.registerComponentType("belt_contents", b -> b
                    .persistent(ItemContainerContents.CODEC)
                    .networkSynchronized(ItemContainerContents.STREAM_CODEC)
                    .cacheEncoding());

    // ---- 付け物 4 種 ----

    /** 口覆いの布(頭。防護マスクの働き)。 */
    public static final DeferredItem<MouthClothItem> MOUTH_CLOTH = ModItems.ITEMS.registerItem(
            "mouth_cloth", MouthClothItem::new, new Item.Properties().stacksTo(1));
    /** 収納付きベルト(腰 = 脚の防具。小物入れ)。 */
    public static final DeferredItem<ArmorAttachmentItem> STORAGE_BELT = ModItems.ITEMS.registerItem(
            "storage_belt", ArmorAttachmentItem::new, new Item.Properties().stacksTo(1));
    /** 樹皮の当て板(すべての部位。防御 +1)。 */
    public static final DeferredItem<ArmorAttachmentItem> BARK_PLATE = ModItems.ITEMS.registerItem(
            "bark_plate", ArmorAttachmentItem::new, new Item.Properties().stacksTo(16));
    /** 導晶装飾の布(腰 = 脚の防具。地脈の力の減りを 1 割減らす)。 */
    public static final DeferredItem<ArmorAttachmentItem> CONDUIT_SASH = ModItems.ITEMS.registerItem(
            "conduit_sash", ArmorAttachmentItem::new, new Item.Properties().stacksTo(1));

    // ---- 付け物の台 ----

    public static final DeferredBlock<AttachBenchBlock> BENCH = ModBlocks.BLOCKS.registerBlock(
            "attachment_bench", AttachBenchBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .ignitedByLava());

    public static final DeferredItem<BlockItem> BENCH_ITEM = ModItems.ITEMS.registerSimpleBlockItem(BENCH);

    // ---- 画面 ----

    public static final DeferredHolder<MenuType<?>, MenuType<AttachBenchMenu>> BENCH_MENU = ModMenus.MENUS.register(
            "attachment_bench", () -> new MenuType<>(AttachBenchMenu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<BeltPouchMenu>> BELT_MENU = ModMenus.MENUS.register(
            "belt_pouch", () -> IForgeMenuType.create((id, inv, buf) -> BeltPouchMenu.client(id, inv,
                    // 1.20.1: Forge の画面の通信は FriendlyByteBuf。本流の受け手(RegistryFriendlyByteBuf)へ包んで渡す
                    new com.yayuy.overgrownvegetation.port.network.RegistryFriendlyByteBuf(buf, () -> inv.player.level().registryAccess()))));

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(BENCH_ITEM.get());
        output.accept(MOUTH_CLOTH.get());
        output.accept(STORAGE_BELT.get());
        output.accept(BARK_PLATE.get());
        output.accept(CONDUIT_SASH.get());
    }
}
