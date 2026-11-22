package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;

/**
 * 根糸の巣(2026-11-13、能力の第 4 回。仕様 Vault「G1 クモ(1 段目)」3.1【決定 11-09: クモの巣の植物版・入れてよい】)。
 * クモの 1 段目が吐いた根糸({@code G1RootsilkBolt})が外れて着いた所にできる。自然には生成しない。
 *
 * <ul>
 *   <li>中に入ると大きく遅くなる・跳べない(バニラのクモの巣と同じ {@code makeStuckInBlock}。移動 ×{@value #STUCK_H}・縦 ×{@value #STUCK_V})。
 *   G1 のクモ(段階 0・1 段目)は遅くならない(クモの側で外す)。</li>
 *   <li>燃える。剣とハサミで早く切れる(剣はタグ {@code minecraft:sword_efficient}、ハサミは {@link #getDestroyProgress} で 15 倍 = クモの巣と同じ)。
 *   切ると繊維質を落とす(ハサミなら根糸の巣そのもの。ルートテーブル)。</li>
 *   <li><b>汚染の地面の上では残り、汚染していない所では 1〜2 分で枯れて消える</b>【決定 11-09】: 置かれてから
 *   {@value #WITHER_MIN}〜{@value #WITHER_MAX} tick 後に確かめ、下か隣(6 方向)に汚染したマスが無ければ消える。あれば同じ間隔でまた確かめる。</li>
 *   <li>絵は今ある天井の根の網 {@code ceiling_root_net} を ×字のモデルでそのまま使う(新しい絵は作らない。仕様の「クモの巣の形に
 *   垂れ根・這い蔦の画素で糸の線」は docs/TEXTURE_TODO.md)。</li>
 * </ul>
 */
public class RootsilkWebBlock extends Block {

    static final double STUCK_H = 0.25D;
    static final double STUCK_V = 0.05D;
    public static final int WITHER_MIN = 1200;
    public static final int WITHER_MAX = 2400;
    /** ハサミの掘る速さの倍率(バニラのクモの巣と同じ 15)。 */
    static final float SHEARS_SPEED = 15.0F;

    public RootsilkWebBlock(Properties properties) {
        super(properties);
    }

    /**
     * 根糸が着いた所に巣を張る(サーバー)。空気か、置き換えてよい草花のマスだけ(液体の中・ほかのブロックは不可)。
     * 汚染が止まっている・胞子除去装置の中では張らない(汚染と同じ守り)。
     */
    public static boolean placeAt(ServerLevel level, BlockPos pos) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos) || level.isOutsideBuildHeight(pos)) return false;
        if (CorruptionSwitch.isOff() || SporeDeviceData.inhibits(level, pos, SporeDeviceData.Inhibit.CORRUPTION)) return false;
        BlockState old = level.getBlockState(pos);
        if (!(old.isAir() || old.canBeReplaced()) || !old.getFluidState().isEmpty() || old.is(ModG1.ROOTSILK_WEB.get())) return false;
        level.setBlock(pos, ModG1.ROOTSILK_WEB.get().defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.HANGING_ROOTS_PLACE, SoundSource.BLOCKS, 0.9F, 0.8F);
        return true;
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        entity.makeStuckInBlock(state, new Vec3(STUCK_H, STUCK_V, STUCK_H));
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide()) {
            level.scheduleTick(pos, this, WITHER_MIN + level.random.nextInt(WITHER_MAX - WITHER_MIN + 1));
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (onCorruption(level, pos)) {
            level.scheduleTick(pos, this, WITHER_MIN + random.nextInt(WITHER_MAX - WITHER_MIN + 1));
            return;
        }
        // 枯れて消える(何も落とさない)
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                8, 0.25D, 0.25D, 0.25D, 0.02D);
        level.playSound(null, pos, SoundEvents.HANGING_ROOTS_BREAK, SoundSource.BLOCKS, 0.6F, 0.7F);
        level.removeBlock(pos, false);
    }

    /** 下か隣(6 方向)に汚染したマスがあるか(= 枯れずに残る)。 */
    public static boolean onCorruption(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos p = pos.relative(d);
            if (com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p) && OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p))) return true;
        }
        return false;
    }

    /** ハサミで早く切れる(バニラのハサミの道具の決まりはクモの巣だけを名指しするので、ここで足す)。 */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float p = super.getDestroyProgress(state, player, level, pos);
        return player.getMainHandItem().canPerformAction(ToolActions.SHEARS_DIG) ? p * SHEARS_SPEED : p;
    }

    @Override
    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return true;
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 60;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 30;
    }
}
