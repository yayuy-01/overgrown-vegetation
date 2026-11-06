package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
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

    /**
     * 「ファルキムネス」— カマキリの姿をした爬虫類。グループ5(適応種)の陸の攻撃役(2026-09-23、11-05 に作り直して戻した)。
     * 11-05 から描画倍率 1.8 を geo に焼き込み、体格(SCALE)は 1。当たり判定は前と同じ実効 2.97 x 3.06(胴だけを覆う。3 を超えると経路探索が通らない)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FalcimnesEntity>> FALCIMNES =
            ENTITY_TYPES.register("falcimnes", () -> EntityType.Builder
                    .of(FalcimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("falcimnes", 2.97F), MobStats.height("falcimnes", 3.06F))
                    .spawnDimensionsScale(MobStats.spawnScale("falcimnes"))
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build("falcimnes"));

    /**
     * 「アルボラ」— G4(拡大種)の地上の大群・前衛(11-05 に作り直して戻した)。全面に張り付いて動く({@code entity/cling})。
     * 当たり判定は 1 マスの隙間を通れる 0.98(前と同じ)。張り付きの部品は体が 1 マスに収まる前提。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ArboraEntity>> ARBORA =
            ENTITY_TYPES.register("arbora", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.ArboraEntity>of(com.yayuy.overgrownvegetation.entity.ArboraEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("arbora", 0.98F), MobStats.height("arbora", 0.98F))
                    .spawnDimensionsScale(MobStats.spawnScale("arbora"))
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build("arbora"));

    /** ファルキムネスの飛ぶ斬撃(11-05)。当たり判定は斬撃が自分で引く(横 3 × 厚み 0.5)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity>> FALCIMNES_WAVE =
            ENTITY_TYPES.register("falcimnes_wave", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity>of(com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .noSave()
                    .fireImmune()
                    .build("falcimnes_wave"));

    /** ファルキムネスの地走りの光の帯(11-05)。位置は鎌を刺した所、向きが筋の向き。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity>> FALCIMNES_RIFT =
            ENTITY_TYPES.register("falcimnes_rift", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity>of(com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .noSave()
                    .fireImmune()
                    .build("falcimnes_rift"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }

    private ModEntities() {
    }
}
