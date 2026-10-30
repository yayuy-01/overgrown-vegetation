package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.mixin.EntityPersistentDataAccessor;
import com.yayuy.overgrownvegetation.network.CorpseCollapsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 胞子感染で死んだバニラの Mob が、死体ブロックと同じ姿勢へ崩れ落ちてから死体になる(2026-10-09、ユーザー指定)。
 *
 * <p>流れ: 死んだ瞬間({@code SporeInfectionDeathHandler})に {@link #begin} で印を付け、見ているプレイヤーへ
 * {@link CorpseCollapsePayload} を送る。クライアントはバニラの描画のまま死体の姿勢へ倒す
 * ({@code client.corpse.CollapsePoser})。サーバーは {@link #COLLAPSE_TICKS} 経ったら({@code LivingEntityCorpseDeathMixin})
 * {@link #tickDeath} で死体ブロックを置き、Mob は死体が描かれ始めるまで残してから煙を出さずに消す。
 *
 * <p>死体ブロックには死んだときの体の向き(yBodyRot)と、ブロックの真ん中からのずれを記録する。
 * 死体の描画と、起き上がる Mob の位置・向きはこれを使う(倒れたその場から起き上がる)。
 */
public final class CorpseCollapse {

    private CorpseCollapse() {
    }

    /**
     * 崩れ落ちの長さ(tick)。バニラは死んで 20 tick で Mob を消すので、その手前の 19 で死体に入れ替える。
     * クライアントの姿勢の進みは (deathTime + partialTick − 1) / (COLLAPSE_TICKS − 1)。
     */
    public static final int COLLAPSE_TICKS = 19;

    private static final String TAG = "ov_corpse_collapse";

    /** 崩れ落ちを始める。対応していない死体ブロックなら false(呼び出し側が今までどおりその場に置く)。 */
    public static boolean begin(LivingEntity entity, BlockState corpse) {
        String species = CorpseSpecies.speciesOf(corpse.getBlock());
        if (species == null) {
            return false;
        }
        CompoundTag t = new CompoundTag();
        t.putString("Block", BuiltInRegistries.BLOCK.getKey(corpse.getBlock()).toString());
        t.putFloat("Yaw", entity.yBodyRot);
        entity.getPersistentData().put(TAG, t);
        PacketDistributor.sendToPlayersTrackingEntity(entity, new CorpseCollapsePayload(entity.getId(), species, entity.yBodyRot));
        return true;
    }

    /**
     * 死体を置いてから Mob を消すまでの猶予(tick)。新しく置いたブロックエンティティは、描画の区画が作り直されるまで
     * 描かれない(数フレーム〜数 tick)。その間に Mob が消えると何も無い瞬間ができる(10-09 に見た点滅)。
     * そのため Mob は残し、クライアントは死体が描かれ始めたら Mob を描くのをやめる({@code ClientCorpseCollapse#shouldHide})。
     */
    public static final int HANDOFF_TICKS = 20;

    /** 崩れ落ちの途中か(サーバー側)。印の入れ物を作らずに見る。 */
    public static boolean isCollapsing(Entity entity) {
        CompoundTag data = ((EntityPersistentDataAccessor) entity).overgrown_vegetation$getPersistentDataOrNull();
        return data != null && data.contains(TAG);
    }

    /** サーバーの死の時計 1 tick 分({@code LivingEntityCorpseDeathMixin} から)。19 で死体を置き、さらに {@link #HANDOFF_TICKS} 後に Mob を消す。 */
    public static void tickDeath(LivingEntity entity) {
        entity.deathTime++;
        CompoundTag t = entity.getPersistentData().getCompound(TAG);
        if (entity.deathTime >= COLLAPSE_TICKS && !t.getBoolean("Placed")) {
            t.putBoolean("Placed", true);
            place(entity, t);
        }
        if (entity.deathTime >= COLLAPSE_TICKS + HANDOFF_TICKS && !entity.isRemoved()) {
            entity.getPersistentData().remove(TAG);
            entity.remove(Entity.RemovalReason.KILLED);
        }
    }

    /** 崩れ落ちの終わり: 死体ブロックを置く(向き・ずれを記録)。Mob はまだ消さない。 */
    private static void place(LivingEntity entity, CompoundTag t) {
        if (entity.level() instanceof ServerLevel level) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(t.getString("Block")));
            BlockPos pos = entity.blockPosition();
            if (level.isLoaded(pos)) {
                level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
                if (level.getBlockEntity(pos) instanceof CorruptedCorpseBlockEntity be) {
                    be.setPlacement(t.getFloat("Yaw"),
                            entity.getX() - (pos.getX() + 0.5), entity.getY() - pos.getY(), entity.getZ() - (pos.getZ() + 0.5));
                }
                // 体から植物が噴き出す(テクスチャの入れ替わりを隠す)
                level.sendParticles(ParticleTypes.COMPOSTER, entity.getX(), entity.getY() + 0.3, entity.getZ(),
                        24, 0.6, 0.25, 0.6, 0.02);
                level.playSound(null, pos, SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 0.8F, 0.7F);
            }
        }
    }
}
