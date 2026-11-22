package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 汚染シダ({@link BlightFernEntity})を湧かせる共通処理。
 *
 * <p>「汚染水草は汚染シダとすべて同じ仕様で、違いは湧いた場所が水中か地上かだけ」という要望に従い、
 * 汚染シダを湧かせるすべての経路(胞子濃度による自然発生・完了時の次世代スポーン・感染死・
 * 死体ブロックの孵化フォールバックなど)はここを経由する。湧く地点が<b>水中</b>なら
 * 汚染水草を、そうでなければ {@link BlightFernEntity}(汚染シダ)を(2026-10-23 に汚染水草を削除し、いつも萌芽体)
 * 生成する。
 */
public final class BlightFernPlacement {

    private BlightFernPlacement() {
    }

    /** {@code pos}(足元の1つ上=植物本体が入るマス)またはその上のマスが水なら「水中」とみなす。 */
    public static boolean isUnderwater(BlockGetter level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER)
                || level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    /** 湧く種別。2026-10-23 に汚染水草を削除して萌芽体に統一したので、水中でも萌芽体(水に落ちたら岸へ上がる)。 */
    public static EntityType<? extends BlightFernEntity> typeFor(BlockGetter level, BlockPos pos) {
        return ModEntities.BLIGHT_FERN.get();
    }

    /**
     * 湧く地点に応じた汚染シダ / 汚染水草を1体生成して {@link ServerLevel#addFreshEntity} する。
     *
     * @return 生成できたエンティティ。生成に失敗したら {@code null}。
     */
    @Nullable
    public static BlightFernEntity spawn(ServerLevel level, BlockPos pos, MobSpawnType reason) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return null; // /ov corruption false(2026-10-10 ユーザー指定): 新しい汚染シダは生まれない(死・感染・胞子濃度・自己増殖・地割れ・爆弾の全部がここを通る)
        }
        if (SporeDeviceData.inhibits(level, pos, SporeDeviceData.Inhibit.FERN_SPAWN)) {
            // 稼働中の胞子除去装置の効果範囲内: 装置のチャンクでは必ず、末端では変化量 % の確率で湧きが不発になる。
            return null;
        }
        EntityType<? extends BlightFernEntity> type = typeFor(level, pos);
        // 自然発生(地割れなど)の入り切りは spawns.json の fernNaturalSpawn(2026-11-16 見直し ④。前は mobs.json の naturalSpawn)。
        // 死体ブロック・感染死・装置など意図した湧き(MOB_SUMMONED)はそのまま通す。
        if ((reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION)
                && !com.yayuy.overgrownvegetation.data.DesignData.spawns().fernNaturalSpawn) {
            return null;
        }
        BlightFernEntity entity = type.create(level, null, null, pos, reason, false, false);
        if (entity == null) {
            return null;
        }
        level.addFreshEntity(entity);
        return entity;
    }

    /**
     * <b>死に由来する</b>湧き(胞子感染死・G1 の過繁茂滞在死・胞子感染3 の期限切れで死んだプレイヤー)の入口
     * (2026-09-25、ユーザー承認の「汚染の見直し」。監査報告 1571)。近くに汚染シダ / 汚染水草が
     * もう十分いれば湧かせず、そうでなければ {@link #spawn}({@code MOB_SUMMONED})と同じ。
     *
     * <p>以前は死ぬたびに無条件で 1 本ずつ湧かせていたので、胞子の雲で群れがまとめて死んだ地点などに、
     * 同じ場所を汚染する起点が何本も重なっていた(どれも担当半径を汚染し終えれば消えるので永久には溜まらないが、
     * その間は重複した汚染処理と描画を抱える)。上限は 2 段:
     * <ul>
     *     <li>{@code blightFernDeathSpawnMinSeparation}(既定 8)以内に 1 体でもいる —— すぐ隣の起点と役目が重なる</li>
     *     <li>{@code blightFernDeathSpawnNearbyRadius}(既定 32)以内に {@code blightFernDeathSpawnMaxNearby}
     *     (既定 10)体以上いる —— 次世代の湧きの密度の上限({@code blightFernFinishSpawnMaxNearby}、
     *     半径 64 で 40)と同じ面積あたりの密度</li>
     * </ul>
     * (汚染水草は 2026-10-23 に削除した。)
     * 判定の箱は立方体(水平距離ではない。前は自己増殖 BlightFernSelfSpread と揃えていた —— そちらは消えている)。
     *
     * <p>攻城戦ボスに殺された Mob(SiegeKillHandler。2026-10-30 に parked/ へ退避)はここを通さない —— 引き継ぎ書で
     * 「必ず 1 本生える」と決めてある。死体ブロックの孵化(死体を経由するので死とは間が空く)も対象外。
     *
     * @return 生成できたエンティティ。密度の上限・除去装置などで湧かなかったら {@code null}。
     */
    @Nullable
    public static BlightFernEntity spawnFromDeath(ServerLevel level, BlockPos pos) {
        if (tooCrowdedForDeathSpawn(level, pos)) {
            return null;
        }
        return spawn(level, pos, MobSpawnType.MOB_SUMMONED);
    }

    /** {@link #spawnFromDeath} の密度の判定。検索は大きい方の箱 1 回で済ませ、近い方は同じ一覧から拾う。 */
    private static boolean tooCrowdedForDeathSpawn(ServerLevel level, BlockPos pos) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int separation = cfg.blightFernDeathSpawnMinSeparation.get();
        int maxNearby = cfg.blightFernDeathSpawnMaxNearby.get();
        int nearbyRadius = maxNearby > 0 ? cfg.blightFernDeathSpawnNearbyRadius.get() : 0;
        int reach = Math.max(separation, nearbyRadius);
        if (reach <= 0) {
            return false; // どちらも無効。
        }
        List<BlightFernEntity> ferns = level.getEntitiesOfClass(BlightFernEntity.class, new AABB(pos).inflate(reach));
        if (ferns.isEmpty()) {
            return false;
        }
        if (maxNearby > 0) {
            // reach > nearbyRadius(間隔の方が広い設定)のときだけ、半径の内側に絞って数え直す。
            int nearby = ferns.size();
            if (reach > nearbyRadius) {
                AABB nearbyBox = new AABB(pos).inflate(nearbyRadius);
                nearby = 0;
                for (BlightFernEntity fern : ferns) {
                    if (fern.getBoundingBox().intersects(nearbyBox)) {
                        nearby++;
                    }
                }
            }
            if (nearby >= maxNearby) {
                return true;
            }
        }
        if (separation > 0) {
            AABB separationBox = new AABB(pos).inflate(separation);
            for (BlightFernEntity fern : ferns) {
                if (fern.getBoundingBox().intersects(separationBox)) {
                    return true;
                }
            }
        }
        return false;
    }
}
