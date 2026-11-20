package com.yayuy.overgrownvegetation.corruption.cliff;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 崖の嚢(2026-11-20 崖の汚染 B2 嚢の房)。器官の嚢 {@code organ_sac} と同じ見た目だが別のブロック:
 * <ul>
 *   <li>器官の嚢は踏む・叩く・矢で割れるが、崖の嚢は<b>壊したときだけ</b>破裂する(ユーザー決定「触れても割れない。壊したら破裂」)。</li>
 *   <li>芯が無い(ユーザーの決まり: 芯は機能があって植物側に有利で消す方法が無いと困る物にだけ)ので、枯れの状態も器官の台帳も持たない。</li>
 * </ul>
 * 破裂すると、しばらく残る胞子の霧({@link CliffSacFog})がまわりの胞子濃度と暴露量を上げる。
 * 生成・掃除の置き換え({@code setBlock})では割れない(プレイヤーの破壊と爆発だけ)。
 */
public class CliffSacBlock extends Block {

    public static final MapCodec<CliffSacBlock> CODEC = simpleCodec(CliffSacBlock::new);

    public CliffSacBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) burst(server, pos);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        if (level instanceof ServerLevel server) burst(server, pos);
        super.wasExploded(level, pos, explosion);
    }

    /** 破裂させる(霧を出す)。設計値 {@code burst.chain} が真なら、房の隣の嚢も続けて割る(1 tick 後ではなくその場で。房は数マスなので重くない)。 */
    public static void burst(ServerLevel level, BlockPos pos) {
        CliffSacFog.start(level, pos);
        if (!com.yayuy.overgrownvegetation.data.DesignData.cliff().burst.chain) return;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getBlock() instanceof CliffSacBlock) {
                level.destroyBlock(n, false);
                burst(level, n);
            }
        }
    }
}
