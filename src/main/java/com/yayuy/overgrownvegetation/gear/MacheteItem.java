package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>山刀</b>(段 1 の近接。2026-11-17 決定 17)。戦う刃物で、蔦や下草を切り開いて森を進む道具にもなる:
 * タグ {@code machete_clearable} の植物を速く刈り({@code clearSpeed})、刈った所のまわりの同じ植物をまとめて刈る({@code clearRadius}・{@code clearMax})。
 * しゃがんでいるとまとめない(1 本だけ刈りたいとき)【仮】。まとめて刈った分の耐久は減らさない【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class MacheteItem extends SwordItem {

    public static final TagKey<Block> CLEARABLE = TagKey.create(Registries.BLOCK, OvergrownVegetation.id("machete_clearable"));

    /** まとめて刈っている最中(自分の壊しで、もう一度広げない)。 */
    private static boolean clearing;

    public MacheteItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        return state.is(CLEARABLE) ? DesignData.gearTiers().machete.clearSpeed : super.getDestroySpeed(stack, state);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (clearing || !(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (player.isShiftKeyDown() || !(player.getMainHandItem().getItem() instanceof MacheteItem)) return;
        if (!event.getState().is(CLEARABLE)) return;
        clearAround(level, event.getPos(), player);
    }

    /**
     * {@code origin}(今刈った所)のまわりの刈れる植物を、近い順に {@code clearMax} 本まで刈る(GameTest からも)。刈った数を返す。
     * 落とし物は普通に壊したときと同じ({@code destroyBlock} の落とす版)。
     */
    public static int clearAround(ServerLevel level, BlockPos origin, ServerPlayer player) {
        GearTierTable.Machete m = DesignData.gearTiers().machete;
        if (m.clearMax <= 0 || m.clearRadius <= 0) return 0;
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-m.clearRadius, -1, -m.clearRadius), origin.offset(m.clearRadius, 1, m.clearRadius))) {
            if (!p.equals(origin) && level.getBlockState(p).is(CLEARABLE)) found.add(p.immutable());
        }
        found.sort((a, b) -> Double.compare(a.distSqr(origin), b.distSqr(origin)));
        int n = 0;
        clearing = true;
        try {
            for (BlockPos p : found) {
                if (n >= m.clearMax) break;
                if (!level.getBlockState(p).is(CLEARABLE)) continue; // 上の植物と一緒に落ちた
                if (player != null && !level.mayInteract(player, p)) continue;
                if (level.destroyBlock(p, true, player)) n++;
            }
        } finally {
            clearing = false;
        }
        return n;
    }
}
