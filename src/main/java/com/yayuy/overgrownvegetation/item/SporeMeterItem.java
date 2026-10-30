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
        if (level instanceof ServerLevel serverLevel && SporeConcentration.useField(serverLevel)) {
            fieldReading(serverLevel, player);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.4F);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (level instanceof ServerLevel serverLevel) {
            ChunkPos chunk = new ChunkPos(player.blockPosition());
            SporeConcentration.Result r = SporeConcentration.at(serverLevel, chunk);
            double shown = r.percent();

            ChatFormatting color = shown >= 100.0D ? ChatFormatting.LIGHT_PURPLE
                    : shown >= 50.0D ? ChatFormatting.RED
                    : shown >= 15.0D ? ChatFormatting.YELLOW
                    : ChatFormatting.GREEN;
            player.displayClientMessage(Component.translatable(
                    "item.overgrown_vegetation.spore_meter.reading",
                    String.format("%.0f", shown)).withStyle(color), true);

            // 上空のペディキュラ(2026-09-23)。いる間だけ濃度を押し上げるので、下の内訳を足しても合わない ——
            // 合わない理由をここで出す。
            if (r.drifterComponent() > 0.0D) {
                player.displayClientMessage(Component.translatable(
                        "item.overgrown_vegetation.spore_meter.drifter",
                        String.format("%+.0f", r.drifterComponent()))
                        .withStyle(ChatFormatting.DARK_GREEN), false);
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

    /**
     * 持っている間(利き手かもう一方の手)、1 秒ごとにアクションバーへ今いる所の値(2026-10-24 地脈技術の 4 段目)。
     * 胞子の場があれば浮遊と降り積もりの 2 本の針、無ければ今の胞子濃度 1 つ。風・土地の様子(チャット欄)は右クリックのときだけ。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel server) || !(entity instanceof Player player) || player.tickCount % 20 != 0) {
            return;
        }
        if (selected || player.getOffhandItem() == stack) {
            player.displayClientMessage(heldLine(server, player.blockPosition()), true);
        }
    }

    /** 持っている間の 1 行。 */
    public static Component heldLine(ServerLevel level, BlockPos pos) {
        double[] v = readings(level, pos);
        if (v.length == 2) {
            double air = v[0], dep = v[1];
            return Component.translatable("item.overgrown_vegetation.spore_meter.needles",
                    needle(air), air >= 100.0D ? "100+" : String.format("%.0f", air),
                    needle(dep), dep >= 100.0D ? "100+" : String.format("%.0f", dep)).withStyle(colorFor(air));
        }
        return Component.translatable("item.overgrown_vegetation.spore_meter.reading", String.format("%.0f", v[0]))
                .withStyle(colorFor(v[0]));
    }

    /**
     * 今いる所の値: 胞子の場があれば {浮遊 %, 降り積もり(発芽のしきい値 = 100)%}、無ければ {胞子濃度 %}。
     * 雷雨(胞子の嵐)の間は浮遊が振り切れる(999)。
     */
    public static double[] readings(ServerLevel level, BlockPos pos) {
        if (!SporeConcentration.useField(level)) {
            return new double[]{SporeConcentration.at(level, new ChunkPos(pos)).percent()};
        }
        double air = SporeConcentration.percentAt(level, pos);
        if (level.dimension() == Level.OVERWORLD && level.isThundering()) {
            air = Math.max(air, 999.0D);
        }
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell cell =
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(level, pos.getX() >> 4, pos.getZ() >> 4);
        double threshold = com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold;
        double dep = cell == null ? 0.0D : cell.dep() / threshold * 100.0D;
        return new double[]{air, dep};
    }

    private static ChatFormatting colorFor(double percent) {
        return percent >= 100.0D ? ChatFormatting.LIGHT_PURPLE
                : percent >= 50.0D ? ChatFormatting.RED
                : percent >= 15.0D ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;
    }

    /**
     * 胞子の場の 2 本の針(2026-10-23 第 2 段 H): 浮遊 = 足元の胞子濃度(%、表示は 100 で頭打ち、超えたら「霧」)、
     * 降り積もり = 発芽のしきい値を 100% とした割合。チャット欄に風の向き・強さ、チャンクの段階と萌芽体の数。
     */
    private static void fieldReading(ServerLevel level, Player player) {
        BlockPos pos = player.blockPosition();
        double air = SporeConcentration.percentAt(level, pos);
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD && level.isThundering()) {
            // 胞子の嵐(2026-10-23 胞子の場 H): 嵐の間は浮遊の針が振り切れる(胞子が空中に満ちて測り切れない)
            air = Math.max(air, 999.0D);
        }
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell cell =
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(level, pos.getX() >> 4, pos.getZ() >> 4);
        double threshold = com.yayuy.overgrownvegetation.data.DesignData.sporeField().field().germThreshold;
        double dep = cell == null ? 0.0D : cell.dep() / threshold * 100.0D;
        ChatFormatting color = air >= 100.0D ? ChatFormatting.LIGHT_PURPLE
                : air >= 50.0D ? ChatFormatting.RED
                : air >= 15.0D ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;
        player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.needles",
                needle(air), air >= 100.0D ? "100+" : String.format("%.0f", air),
                needle(dep), dep >= 100.0D ? "100+" : String.format("%.0f", dep)).withStyle(color), true);
        com.yayuy.overgrownvegetation.spore.field.PrevailingWind.Sample w = level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                ? com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.windNow(level) : null; // ネザー・エンドに風は無い
        if (w != null) {
            String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
            Component dir = Component.translatable("item.overgrown_vegetation.spore_meter.dir." + names[(int) Math.round(w.angleDeg() / 45.0) & 7]);
            player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.wind",
                    dir, String.format("%.1f", w.strength)).withStyle(ChatFormatting.AQUA), false);
        }
        if (cell != null && cell.stage() > 0) {
            player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.stage",
                    Component.translatable("item.overgrown_vegetation.spore_meter.stage." + cell.stage()), cell.count())
                    .withStyle(ChatFormatting.DARK_GREEN), false);
        }
    }

    /** 10 目盛りの針(棒)。 */
    private static String needle(double percent) {
        int n = (int) Math.max(0, Math.min(10, Math.round(percent / 10.0D)));
        return "▮".repeat(n) + "▯".repeat(10 - n);
    }
}
