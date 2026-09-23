package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 環境の地域マップ({@link OvergrowthRegionMap})のデバッグコマンド。OP 専用。
 * <ul>
 *     <li>{@code /ov region} — 現在地の環境と、他の各環境の最寄り地点(距離・座標)を表示。</li>
 *     <li>{@code /ov region tp <jungle|swamp|fungal_swamp>} — その環境の最寄り地点へテレポート。</li>
 *     <li>{@code /ov region corrupt [半径チャンク]} — 現在地の周囲のチャンクを即座に過繁茂バイオームへ
 *     書き換える(地域マップに従った環境になる)。湿地の浸水・棚田のテストに使う。ブロックは汚染しない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RegionCommand {

    /** 最寄り探索の刻み(ブロック)と上限(ブロック)。 */
    private static final int SEARCH_STEP = 32;
    private static final int SEARCH_LIMIT = 8192;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var region = Commands.literal("region")
                .requires(source -> source.hasPermission(2))
                .executes(RegionCommand::show);
        var tp = Commands.literal("tp");
        for (OvergrowthVariant variant : OvergrowthVariant.values()) {
            tp.then(Commands.literal(variant.name().toLowerCase()).executes(ctx -> teleport(ctx, variant)));
        }
        region.then(tp);
        region.then(Commands.literal("corrupt")
                .executes(ctx -> corrupt(ctx, 2))
                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16))
                        .executes(ctx -> corrupt(ctx, IntegerArgumentType.getInteger(ctx, "radiusChunks")))));
        region.then(Commands.literal("convert")
                .executes(ctx -> convert(ctx, 3))
                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16))
                        .executes(ctx -> convert(ctx, IntegerArgumentType.getInteger(ctx, "radiusChunks")))));
        event.getDispatcher().register(Commands.literal("ov").then(region));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        OvergrowthVariant here = OvergrowthRegionMap.variantAt(level, pos.getX(), pos.getZ());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "現在地の環境: " + label(here) + "  (地域サイズ " + OvergrownVegetationConfig.COMMON.overgrowthRegionSize.get()
                        + " ブロック, シード " + level.getSeed() + ")"), false);
        // このチャンクのバイオームと、昇格判定に使う地表(流体を除いた最上部)の汚染列数。
        ChunkPos cp = player.chunkPosition();
        String biomeName = level.getBiome(cp.getMiddleBlockPosition(level.getSeaLevel()))
                .unwrapKey().map(k -> k.location().toString()).orElse("?");
        int corrupted = countCorruptedColumns(level, cp);
        int required = (int) Math.ceil(OvergrownVegetationConfig.COMMON.overgrownBiomeThreshold.get() * 256);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "このチャンク: biome=" + biomeName + "  汚染列 " + corrupted + "/256 (過繁茂に必要 " + required
                        + "、陸チャンクでは未汚染の水底を分母から除く)"), false);
        // 足元の正確な位置のバイオームと、その水の色(バイオーム JSON の water_color)。
        var hereBiome = level.getBiome(pos);
        String hereName = hereBiome.unwrapKey().map(k -> k.location().toString()).orElse("?");
        int waterColor = hereBiome.value().getWaterColor();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "足元の位置: biome=" + hereName + "  water_color=#" + String.format("%06X", waterColor)), false);
        Map<OvergrowthVariant, BlockPos> nearest = findNearest(level, pos, here);
        for (OvergrowthVariant variant : OvergrowthVariant.values()) {
            if (variant == here) {
                continue;
            }
            BlockPos found = nearest.get(variant);
            String line = found == null
                    ? label(variant) + ": " + SEARCH_LIMIT + " ブロック以内に無し"
                    : label(variant) + ": 約 " + (int) Math.sqrt(found.distSqr(pos)) + " ブロック先 (x=" + found.getX()
                            + ", z=" + found.getZ() + ")";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> ctx, OvergrowthVariant target) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        OvergrowthVariant here = OvergrowthRegionMap.variantAt(level, pos.getX(), pos.getZ());
        BlockPos dest;
        if (here == target) {
            dest = pos;
        } else {
            dest = findNearest(level, pos, here).get(target);
            if (dest == null) {
                ctx.getSource().sendFailure(Component.literal(label(target) + " は " + SEARCH_LIMIT + " ブロック以内に見つかりません"));
                return 0;
            }
        }
        // 地域の縁ではなく少し内側へ寄せる(境界がうねっているので、縁だと隣の環境に戻りやすい)。
        BlockPos inner = pushInward(level, pos, dest, target);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, inner.getX(), inner.getZ());
        player.teleportTo(level, inner.getX() + 0.5D, y + 1.0D, inner.getZ() + 0.5D, player.getYRot(), player.getXRot());
        BlockPos shown = inner;
        ctx.getSource().sendSuccess(() -> Component.literal(
                label(target) + " へテレポートしました (x=" + shown.getX() + ", z=" + shown.getZ() + ")"), true);
        return 1;
    }

    private static int corrupt(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos center = player.chunkPosition();
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        OvergrownBiomeConverter.forceOvergrownBatch(level, chunks);
        OvergrowthVariant here = OvergrowthRegionMap.variantAt(level, center);
        int count = chunks.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                count + " チャンクを過繁茂バイオーム(" + label(here) + ")に書き換えました"), true);
        return count;
    }

    /** 周囲のチャンクにバイオーム昇格判定({@link OvergrownBiomeConverter#maybeConvert})を即時に掛ける。 */
    private static int convert(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        ChunkPos center = player.chunkPosition();
        int changed = 0;
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                if (!level.hasChunk(cp.x, cp.z)) {
                    continue;
                }
                total++;
                BlockPos mid = cp.getMiddleBlockPosition(level.getSeaLevel());
                var before = level.getBiome(mid);
                OvergrownBiomeConverter.maybeConvertToOvergrowth(level, cp);
                OvergrownBiomeConverter.maybeConvert(level, cp);
                if (level.getBiome(mid) != before) {
                    changed++;
                }
            }
        }
        int c = changed;
        int t = total;
        ctx.getSource().sendSuccess(() -> Component.literal(
                t + " チャンクを判定し、" + c + " チャンクのバイオームが変わりました"), true);
        return changed;
    }

    /** チャンク地表(流体を除いた最上部)の汚染列数。{@code OvergrownBiomeConverter#maybeConvert} と同じ見方。 */
    private static int countCorruptedColumns(ServerLevel level, ChunkPos cp) {
        var chunk = level.getChunk(cp.x, cp.z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); // ChunkAccess#getHeight は最上部ブロックの Y
                cursor.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z);
                if (chunk.getBlockState(cursor).is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 現在地から渦巻き状に外へ探し、{@code here} 以外の各環境の最寄り地点を返す。 */
    private static Map<OvergrowthVariant, BlockPos> findNearest(ServerLevel level, BlockPos from, OvergrowthVariant here) {
        Map<OvergrowthVariant, BlockPos> result = new EnumMap<>(OvergrowthVariant.class);
        int wanted = OvergrowthVariant.values().length - 1;
        long seed = level.getSeed();
        for (int ring = 1; ring * SEARCH_STEP <= SEARCH_LIMIT && result.size() < wanted; ring++) {
            int r = ring * SEARCH_STEP;
            for (int i = -ring; i <= ring; i++) {
                int off = i * SEARCH_STEP;
                check(seed, from.getX() + off, from.getZ() - r, from, result);
                check(seed, from.getX() + off, from.getZ() + r, from, result);
                check(seed, from.getX() - r, from.getZ() + off, from, result);
                check(seed, from.getX() + r, from.getZ() + off, from, result);
            }
        }
        return result;
    }

    private static void check(long seed, int x, int z, BlockPos from, Map<OvergrowthVariant, BlockPos> result) {
        OvergrowthVariant v = OvergrowthRegionMap.variantAt(seed, x, z);
        BlockPos p = new BlockPos(x, from.getY(), z);
        BlockPos old = result.get(v);
        if (old == null || p.distSqr(from) < old.distSqr(from)) {
            result.put(v, p);
        }
    }

    /** {@code dest} から {@code from} と反対方向へ、環境が変わらない範囲で最大 96 ブロック進めた地点。 */
    private static BlockPos pushInward(ServerLevel level, BlockPos from, BlockPos dest, OvergrowthVariant target) {
        double dx = dest.getX() - from.getX();
        double dz = dest.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0D) {
            return dest;
        }
        dx /= len;
        dz /= len;
        BlockPos best = dest;
        for (int d = 16; d <= 96; d += 16) {
            int x = dest.getX() + (int) Math.round(dx * d);
            int z = dest.getZ() + (int) Math.round(dz * d);
            if (OvergrowthRegionMap.variantAt(level, x, z) != target) {
                break;
            }
            best = new BlockPos(x, dest.getY(), z);
        }
        return best;
    }

    private static String label(@Nullable OvergrowthVariant variant) {
        if (variant == null) {
            return "なし";
        }
        return switch (variant) {
            case JUNGLE -> "密林 (jungle)";
            case SWAMP -> "湿地 (swamp)";
            case FUNGAL_SWAMP -> "菌湿地帯 (fungal_swamp)";
        };
    }

    private RegionCommand() {
    }
}
