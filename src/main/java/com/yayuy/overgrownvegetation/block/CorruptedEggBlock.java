package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 汚染された卵(2026-11-13、能力の第 2 回。鶏の 1 段目の (b) が産む。仕様 Vault「G1 鶏(1 段目)」の「汚染された卵」【決定 11-09 ユーザー】)。
 * 設定: 植物が鶏の産卵の仕組みを自分の繁殖に使い、歩いた先に汚染の種を置く。<b>中から鳥は生まれない</b>(設定では鳥は消える)。
 *
 * <ul>
 *   <li>形はバニラの亀の卵と同じ作り: 1 升に 1〜4 個({@link #EGGS})・ひびの 3 段({@link #HATCH} 0〜2)。当たり判定・模型の箱も亀の卵のもの。</li>
 *   <li><b>効果は後で決める【決定 11-09・11-13】</b>ので、今は何もしない: 段は進まない(ランダム tick なし)・何も生まれない・割れても何も起きない。
 *   {@code TurtleEggBlock} を継ぐと亀が孵るので、継がずに形だけ写した。</li>
 *   <li><b>踏むと割れて消える【決定 11-13 ユーザー「踏むと割れて消滅」】</b>({@link #stepOn}・{@link #tramples})。何も落とさない。
 *   割るのは<b>植物側以外のすべての生き物【決定 11-13 ユーザー「卵は植物側以外なら誰でも割るかな」】</b>・<b>植物側は割らない</b>
 *   (産んだ鶏は自分の足元の升に産むので、除かないと産んだ直後に自分で割る)。
 *   【仮】で残る所: プレイヤーは創造でも割る・観戦は割らない・ほかの Mob は亀の卵と同じく mobGriefing が要る・忍び歩きでも割れる・
 *   確率なしで乗った瞬間に・升ごと(1〜4 個まとめて)消える。</li>
 *   <li>素手で壊せる・何も落とさない(亀の卵と同じ)。アイテムは無い(置くのは鶏か {@code /setblock})。光らない。</li>
 * </ul>
 */
public class CorruptedEggBlock extends Block {

    public static final int MAX_EGGS = 4;
    public static final IntegerProperty EGGS = BlockStateProperties.EGGS;
    public static final IntegerProperty HATCH = BlockStateProperties.HATCH;
    private static final VoxelShape ONE_EGG = Block.box(3.0D, 0.0D, 3.0D, 12.0D, 7.0D, 12.0D);
    private static final VoxelShape MULTIPLE_EGGS = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 7.0D, 15.0D);

    public CorruptedEggBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(EGGS, 1).setValue(HATCH, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(EGGS, HATCH);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(EGGS) > 1 ? MULTIPLE_EGGS : ONE_EGG;
    }

    /**
     * 踏むと割れて消える【決定 11-13 ユーザー】。升ごと壊す(割れる粒と音 = このブロックの壊す音。何も落とさない)。
     * 乗っている間は毎 tick 呼ばれるので、1 個ずつ減らしても数 tick で全部消える → まとめて消す。
     */
    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (!level.isClientSide() && tramples(level, entity)) {
            level.destroyBlock(pos, false, entity);
        }
        super.stepOn(level, pos, state, entity);
    }

    /**
     * その生き物が乗ると卵が割れるか: 生きている生き物で、植物側({@link SporeExposureTracker#isPlantSide})でないこと【決定 11-13 ユーザー】。
     * 【仮】: プレイヤーは創造でも割る・観戦は割らない・ほかの Mob は亀の卵と同じく mobGriefing が要る。落ちたアイテムなど生き物でない物は割らない。
     */
    public static boolean tramples(Level level, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) return false;
        if (SporeExposureTracker.isPlantSide(living)) return false;
        return living instanceof Player || ForgeEventFactory.getMobGriefingEvent(level, living);
    }

    /** その升に卵を置ける・足せるか(空いた升で下が固い、または卵が {@value #MAX_EGGS} 個未満)。水の中には置かない。 */
    public static boolean canLayAt(ServerLevel level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (here.getBlock() instanceof CorruptedEggBlock) {
            return here.getValue(EGGS) < MAX_EGGS;
        }
        if (!here.isAir() && !(here.canBeReplaced() && here.getFluidState().isEmpty())) return false;
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * その升に卵を 1 個産む(空なら置く・卵があれば 1 個増やす)。
     *
     * @return 産めたか
     */
    public static boolean layAt(ServerLevel level, BlockPos pos, Block egg) {
        if (!canLayAt(level, pos)) return false;
        BlockState here = level.getBlockState(pos);
        if (here.getBlock() instanceof CorruptedEggBlock) {
            level.setBlock(pos, here.setValue(EGGS, here.getValue(EGGS) + 1), Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, egg.defaultBlockState(), Block.UPDATE_ALL);
        }
        return true;
    }

    /** まわり(水平 {@code horizontal}・上下 {@code vertical} ブロック)の卵の数(個数の合計)。 */
    public static int countAround(ServerLevel level, BlockPos center, int horizontal, int vertical) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-horizontal, -vertical, -horizontal), center.offset(horizontal, vertical, horizontal))) {
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof CorruptedEggBlock) n += s.getValue(EGGS);
        }
        return n;
    }
}
