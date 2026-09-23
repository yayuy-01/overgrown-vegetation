package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.BlockGetter;

import javax.annotation.Nullable;

/**
 * 汚染シダ({@link BlightFernEntity})を湧かせる共通処理。
 *
 * <p>「汚染水草は汚染シダとすべて同じ仕様で、違いは湧いた場所が水中か地上かだけ」という要望に従い、
 * 汚染シダを湧かせるすべての経路(胞子濃度による自然発生・完了時の次世代スポーン・感染死・
 * 死体ブロックの孵化フォールバックなど)はここを経由する。湧く地点が<b>水中</b>なら
 * {@link BlightWaterGrassEntity}(汚染水草)を、そうでなければ {@link BlightFernEntity}(汚染シダ)を
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

    /** 湧く地点に応じた種別(水中なら汚染水草、地上なら汚染シダ)。 */
    public static EntityType<? extends BlightFernEntity> typeFor(BlockGetter level, BlockPos pos) {
        return isUnderwater(level, pos) ? ModEntities.BLIGHT_WATER_GRASS.get() : ModEntities.BLIGHT_FERN.get();
    }

    /**
     * 湧く地点に応じた汚染シダ / 汚染水草を1体生成して {@link ServerLevel#addFreshEntity} する。
     *
     * @return 生成できたエンティティ。生成に失敗したら {@code null}。
     */
    @Nullable
    public static BlightFernEntity spawn(ServerLevel level, BlockPos pos, MobSpawnType reason) {
        if (SporeDeviceData.inhibits(level, pos, SporeDeviceData.Inhibit.FERN_SPAWN)) {
            // 稼働中の胞子除去装置の効果範囲内: 装置のチャンクでは必ず、末端では変化量 % の確率で湧きが不発になる。
            return null;
        }
        EntityType<? extends BlightFernEntity> type = typeFor(level, pos);
        // mobs.json で naturalSpawn=false にした種は「自然発生」だけ止める。
        // 死体ブロック・感染死・装置など意図した湧き(MOB_SUMMONED)はそのまま通す。
        if ((reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION)
                && !com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type)) {
            return null;
        }
        BlightFernEntity entity = type.create(level, null, pos, reason, false, false);
        if (entity == null) {
            return null;
        }
        level.addFreshEntity(entity);
        return entity;
    }
}
