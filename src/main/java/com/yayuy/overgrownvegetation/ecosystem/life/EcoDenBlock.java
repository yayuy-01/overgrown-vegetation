package com.yayuy.overgrownvegetation.ecosystem.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 倒木の洞(2026-11-18、環境生物の棲み処。10-22 の決定「ハチの巣方式」・11-18 全体管理の答え)。倒木の端の 1 マスで、{@link #FACING} の面が入口。
 * <ul>
 *   <li>中にいると入口の絵が変わる({@link #OCCUPIED}: 触角と鈍い目が見える)・入口から土の粉が落ちる。</li>
 *   <li>右クリックで覗く(種と数)。</li>
 *   <li>壊すと中の個体が全部出る。サソリ・ファランギオタルブスは壊した人を襲う、ほかは逃げる。押し返し(火・散布薬)で消えたときも出て逃げる。</li>
 * </ul>
 * 絵は仮(Claude 11-18。0.2.0 の仕上げでユーザーが清書)。
 */
public class EcoDenBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OCCUPIED = BooleanProperty.create("occupied");

    public EcoDenBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OCCUPIED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OCCUPIED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EcoDenBlockEntity(pos, state);
    }

    /** 入口の前の立つ所(ブロックの下の面の真ん中)。 */
    public static Vec3 mouth(BlockPos pos, BlockState state) {
        Direction f = state.getValue(FACING);
        return Vec3.atBottomCenterOf(pos.relative(f));
    }

    /** 入口から外を向く向き(度)。 */
    public static float outYaw(BlockState state) {
        return state.getValue(FACING).toYRot();
    }

    // ---------------------------------------------------------------- 覗く

    // 1.20.1: 1.21 の useWithoutItem(素手の扱い。利き手だけで呼ばれる)を use 1 つで
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof EcoDenBlockEntity den) {
            player.displayClientMessage(peek(den), true);
        }
        return InteractionResult.CONSUME;
    }

    static Component peek(EcoDenBlockEntity den) {
        Map<String, Integer> c = den.census();
        if (c.isEmpty()) return Component.translatable("message.overgrown_vegetation.den.empty");
        MutableComponent list = Component.empty();
        boolean first = true;
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            Component name = id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                    ? BuiltInRegistries.ENTITY_TYPE.get(id).getDescription() : Component.literal(e.getKey());
            if (!first) list.append("、");
            list.append(Component.translatable("message.overgrown_vegetation.den.count", name, e.getValue()));
            first = false;
        }
        return Component.translatable("message.overgrown_vegetation.den.peek", list);
    }

    // ---------------------------------------------------------------- 壊す・消える

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof EcoDenBlockEntity den) {
            boolean attack = !player.isCreative() && !player.isSpectator();
            den.releaseAll(sl, mouth(pos, state), outYaw(state), m -> burst(m, player, attack));
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /** 押し返し(火・散布薬)などで消えたとき: 中の個体は出てきて、いちばん近いプレイヤーから逃げる(11-18 決定)。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!newState.is(this) && level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof EcoDenBlockEntity den && den.count() > 0) {
            Player near = sl.getNearestPlayer(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 24.0D, false);
            den.releaseAll(sl, mouth(pos, state), outYaw(state), m -> burst(m, near, false));
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    /** 洞から急に出された個体: 襲う種は壊した人を襲い、ほかは逃げる。 */
    static void burst(Mob m, @Nullable LivingEntity cause, boolean attack) {
        if (cause == null) return;
        if (attack && EcoLife.behavior(m).defends) {
            m.setTarget(cause);
        } else {
            m.setLastHurtByMob(cause);   // 逃げる Goal が拾う(60 tick)
        }
    }

    // ---------------------------------------------------------------- 印(粒)

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(OCCUPIED) || random.nextInt(4) != 0) return;
        Direction f = state.getValue(FACING);
        double x = pos.getX() + 0.5D + f.getStepX() * 0.52D + (f.getStepX() == 0 ? (random.nextDouble() - 0.5D) * 0.6D : 0.0D);
        double z = pos.getZ() + 0.5D + f.getStepZ() * 0.52D + (f.getStepZ() == 0 ? (random.nextDouble() - 0.5D) * 0.6D : 0.0D);
        double y = pos.getY() + 0.25D + random.nextDouble() * 0.5D;
        level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.ROOTED_DIRT.defaultBlockState()), x, y, z, 0.0D, 0.0D, 0.0D);
    }
}
