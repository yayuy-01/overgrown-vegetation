package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;

import java.util.List;

/**
 * 寝袋(2026-10-16、㊹)。ユーザー指定: どこでも寝られる・敵がいても寝られる・復活地点は変えない。
 * <ul>
 *     <li>敵がそばにいる(バニラの NOT_SAFE)だけは許す({@link CanPlayerSleepEvent})。夜でない・眠れない世界はバニラのまま。</li>
 *     <li>バニラは寝る前に復活地点を書くので、寝袋のときは取り消す({@link PlayerSetSpawnEvent})。復活の場所も持たない(getRespawnPosition は既定の空)。</li>
 *     <li>仮決め: 繊維質 3 + 羊毛 3 で作る / 1 ブロックの敷物(頭側に巻いた枕)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class BedrollBlock extends SleepingMatBlock {

    public static final MapCodec<BedrollBlock> CODEC = simpleCodec(BedrollBlock::new);
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 4.0D, 16.0D);

    public BedrollBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @SubscribeEvent
    public static void onCanSleep(CanPlayerSleepEvent event) {
        if (event.getState().is(ModBlocks.BEDROLL.get()) && event.getProblem() == Player.BedSleepingProblem.NOT_SAFE) {
            event.setProblem(null);
        }
    }

    @SubscribeEvent
    public static void onSetSpawn(PlayerSetSpawnEvent event) {
        BlockPos pos = event.getNewSpawn();
        Player player = event.getEntity();
        if (pos != null && player.level().dimension().equals(event.getSpawnLevel()) && player.level().getBlockState(pos).is(ModBlocks.BEDROLL.get())) {
            event.setCanceled(true);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.bedroll").withStyle(ChatFormatting.GRAY));
    }
}
