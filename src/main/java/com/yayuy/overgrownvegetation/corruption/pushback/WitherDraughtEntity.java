package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.gear.MacheteItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * <b>枯らし薬</b>【仮の名前】の投げた瓶(押し返し 2026-11-18 夜 ユーザー決定「散布薬 = 一時的に植物を枯らす。胞子が溜まって 1 日ほどで発芽すれば戻る」。
 * 何日も生えなくする枯葉剤とは別物)。元は 19 世紀のボルドー液(銅と石灰。ぶどう畑の菌に撒かれた薬)。
 *
 * <p>割れた所から半径 {@code spray.radius}【仮 4】の球で:
 * 汚染の下草・蔦・胞子嚢・汚染の葉 → 枯れて消える / 汚染の地面 → 枯れた土({@link WitheredSoilBlock}。1 日【仮】で見直す) / 苔の覆い → はがれる。
 * 幹・巨大樹・器官には効かない(焼くか切る)。Mob・プレイヤーには効かない【仮】。
 */
public class WitherDraughtEntity extends ThrowableItemProjectile {

    private static final DustParticleOptions MIST = new DustParticleOptions(new Vector3f(0.35F, 0.62F, 0.72F), 1.4F);

    public WitherDraughtEntity(EntityType<? extends WitherDraughtEntity> type, Level level) {
        super(type, level);
    }

    public WitherDraughtEntity(Level level, LivingEntity owner) {
        super(ModPushBack.WITHER_DRAUGHT_ENTITY.get(), owner, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModPushBack.WITHER_DRAUGHT.get();
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel server) {
            splash(server, BlockPos.containing(result.getLocation()));
            discard();
        }
    }

    /** 半径の中を枯らす(GameTest からも)。変えたマスの数を返す。 */
    public static int splash(ServerLevel level, BlockPos center) {
        PushBackTable.Spray t = DesignData().spray;
        int r = (int) Math.ceil(t.radius);
        double r2 = t.radius * t.radius;
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (p.distSqr(center) > r2) continue;
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p)) continue;
            BlockState s = level.getBlockState(p);
            BlockPos fixed = p.immutable();
            boolean changed = false;
            if (s.is(MacheteItem.CLEARABLE) || s.is(ModBlocks.OVERGROWN_LEAVES.get())) {
                level.setBlock(fixed, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                changed = true;
            } else if (WitheredSoilBlock.withers(s)) {
                level.setBlock(fixed, WitheredSoilBlock.from(s), Block.UPDATE_ALL);
                changed = true;
            } else if (!s.isAir() && OvergrownLayerData.isCovered(level, fixed)) {
                changed = OvergrownLayerData.uncover(level, fixed);
            }
            if (changed) {
                n++;
                LedgerShrink.touch(level, fixed, LedgerShrink.Cause.REMOVED);
                if (level.random.nextInt(3) == 0) {
                    level.sendParticles(MIST, fixed.getX() + 0.5D, fixed.getY() + 0.8D, fixed.getZ() + 0.5D, 1, 0.3D, 0.2D, 0.3D, 0.0D);
                }
            }
        }
        Vec3 c = Vec3.atCenterOf(center);
        level.sendParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 30, t.radius * 0.4D, 0.4D, t.radius * 0.4D, 0.1D);
        level.sendParticles(MIST, c.x, c.y + 0.5D, c.z, 40, t.radius * 0.5D, 0.6D, t.radius * 0.5D, 0.0D);
        level.playSound(null, center, SoundEvents.SPLASH_POTION_BREAK, SoundSource.NEUTRAL, 1.0F, 0.9F);
        return n;
    }

    private static PushBackTable DesignData() {
        return com.yayuy.overgrownvegetation.data.DesignData.pushBack();
    }
}
