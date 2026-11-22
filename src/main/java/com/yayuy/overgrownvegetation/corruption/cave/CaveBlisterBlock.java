package com.yayuy.overgrownvegetation.corruption.cave;

import com.yayuy.overgrownvegetation.corruption.cliff.CliffSacFog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 洞窟の水ぶくれ(2026-11-20 洞窟の作り 嚢2 大きな水ぶくれ)。器官の嚢を明るくした絵で、ぼんやり光る(光 4【仮】。今の洞窟の嚢 cave_spore_sac に合わせる)。
 * 芯なし(ユーザーの芯の決まり)。壊すと崖の嚢と同じ胞子の霧({@link CliffSacFog})が出る。
 * 塊は数十個の嚢なので、崖の嚢のような連鎖はしない: 1 つ割ると霧 1 回。まわり 3 マスにもう霧が出ていれば足さない(霧を重ねない)。
 */
public class CaveBlisterBlock extends Block {
    /**
     * 形(11-20 ユーザー「形も直そう」: 四角い積み重ねに見えた)。塊の中は満ちたブロック、上の縁と角は床から盛り上がる丸いこぶ、
     * 天井から垂れる塊の下の縁は天井から下がるこぶ。こぶは周りの面を隠さない。
     */
    public enum Form implements net.minecraft.util.StringRepresentable {
        FULL("full"), LUMP("lump"), LUMP_HANGING("lump_hanging");

        private final String name;

        Form(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Form> FORM =
            net.minecraft.world.level.block.state.properties.EnumProperty.create("form", Form.class);
    private static final net.minecraft.world.phys.shapes.VoxelShape LUMP = Block.box(1, 0, 1, 15, 14, 15);
    private static final net.minecraft.world.phys.shapes.VoxelShape LUMP_HANGING = Block.box(1, 2, 1, 15, 16, 15);

    public CaveBlisterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FORM, Form.FULL));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FORM);
    }

    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                                 net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return switch (state.getValue(FORM)) {
            case FULL -> net.minecraft.world.phys.shapes.Shapes.block();
            case LUMP -> LUMP;
            case LUMP_HANGING -> LUMP_HANGING;
        };
    }

    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getOcclusionShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return state.getValue(FORM) == Form.FULL ? net.minecraft.world.phys.shapes.Shapes.block() : net.minecraft.world.phys.shapes.Shapes.empty();
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) burst(server, pos);
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        if (level instanceof ServerLevel server) burst(server, pos);
        super.wasExploded(level, pos, explosion);
    }

    /** 最近の破裂(位置 → 霧が消える時刻)。霧を重ねないため。保存しない。 */
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap RECENT = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    /** 破裂(霧 1 回。まわり 3 マスにまだ霧が出ていれば足さない)。 */
    public static void burst(ServerLevel level, BlockPos pos) {
        long now = level.getGameTime();
        RECENT.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        for (long k : RECENT.keySet()) if (BlockPos.of(k).distSqr(pos) <= 9.0D) return;
        CliffSacFog.start(level, pos);
        RECENT.put(pos.asLong(), now + Math.max(1, com.yayuy.overgrownvegetation.data.DesignData.cliff().burst.durationTicks));
    }
}
