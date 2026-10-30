package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/**
 * 過繁茂のバイオームに生える、力をためた植物(2026-10-16、サバイバル用アイテム ideas.useful_plants。ユーザー指定
 * 「力をためた植物は過繁茂のバイオーム」「畑で育てられるのは薬草と繊維草だけ」)。汚染した地面の下草として生える
 * ({@code corruption/UsefulPlants})。種は無く、実を採っても株は残る(甘い実の茂みと同じ)。
 * <ul>
 *     <li>炸裂の実 {@link Kind#BURST}: 段 2(未熟)で採る。段 3 で熟すと、触れただけで爆ぜる(ブロックは壊さない。植物側の Mob は爆ぜさせない)。</li>
 *     <li>粘り蔦 {@link Kind#STICKY}: 入ると足が絡む。段 3 で粘りの実。</li>
 *     <li>浮き袋草 {@link Kind#FLOAT_SAC}: 段 3 で浮き袋。</li>
 * </ul>
 * (蓄電の実は 2026-10-22 に削除。電池は地脈の汲み上げ器で充電する。)
 * 数値(爆発の強さ・遅さ・採れる数)は仮決め。
 */
public class OvergrownFruitPlantBlock extends BushBlock {

    public enum Kind { BURST, STICKY, FLOAT_SAC }

    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;
    public static final int MAX_AGE = 3;
    private static final VoxelShape YOUNG = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 8.0D, 13.0D);
    private static final VoxelShape GROWN = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 14.0D, 15.0D);

    private final Kind kind;
    private final Supplier<? extends ItemLike> fruit;

    public OvergrownFruitPlantBlock(Kind kind, Supplier<? extends ItemLike> fruit, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
        this.fruit = fruit;
        registerDefaultState(stateDefinition.any().setValue(AGE, 0));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(AGE) < 2 ? YOUNG : GROWN;
    }

    /** 汚染した地面にも普通の地面にも生える(上面が支えになるブロックなら)。 */
    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return mayPlaceOn(level.getBlockState(pos.below()), level, pos.below());
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(AGE) < MAX_AGE;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) == 0) {
            level.setBlock(pos, state.setValue(AGE, state.getValue(AGE) + 1), Block.UPDATE_CLIENTS);
        }
    }

    /** 実が採れる段か(炸裂の実は未熟な段 2、ほかは育ちきった段 3)。 */
    public boolean harvestable(BlockState state) {
        return kind == Kind.BURST ? state.getValue(AGE) == 2 : state.getValue(AGE) == MAX_AGE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (kind == Kind.BURST && state.getValue(AGE) == MAX_AGE) {
            if (level instanceof ServerLevel server) {
                burst(server, pos, state);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if (!harvestable(state)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            int count = kind == Kind.BURST ? 1 : 1 + level.random.nextInt(2);
            popResource(level, pos, new ItemStack(fruit.get(), count));
            level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F,
                    0.8F + level.random.nextFloat() * 0.4F);
            level.setBlock(pos, state.setValue(AGE, 1), Block.UPDATE_CLIENTS);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (kind == Kind.STICKY && state.getValue(AGE) >= 2) {
            entity.makeStuckInBlock(state, new Vec3(0.5D, 0.75D, 0.5D));
        } else if (kind == Kind.BURST && state.getValue(AGE) == MAX_AGE && level instanceof ServerLevel server
                && MobGroup.of(living.getType()) == null) {
            burst(server, pos, state);
        }
    }

    /** 熟した炸裂の実が爆ぜる(ブロックは壊さない)。株は芽に戻る。 */
    public static void burst(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(AGE, 0), Block.UPDATE_CLIENTS);
        level.explode(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 1.5F, Level.ExplosionInteraction.NONE);
    }
}
