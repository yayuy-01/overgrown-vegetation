package com.yayuy.overgrownvegetation.ecosystem.life;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 確かめ用: {@code /ov perf eco den}(2026-11-18、行動の段)。
 * <ul>
 *   <li>(引数なし)まわり 48 ブロックの洞の数・中の数・まわりの G2・G3 の数と上限</li>
 *   <li>{@code stock <種> <数>} いちばん近い洞(16 ブロック)に詰める / {@code release} 出す(這い出る)</li>
 *   <li>{@code scan} 今いるチャンクの倒木の端を洞に変える(後付けを今すぐ)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EcoDenCommand {

    private EcoDenCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov").then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                .then(Commands.literal("eco").then(Commands.literal("den")
                        .executes(ctx -> show(ctx.getSource()))
                        .then(Commands.literal("stock").then(Commands.argument("species", StringArgumentType.word())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, EcoDenBlockEntity.CAPACITY))
                                        .executes(ctx -> stock(ctx.getSource(), StringArgumentType.getString(ctx, "species"), IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("release").executes(ctx -> release(ctx.getSource())))
                        .then(Commands.literal("scan").executes(ctx -> scan(ctx.getSource())))))));
    }

    private static int show(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        BlockPos p = BlockPos.containing(src.getPosition());
        int[] dens = {0, 0};
        level.getPoiManager().getInRange(h -> h.is(EcoBlocks.DEN_POI.getKey()), p, EcoDens.DEN_RADIUS, PoiManager.Occupancy.ANY).forEach(r -> {
            dens[0]++;
            if (level.getBlockEntity(r.getPos()) instanceof EcoDenBlockEntity d) dens[1] += d.count();
        });
        int[] live = EcoDens.live(level, p.getX(), p.getZ());
        String s = String.format(Locale.ROOT, "洞 %d 個(中に %d 体)・まわりの G2 %d / %d・G3 %d / %d・後付けで見たチャンク %d・回った %d・見た %d・汚染でない %d・読み込みなし %d・洞にした %d・端 %d・乱数で外れ %d・空けられない %d・床なし %d・空けた %d・塞いだ物 %s",
                dens[0], dens[1], live[2], EcoDens.cap(level, 2, p), live[3], EcoDens.cap(level, 3, p), EcoDens.Scanned.get(level).done.size(), EcoDens.STATS[0], EcoDens.STATS[1], EcoDens.STATS[2], EcoDens.STATS[3], EcoDens.STATS[4], EcoDens.STATS[5], EcoDens.STATS[6], EcoDens.STATS[7], EcoDens.STATS[8], EcoDens.STATS[9], EcoDens.BLOCKED);
        src.sendSuccess(() -> Component.literal(s), false);
        return dens[0];
    }

    private static BlockPos nearest(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        return level.getPoiManager().findClosest(h -> h.is(EcoBlocks.DEN_POI.getKey()), BlockPos.containing(src.getPosition()), 16, PoiManager.Occupancy.ANY).orElse(null);
    }

    private static int stock(CommandSourceStack src, String species, int n) {
        ServerLevel level = src.getServer().overworld();
        BlockPos pos = nearest(src);
        EntityType<?> type = EcoDens.type(species);
        if (pos == null || type == null || !(level.getBlockEntity(pos) instanceof EcoDenBlockEntity d)) {
            src.sendFailure(Component.literal("16 ブロック以内に洞が無いか、種の名前が違う"));
            return 0;
        }
        d.stock(type, n, level.getGameTime());
        src.sendSuccess(() -> Component.literal("洞 " + pos.toShortString() + " に " + species + " を " + d.count() + " 体"), false);
        return d.count();
    }

    private static int release(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        BlockPos pos = nearest(src);
        if (pos == null || !(level.getBlockEntity(pos) instanceof EcoDenBlockEntity d)) {
            src.sendFailure(Component.literal("16 ブロック以内に洞が無い"));
            return 0;
        }
        BlockState st = level.getBlockState(pos);
        Direction out = st.getValue(EcoDenBlock.FACING);
        int n = d.releaseAll(level, EcoDenBlock.mouth(pos, st), EcoDenBlock.outYaw(st), m -> EcoDens.crawlOut(m, out));
        src.sendSuccess(() -> Component.literal("洞 " + pos.toShortString() + " から " + n + " 体出した"), false);
        return n;
    }

    private static int scan(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        BlockPos p = BlockPos.containing(src.getPosition());
        var chunk = level.getChunkAt(p);
        int n = EcoDens.convertChunk(level, chunk);
        src.sendSuccess(() -> Component.literal("このチャンクで洞にした端 " + n), false);
        return n;
    }
}
