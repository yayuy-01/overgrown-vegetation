package com.yayuy.overgrownvegetation.ally;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.EnumMap;
import java.util.Map;

/**
 * 同盟軍の兵士の種類(2026-10-01)。兵種ごとに別のエンティティの種類(スポーンエッグと体力などの属性を分けるため)、中身は {@link SoldierEntity}。
 * 共有の {@link ModEntities}・{@link ModItems} を太らせないよう、DeferredRegister だけ借りる({@link #init()} は {@link AllyItems#init()} から、
 * 登録より前に呼ばれる)。区分は MISC(村人・ゴーレムと同じ。MONSTER にすると TD の片付けとピースフルの敵の判定に巻き込まれる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AllyEntities {

    /**
     * 同盟軍(兵士 4 種)のタグ {@code overgrown_vegetation:allies}。過繁茂側の捕食種(グループ 3)が侵入者として狙う
     * ({@code Predation#isIntruder})。ほかのグループはもともと「グループに属さない相手」を狙うので兵士も標的になる。
     */
    public static final TagKey<EntityType<?>> ALLIES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "allies"));
    /** 兵士の銃の傷({@code damage_type/soldier_bullet.json})。盾を素通りする(タグ {@code #minecraft:bypasses_shield}、ユーザー指定 2026-10-02「弾は盾で防げないように」)。 */
    public static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "soldier_bullet"));

    private static final Map<SoldierKind, DeferredHolder<EntityType<?>, EntityType<SoldierEntity>>> TYPES = new EnumMap<>(SoldierKind.class);
    private static final Map<SoldierKind, DeferredItem<Item>> EGGS = new EnumMap<>(SoldierKind.class);

    /** スポーンエッグの色(地・斑点)。 */
    private static final int[][] EGG_COLORS = {
            {0xA59473, 0x5E5A3F}, // 歩兵: OCP
            {0x7E7552, 0xB03030}, // 砲兵: 赤の兵科章
            {0x6F6B4B, 0xE8D000}, // 工兵: 黄色の反射ベルト
            {0x1E1E22, 0x4A4A55}, // エリート兵: 黒
    };

    static {
        for (SoldierKind kind : SoldierKind.values()) {
            String id = "soldier_" + kind.id;
            DeferredHolder<EntityType<?>, EntityType<SoldierEntity>> type = ModEntities.ENTITY_TYPES.register(id,
                    () -> EntityType.Builder.<SoldierEntity>of((t, level) -> new SoldierEntity(t, level, kind), MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .eyeHeight(1.62F)
                            .clientTrackingRange(10)
                            .build(id));
            TYPES.put(kind, type);
            int[] c = EGG_COLORS[kind.ordinal()];
            EGGS.put(kind, ModItems.ITEMS.register(id + "_spawn_egg",
                    () -> new DeferredSpawnEggItem(type, c[0], c[1], new Item.Properties())));
        }
    }

    public static void init() {
    }

    /** 兵種のエンティティの種類。 */
    public static EntityType<SoldierEntity> type(SoldierKind kind) {
        return TYPES.get(kind).get();
    }

    /** 兵種のスポーンエッグ(実験的要素のアイテムの一覧 {@code difficulty.ExperimentalFeatures} が使う)。 */
    public static Item spawnEgg(SoldierKind kind) {
        return EGGS.get(kind).get();
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        for (SoldierKind kind : SoldierKind.values()) {
            event.put(type(kind), SoldierEntity.attributes(kind).build());
        }
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.HUMANITY.getKey()) {
            for (SoldierKind kind : SoldierKind.values()) {
                event.accept(EGGS.get(kind).get());
            }
        }
    }

    private AllyEntities() {
    }
}
