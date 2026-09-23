package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * {@link CorruptedCorpseBlock} の中身。「胞子感染」で力尽きた Mob の死体として、
 * blight_fern と同じ {@link CorruptionSpreader} を使ってこの場所を起点に侵食を広げる。
 * ただし半径は {@link OvergrownVegetationConfig.Common#corpseCorruptionRadius}
 * (既定 10。blight_fern の {@code corruptionRadius}(既定 30)より小さい)。
 *
 * <p>半径いっぱいまで汚染し終えたら(=侵食が完了したら)、この死体を消して
 * {@link OvergrownVegetationConfig.Common#corpseHatchChance}(既定 80%)の確率で種族対応の
 * 「過繁茂した(MOB名)」({@link CorpseHatchTable} 参照)を同じ場所に孵化させ、外れた残り
 * (既定 20%)ではその場に汚染シダ(blight_fern)を生やす({@link #finish}）。全ての死体ブロックが
 * {@link CorpseHatchTable} に対応表を持つので、この2択のどちらかに必ず分岐する。
 *
 * <p>種族専用の GeckoLib モデルを持つ死体({@link GeoCorruptedCorpseBlock}: 羊・豚・クリーパー)は
 * この中身を {@code GeoBlockEntity} として {@code CorruptedCorpseRenderer}(GeoBlockRenderer)で
 * 描画する。遠くの個体は
 * 描画しない({@code getViewDistance()} を短く設定)。アニメーションはせず静的なモデルだが、
 * GeckoLib でカスタムモデルを描画するには {@code GeoBlockEntity} + {@code GeoBlockRenderer} の組が必要。
 * まだ専用モデルの無い死体(牛・ゾンビ・クモ)は通常のブロックモデル({@link CorruptedCorpseBlock})の
 * ままで、その場合 {@code CorruptedCorpseRenderer} は何も描画しない。
 */
public class CorruptedCorpseBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final CorruptionSpreader spreader = new CorruptionSpreader();
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 侵食完了後の孵化/汚染シダ抽選を、もう行ったか。 */
    private boolean spawnRolled = false;

    public CorruptedCorpseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORRUPTED_CORPSE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CorruptedCorpseBlockEntity blockEntity) {
        if (level instanceof ServerLevel serverLevel) {
            blockEntity.tick(serverLevel, pos);
        }
    }

    private void tick(ServerLevel level, BlockPos pos) {
        if (spawnRolled) {
            // 拡散も抽選も完了済み。以後は本当に何もしない(blight_fern と同じ考え方)
            return;
        }

        if (!spreader.isFinished()) {
            spreader.seedGround(level, pos);

            int interval = OvergrownVegetationConfig.COMMON.tickInterval.get();
            // 同じ tick に多数の死体の侵食サイクルが重ならないよう、座標から決まる位相でずらす
            // (blight_fern が UUID でやっているのと同じ考え方)。
            int phase = Math.floorMod(pos.hashCode(), interval);
            if ((level.getGameTime() + phase) % interval == 0) {
                // 死体は露出面のあるブロックのみ汚染する(内部には一切広がらない、深さ0固定)
                int before = spreader.getCorruptedCount();
                spreader.tick(level, pos, OvergrownVegetationConfig.COMMON.corpseCorruptionRadius.get(), 0);
                CorruptionCounter.record(level, spreader.getCorruptedCount() - before);
            }
            return;
        }

        spawnRolled = true;
        setChanged();
        finish(level, pos);
    }

    /**
     * 侵食完了時の一度きりの分岐。死体を消し、{@code corpseHatchChance} に当たれば種族対応の
     * 「過繁茂した(MOB名)」を、外れれば汚染シダを、いずれも同じ座標(死体があった場所)に湧かせる。
     * {@code shouldOffsetY=false} で {@link EntityType#create} を呼ぶことで、死体の1つ上ではなく
     * 死体そのものの位置に足が来るようにしている(死体はこの直前に取り除くので、湧いた Mob が
     * 消える前のブロックにめり込むことはない)。
     */
    private void finish(ServerLevel level, BlockPos pos) {
        EntityType<?> hatchType = CorpseHatchTable.hatchTypeFor(getBlockState().getBlock());
        level.removeBlock(pos, false);

        double hatchChance = OvergrownVegetationConfig.COMMON.corpseHatchChance.get();
        if (hatchType != null && level.random.nextDouble() < hatchChance) {
            Entity spawned = hatchType.create(level, null, pos, MobSpawnType.MOB_SUMMONED, false, false);
            if (spawned != null) {
                level.addFreshEntity(spawned);
            }
        } else {
            // フォールバックの汚染シダ。水中の死体なら汚染水草になる。
            BlightFernPlacement.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        spreader.save(tag);
        tag.putBoolean("SpawnRolled", spawnRolled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        spreader.load(tag);
        spawnRolled = tag.getBoolean("SpawnRolled");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 死体は静的なモデルなので、アニメーションコントローラーは登録しない。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
