package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * この MOD のエンティティ型の登録。
 *
 * <p><b>当たり判定を mobs.json から取る Mob には {@code spawnDimensionsScale} も付ける</b>({@link MobStats#spawnScale})。
 * SCALE 属性(mobs.json の {@code scale} と個体差 {@code scaleMin}〜{@code scaleMax})が当たり判定に掛かるのは
 * 湧いた後の最初の tick の終わりだが、湧く場所の空きを確かめる箱({@code EntityType#getSpawnAABB} —
 * {@link com.yayuy.overgrownvegetation.entity.SpawnFit#fits}・バニラの自然湧き・スポナーが使う)は
 * {@code sized} の素の大きさのまま。付けないと素の大きさの隙間に湧いてから育って、丸太や壁に埋まる
 * (2026-09-23、ファルキムネスで発覚)。体格 1 の種にも付けてある —— mod マネージャーで {@code scaleMax} を
 * 足したときに、ここを直し忘れても湧き判定がずれないように。
 *
 * <p>付けないのは、当たり判定から SCALE を打ち消している種だけ(汚染シダ・汚染水草・ハイヴノード。
 * {@code getDefaultDimensions} で {@code 1 / scale} を掛けて判定を固定しているので、素の箱のほうが大きいか同じ)。
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, OvergrownVegetation.MODID);

    /**
     * 「ハイヴノード」— 巨大樹の真下の「巣の広間」に吊り下がる球体。旧「古代樹の核」の代替で、
     * 生きている間だけ周囲が胞子感染3 の強制ゾーンになる
     * ({ com.yayuy.overgrownvegetation.entity.HiveNodeEntity})。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HiveNodeEntity>> HIVE_NODE =
            ENTITY_TYPES.register("hive_node", () -> EntityType.Builder
                    .of(HiveNodeEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("hive_node", 3.0F), MobStats.height("hive_node", 3.0F))
                    // spawnDimensionsScale は付けない(当たり判定から SCALE を打ち消しているので、素の箱のほうが大きいか同じ。クラス javadoc)
                    .clientTrackingRange(16)
                    .updateInterval(3)
                    .fireImmune()
                    .build("hive_node"));

    /** 巨大シダの寄生植物。侵食の起点。 */
    public static final DeferredHolder<EntityType<?>, EntityType<BlightFernEntity>> BLIGHT_FERN =
            ENTITY_TYPES.register("blight_fern", () -> EntityType.Builder
                    .of(BlightFernEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("blight_fern", 1.4F), MobStats.height("blight_fern", 2.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("blight_fern")) // 10-23 萌芽体は体格 1・当たり判定 1 × 2(見た目は 3.65。描画の箱は BlightFernEntity#getBoundingBoxForCulling で広げる)
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build("blight_fern"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }

    private ModEntities() {
    }
}
