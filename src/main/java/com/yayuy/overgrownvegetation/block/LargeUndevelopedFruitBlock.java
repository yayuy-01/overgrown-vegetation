package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 「大きな未発達な果実」— 過繫茂した木({@code worldgen/ForcedTreeFeature})と巨大樹
 * ({@code worldgen/GiantTreeFeature})の<b>葉の下</b>に低確率でぶら下がる大きな実。
 * 生成の抽選は {@link com.yayuy.overgrownvegetation.worldgen.UndevelopedFruitPlacement}。
 *
 * <p>ブロック自体は他の垂れ植物({@link CorruptedHangingGrowthBlock})と同じく当たり判定なしで、
 * 真上のブロックが無くなると落ちる(=消える)。壊すとそのまま {@code large_undeveloped_fruit}
 * アイテムとして手に入り、食べると強力なバフと重い代償が同時に来る
 * ({@link com.yayuy.overgrownvegetation.effect.FruitEffects})。
 *
 * <h2>矢で撃ち落とせる</h2>
 * 生えているのは樹冠の下面 —— 巨大樹なら地上から数十ブロック上で、登るのも足場を組むのも骨が折れる。
 * そこで<b>矢が当たると実が落ちてアイテムになる</b>ようにしてある({@link #onProjectileHit})。
 * 弓でもクロスボウでも、矢を飛ばす手段なら何でもよい。
 *
 * <h2>真上の判定を葉にも通している</h2>
 * {@link CorruptedHangingGrowthBlock} は「真上の下面が塞がっているか」({@code isFaceSturdy})だけを見る。
 * この実は<b>葉にぶら下がるのが本業</b>なので、葉ブロック({@code #minecraft:leaves} と
 * {@link ModTags#GROWTH_HANGING_ONLY})を明示的に許可して、葉の当たり判定の扱いが変わっても
 * ぶら下がれなくならないようにしてある。
 */
public class LargeUndevelopedFruitBlock extends Block {

    public static final MapCodec<LargeUndevelopedFruitBlock> CODEC =
            simpleCodec(LargeUndevelopedFruitBlock::new);

    /** 見た目どおり、マスいっぱいに近い大きさ。選択枠の形であり、矢にとっての当たり判定でもある。 */
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0);

    public LargeUndevelopedFruitBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * <b>矢から見たときだけ</b>当たり判定を返す。
     *
     * <p>矢の飛行判定は {@code ClipContext.Block.COLLIDER} —— つまり<b>当たり判定の形</b>を見るので、
     * ブロック設定の {@code noCollission()} のままでは矢がすり抜けてしまい、
     * {@link #onProjectileHit} がそもそも呼ばれない。かといって当たり判定を付けてしまうと、
     * 他の垂れ植物と違って通り抜けられない・上に乗れる邪魔なブロックになる。
     *
     * <p>{@code getCollisionShape} には「誰にとっての当たり判定か」が {@link CollisionContext} で
     * 渡ってくるので、<b>矢のときだけ</b>形を返して他には空を返せば、
     * 「プレイヤーも Mob もすり抜けるが、矢だけは当たる」を両立できる。
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof AbstractArrow) {
            return SHAPE;
        }
        return Shapes.empty();
    }

    /**
     * 矢が当たったら実を壊し、{@code large_undeveloped_fruit} をドロップさせる
     * (落ちたアイテムはそのまま地面まで落ちてくる)。
     *
     * <p>矢以外の projectile(雪玉・卵など)では落ちない —— 「弓で撃ち落とす」ための仕掛けなので、
     * 投げ物が当たるたびに勝手に落ちてしまわないようにしている。
     *
     * <p>チェックは {@link Projectile#mayInteract}(撃った本人がそこを壊せるか /
     * Mob なら {@code mobGriefing})だけで、{@code mayBreak}(ゲームルール
     * {@code projectilesCanBreakBlocks})は見ない。あちらは飾り壺やコーラスの花のような
     * 「事故で壊れる」ブロックのための歯止めで、こちらは<b>これが正規の収穫手段</b>だから
     * —— 切られていると実を採る方法が無くなってしまう。
     */
    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (level.isClientSide() || !(projectile instanceof AbstractArrow)) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (projectile.mayInteract(level, pos)) {
            level.destroyBlock(pos, true, projectile);
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState above = level.getBlockState(abovePos);
        return above.is(BlockTags.LEAVES)
                || above.is(ModTags.GROWTH_HANGING_ONLY)
                || above.isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
