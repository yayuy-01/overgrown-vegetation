package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.Erosion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov climax …} — 極相({@code engulfed})の確認用コマンド(OP、2026-09-22)。
 * <ul>
 *     <li>{@code status} … 台帳の件数と昇格条件、<b>いま立っているチャンクが条件のどこで止まっているか</b></li>
 *     <li>{@code scan} … 判定の間隔を待たずに 1 回だけ昇格を回す</li>
 *     <li>{@code force} … いま立っているチャンクを条件を無視して極相にする(全層置換もその場で終わらせる)</li>
 *     <li>{@code ley} … <b>いちばん近い地脈へ飛ぶ</b>。{@code ley force} なら着いた先のチャンクを
 *     極相にして地脈ブロックまで出す</li>
 * </ul>
 *
 * <p>極相化は「14 日待つ」「周囲 289 チャンクが埋まる」という<b>ゲーム内で目に見えない条件</b>で
 * 進むので、止まっているときにどちらが足りないのかを出せないと調整ができない。
 * {@code status} はそのためのもの。
 *
 * <p>{@code ley} は「極相にしたのに地脈が無い」ときのためのもの。地脈は
 * {@link ClimaxTable.LeyVein#cellSize()}(既定 192 ブロック)の網目なので、
 * <b>ほとんどのチャンクには通っていない</b> —— 適当な場所を極相にしても出ないのが普通。
 *
 * <p>出力は開発用なので {@code Component.literal}(翻訳キーを作らない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClimaxCommand {

    /** {@code ley} が地脈を探す範囲(ブロック)。網目の粗さの数倍あれば必ず当たる。 */
    private static final int LEY_SEARCH_RADIUS = 1024;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("climax")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ClimaxCommand::status))
                        .then(Commands.literal("scan").executes(ClimaxCommand::scan))
                        .then(Commands.literal("force").executes(ClimaxCommand::force))
                        .then(Commands.literal("ley")
                                .executes(ctx -> ley(ctx, false))
                                .then(Commands.literal("force").executes(ctx -> ley(ctx, true))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        PhaseData data = PhaseData.get(level);
        ClimaxTable table = DesignData.climax();
        int radius = table.neighborRadiusChunks();
        int side = radius * 2 + 1;

        send(source, "§a[極相]§r 台帳 " + data.overgrownLedgerSize() + " チャンク / うち極相 "
                + data.climaxLedgerSize());
        send(source, "  条件: 周囲 " + radius + " チャンク(" + side + "×" + side + " = " + (side * side)
                + ")が過繁茂以上 / 過繁茂になってから "
                + String.format("%.1f", table.minAgeTicks() / 24000.0D) + " 日(" + table.minAgeTicks() + " tick)");
        send(source, "  通常 Mob の自然湧き: " + (table.naturalSpawns() ? "する" : "しない"));
        send(source, "  全層置換: " + (table.deepConversion() ? "する" : "§cしない§r") + " / 済 "
                + data.deepConvertedLedgerSize() + " チャンク / 待ち " + ClimaxDeepConverter.pending()
                + " チャンク(" + table.deepConversionBlocksPerTick() + " マス/tick)");
        send(source, "  地脈: 網目 " + (int) table.leyVein().cellSize() + " ブロック / 幅 "
                + table.leyVein().width() + " / 深さ " + table.leyVein().depth()
                + " —— 近くの地脈へは §e/ov climax ley§r");
        send(source, "  対応表に無いブロックを覆う確率: "
                + String.format("%.0f%%", table.overgrownLayerChance() * 100.0D));

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 1;
        }
        float deep = Erosion.percent(player);
        send(source, "  浸食度 " + String.format("%.1f", deep) + "% → 最大体力 "
                + String.format("%.1f", player.getMaxHealth()) + " / 経験値 ×"
                + String.format("%.2f", 1.0D - table.xpReduction() * table.scale(deep))
                + " / 装備の汚染 ×" + String.format("%.2f", Erosion.gearContaminationFactor(player)));
        ChunkPos pos = new ChunkPos(player.blockPosition());
        long since = data.overgrownSinceTick(pos);
        send(source, "  このチャンク [" + pos.x + ", " + pos.z + "] バイオーム="
                + (ModBiomes.isClimax(player.level().getBiome(player.blockPosition())) ? "極相"
                        : ModBiomes.isFullyOvergrown(player.level().getBiome(player.blockPosition())) ? "過繁茂"
                        : ModBiomes.isOvergrownLike(player.level().getBiome(player.blockPosition())) ? "繁茂" : "その他"));
        if (since < 0L) {
            send(source, "  §c台帳に無い§r(まだ過繁茂になっていない / 旧セーブでまだ拾い直していない)");
            return 1;
        }
        if (data.isClimaxChunk(pos)) {
            send(source, "  台帳では§a既に極相§r / 全層置換は "
                    + (data.isDeepConverted(pos) ? "§a済み§r" : "§eまだ§r"));
            return 1;
        }
        long age = level.getGameTime() - since;
        int missing = data.countMissingNeighbours(pos, radius);
        send(source, "  齢 " + String.format("%.2f", age / 24000.0D) + " 日 / 必要 "
                + String.format("%.1f", table.minAgeTicks() / 24000.0D) + " 日 → "
                + (age >= table.minAgeTicks() ? "§a足りている§r" : "§c足りない§r"));
        send(source, "  周囲の欠け " + missing + " チャンク → " + (missing == 0 ? "§a埋まっている§r" : "§c埋まっていない§r"));
        return 1;
    }

    private static int scan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int promoted = ClimaxTicker.promoteNow(source.getLevel());
        send(source, "§a[極相]§r " + promoted + " チャンクを極相へ上げた"
                + (promoted > 0 ? "(読み込まれていないチャンクは台帳の印だけ)" : ""));
        return promoted;
    }

    private static int force(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("プレイヤーから実行してください"));
            return 0;
        }
        makeClimax(source, player.serverLevel(), new ChunkPos(player.blockPosition()));
        return 1;
    }

    /**
     * <b>いちばん近い地脈へ飛ぶ。</b>
     *
     * <p>地脈の場は座標だけの決定的な関数なので、<b>チャンクを読み込まずに</b>どこに通っているかが
     * 分かる。地脈が走っていないチャンクを極相にしても地脈ブロックは 1 つも出ないので、
     * 「極相にしたのに地脈が無い」ときはまずこれで正しい場所へ行くこと。
     *
     * @param force 着いた先のチャンクも極相にして、地脈ブロックを実際に出すか
     */
    private static int ley(CommandContext<CommandSourceStack> ctx, boolean force) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("プレイヤーから実行してください"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        ClimaxTable.LeyVein spec = DesignData.climax().leyVein();
        if (spec.width() <= 0.0D) {
            source.sendFailure(Component.literal("climax.json の leyVein.width が 0 なので地脈が存在しません"));
            return 0;
        }
        int fromX = player.getBlockX();
        int fromZ = player.getBlockZ();
        int[] hit = LeyVeinField.nearestVein(level.getSeed(), fromX, fromZ, LEY_SEARCH_RADIUS);
        if (hit == null) {
            source.sendFailure(Component.literal(
                    "半径 " + LEY_SEARCH_RADIUS + " ブロックに地脈が見つかりませんでした(leyVein の設定を確認)"));
            return 0;
        }
        int x = hit[0];
        int z = hit[1];
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int distance = Math.max(Math.abs(x - fromX), Math.abs(z - fromZ));
        player.teleportTo(level, x + 0.5D, y, z + 0.5D, player.getYRot(), player.getXRot());

        ChunkPos pos = new ChunkPos(new BlockPos(x, y, z));
        send(source, "§a[地脈]§r " + distance + " ブロック先の地脈へ飛んだ → [" + x + ", " + y + ", " + z
                + "] チャンク [" + pos.x + ", " + pos.z + "]");
        if (force) {
            makeClimax(source, level, pos);
        } else if (!PhaseData.get(level).isClimaxChunk(pos)) {
            send(source, "  このチャンクはまだ極相ではないので地脈ブロックは出ていない → §e/ov climax ley force§r");
        }
        send(source, "  地脈ブロック " + countVeins(level, pos) + " 個(このチャンク内)");
        return 1;
    }

    /** そのチャンクを極相にして、地脈を敷き、全層置換もその場で終わらせる。 */
    private static void makeClimax(CommandSourceStack source, ServerLevel level, ChunkPos pos) {
        PhaseData data = PhaseData.get(level);
        data.markClimaxChunk(level, pos);
        OvergrownBiomeConverter.forceClimax(level, pos);
        // コマンドから呼ぶときは tick を分けずにその場で終わらせる(結果をすぐ見たいので)。
        int replaced = ClimaxDeepConverter.convertNow(level, pos);
        send(source, "§a[極相]§r チャンク [" + pos.x + ", " + pos.z + "] を極相にした / 全層置換 "
                + replaced + " ブロック");
    }

    /** そのチャンクに地脈ブロックが何個あるか(確認用)。 */
    private static int countVeins(ServerLevel level, ChunkPos pos) {
        if (!level.hasChunk(pos.x, pos.z)) {
            return 0;
        }
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = pos.getMinBlockX() + dx;
                int z = pos.getMinBlockZ() + dz;
                int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                // 木の下に敷かれることがあるので、地表から少し深めまで数える
                // (LeyVeinPlacer は木の高さぶん降りてから地面に敷く)。
                for (int dy = 0; dy < 56; dy++) {
                    cursor.set(x, top - dy, z);
                    if (level.getBlockState(cursor).is(ModBlocks.LEY_VEIN.get())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private ClimaxCommand() {
    }
}
