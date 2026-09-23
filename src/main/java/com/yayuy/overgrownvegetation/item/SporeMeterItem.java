package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 「胞子濃度計」— 右クリックで、今いるチャンクの胞子濃度
 * ({@link SporeConcentration})をアクションバーに表示するテスト/計測用アイテム。
 */
public class SporeMeterItem extends Item {

    public SporeMeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            ChunkPos chunk = new ChunkPos(player.blockPosition());
            SporeConcentration.Result r = SporeConcentration.at(serverLevel, chunk);
            // 噴出孔ぶんは濃度そのものには足さず、ここで<b>表示にだけ</b>上乗せする。
            // 濃度は 0〜100 のままなので、感染・装備汚染・シダの湧きの計算はずれない
            // (ユーザー指定 2026-09-19)。
            double vent = com.yayuy.overgrownvegetation.nest.NestVents
                    .bonusPercentAt(serverLevel, player.blockPosition());
            double shown = r.percent() + vent;

            ChatFormatting color = shown >= 100.0D ? ChatFormatting.LIGHT_PURPLE
                    : shown >= 50.0D ? ChatFormatting.RED
                    : shown >= 15.0D ? ChatFormatting.YELLOW
                    : ChatFormatting.GREEN;
            player.displayClientMessage(Component.translatable(
                    "item.overgrown_vegetation.spore_meter.reading",
                    String.format("%.0f", shown)).withStyle(color), true);
            if (vent > 0.0D) {
                player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.spore_meter.vent",
                        String.format("%+.0f", vent)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
            }

            // 器官(2026-09-23)。ここだけが原因へ辿る道 —— 警報も地図の印も出さない代わりに、
            // 「下限」(世界のどこでも同じ)と「上乗せ」(近いほど大きい)を分けて出す。
            // 上乗せが増える方へ歩けば器官に着く。
            if (r.organFloorComponent() > 0.0D || r.organLocalComponent() > 0.0D) {
                player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.spore_meter.organ",
                        String.format("%+.0f", r.organLocalComponent()),
                        String.format("%.0f", r.organFloorComponent()))
                        .withStyle(ChatFormatting.DARK_PURPLE), false);
            }

            Component detail = r.selfOvergrown()
                    ? Component.translatable("item.overgrown_vegetation.spore_meter.detail_biome",
                            String.format("%+.0f", r.deviceComponent()))
                    : Component.translatable("item.overgrown_vegetation.spore_meter.detail",
                            String.format("%.0f", r.distanceComponent()),
                            String.format("%.0f", r.ratioComponent()),
                            String.format("%.0f", r.corruptedComponent()),
                            r.corruptedColumns(),
                            String.format("%.0f", r.bonusComponent()),
                            String.format("%+.0f", r.deviceComponent()));
            player.displayClientMessage(detail.copy().withStyle(ChatFormatting.GRAY), false);

            if (player.isShiftKeyDown()) {
                BlockPos below = player.blockPosition().below();
                boolean corruptedGround = serverLevel.getBlockState(below).is(ModTags.CORRUPTED);
                player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.spore_meter.debug",
                        chunk.x, chunk.z,
                        serverLevel.getBlockState(below).getBlock().getName(),
                        corruptedGround ? "✔" : "✘").withStyle(ChatFormatting.DARK_GRAY), false);
            }

            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.4F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
