package com.yayuy.overgrownvegetation.sky;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 積乱雲を探すコマンド(OP 専用。2026-10-17 ユーザー「見つからないので積乱雲を見つけられるコマンドを用意してほしい」)。
 *
 * <ul>
 *   <li>{@code /ov storm} — 実行した所から {@link #RANGE} ブロック以内の積乱雲を近い順に 5 つ(向き・距離・天辺の高さ・育ち・巨大か)。
 *       無ければ、なぜ無いか(夜・雲の少ない日・設定)と、出す方法。</li>
 *   <li>{@code /ov storm tp} — いちばん近い積乱雲を横から見られる所(雲の海より上の y 520、雲から少し離れた所)へ移り、雲の方を向く。
 *       サバイバル・アドベンチャーなら落下を遅くする効果を 60 秒付ける(高い所から落ちて死なないように)。</li>
 * </ul>
 * 積乱雲の場所はクライアントの描画と同じ式({@link CloudSky}・{@link CloudField})で、形の値はサーバーの設計値 {@code sky.json storms}(クライアントへ送る)。
 * 2026-11-09 雲の段階 2a で parked から戻した(中身はそのまま。見に行く高さは雲の海が y 350 になったので 520 のまま)。
 * 見えるのは各プレイヤーの描画(ボクセルの雲の設定がオン、バニラの雲の設定がオフでない)のとき。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StormCommand {
    /** 探す範囲(ブロック)。 */
    static final double RANGE = 8000.0;
    /** 見に行く高さ(雲の海の天辺 y 350・裾野より上)。 */
    static final double VIEW_Y = 520.0;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("storm")
                        .requires(source -> source.hasPermission(2))
                        .executes(StormCommand::list)
                        .then(Commands.literal("tp").executes(StormCommand::tp))
                        .then(Commands.literal("here").executes(StormCommand::here))));
    }

    /** 積乱雲 1 つの世界の位置(風で流れた分を足した、今の真ん中)。 */
    private record Found(CloudField.Storm storm, double x, double z, double dist) {
    }

    private static List<Found> find(ServerLevel level, Vec3 from) {
        return find(level, from, false);
    }

    /** obstacles = 入道雲と雲の橋も含める(見る所を選ぶときの障りに。11-09: 入道雲を数えず、入道雲のすぐ脇へ移ることがあった)。 */
    private static List<Found> find(ServerLevel level, Vec3 from, boolean obstacles) {
        long gameTime = level.getGameTime();
        float coverage = CloudSky.coverage(level, 1.0F);
        double fx = CloudField.fieldX(from.x, gameTime, 1.0F);
        double fz = CloudField.fieldZ(from.z, gameTime, 1.0F);
        List<Found> out = new ArrayList<>();
        for (CloudField.Storm s : CloudSky.storms(level, coverage, fx - RANGE, fz - RANGE, fx + RANGE, fz + RANGE)) {
            if (s.kind() != 0 && !obstacles) {
                continue;
            }
            double x = s.x() + CloudField.offsetX(gameTime);
            double z = s.z() + CloudField.offsetZ(gameTime);
            double d = Math.hypot(x - from.x, z - from.z);
            if (d <= RANGE) {
                out.add(new Found(s, x, z, d));
            }
        }
        out.sort(Comparator.comparingDouble(Found::dist));
        return out;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 from = source.getPosition();
        List<Found> found = find(level, from);
        float coverage = CloudSky.coverage(level, 1.0F);
        float chance = CloudSky.stormChance(coverage);
        if (found.isEmpty()) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.storm.none", (int) RANGE).append(" ").append(reason(level, coverage, chance)));
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.storm.hint"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.storm.header", found.size(),
                String.format("%.2f", coverage), String.format("%.2f", chance), CloudSky.style()), false);
        for (int i = 0; i < Math.min(5, found.size()); i++) {
            Found f = found.get(i);
            int index = i + 1;
            MutableComponent line = Component.translatable("commands.overgrown_vegetation.storm.entry", index,
                    Component.translatable("commands.overgrown_vegetation.storm.dir." + direction(f.x - from.x, f.z - from.z)),
                    Math.round(f.dist), f.storm.topY(), Math.round(f.storm.maturity() * 100.0F), Math.round(f.x), Math.round(f.z));
            if (f.storm.giant()) {
                line.append(Component.translatable("commands.overgrown_vegetation.storm.giant"));
            }
            source.sendSuccess(() -> line, false);
        }
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.storm.tp_hint")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ov storm tp"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/ov storm tp")))), false);
        return found.size();
    }

    /**
     * {@code /ov storm here}(11-09 段階 2a): 今いる所でサーバーが雲をどう見ているか(雲の床の柱・積乱雲の塊の中か・積乱雲の下か・真上の積乱雲)。
     * 空の世界の土台({@link CloudSky} の口)を自分のゲームで確かめる用。開発の確かめ用なので文は日本語のまま。
     */
    private static int here(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 p = source.getPosition();
        int[] floor = CloudSky.floorAt(level, p.x, p.z, 1.0F);
        int[] span = CloudSky.stormSpan(level, p.x, p.z, 1.0F);
        CloudField.Storm s = CloudSky.stormAt(level, p.x, p.z, 1.0F, true);
        String text = String.format("雲の量 %.2f / 雲の床 %s / 積乱雲の塊 %s / 塊の中 %s / 積乱雲の下 %s / 雲の中 %s%s",
                CloudSky.coverage(level, 1.0F), floor == null ? "なし" : "y " + floor[0] + "〜" + floor[1], span == null ? "なし" : "y " + span[0] + "〜" + span[1],
                CloudSky.insideStorm(level, p.x, p.y, p.z, 1.0F) ? "はい" : "いいえ", CloudSky.underStorm(level, p.x, p.y, p.z, 1.0F) ? "はい" : "いいえ",
                CloudSky.insideCloud(level, p.x, p.y, p.z, 1.0F) ? "はい" : "いいえ",
                s == null ? "" : String.format(" / 真上の雲: 天辺 y %d・育ち %d%%%s%s", s.topY(), Math.round(s.maturity() * 100.0F), s.giant() ? "・巨大" : "", s.congestus() ? "・入道雲" : ""));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** 積乱雲が無いわけ。 */
    private static Component reason(ServerLevel level, float coverage, float chance) {
        if (CloudShape.current().storms() <= 0.0F) {
            return Component.translatable("commands.overgrown_vegetation.storm.reason.off");
        }
        if (chance <= 0.0F) {
            return Component.translatable("commands.overgrown_vegetation.storm.reason.clear", String.format("%.2f", coverage));
        }
        return Component.translatable("commands.overgrown_vegetation.storm.reason.luck", String.format("%.2f", chance));
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 from = source.getPosition();
        List<Found> found = find(level, from);
        if (found.isEmpty()) {
            float coverage = CloudSky.coverage(level, 1.0F);
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.storm.none", (int) RANGE)
                    .append(" ").append(reason(level, coverage, CloudSky.stormChance(coverage))));
            return 0;
        }
        Found f = found.get(0);
        List<Found> around = find(level, from, true);
        // 雲の真ん中から「雲の広がり + 350〜750」離れた所。今いる方の向きから順に 16 方位を試し、ほかの雲の中でなく、雲までの間に雲が無い所を選ぶ
        // (最初は今いる方へまっすぐ離れただけで、隣の積乱雲の中に出た)。
        long gameTime = level.getGameTime();
        double base = Math.atan2(from.z - f.z, from.x - f.x);
        double[] spot = null;
        double away = f.storm.reach() + 1000.0;
        // 11-09 段階 2a-2 ユーザー決定: 雲の広がりから 800〜1200 ブロック(前は 350〜750 で、大きな山が画面を埋めた)。
        for (double extra = 800.0; extra <= 1200.0 && spot == null; extra += 200.0) {
            for (int k = 0; k < 16 && spot == null; k++) {
                double a = base + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 8.0;
                double d = f.storm.reach() + extra;
                double cx = f.x + Math.cos(a) * d;
                double cz = f.z + Math.sin(a) * d;
                if (clear(around, f, cx, cz, gameTime)) {
                    spot = new double[]{cx, cz};
                    away = d;
                }
            }
        }
        if (spot == null) {
            spot = new double[]{f.x + Math.cos(base) * away, f.z + Math.sin(base) * away};
        }
        double x = spot[0];
        double z = spot[1];
        final double distance = away;
        float yaw = (float) Math.toDegrees(Math.atan2(-(f.x - x), f.z - z));
        float pitch = (float) -Math.toDegrees(Math.atan2((f.storm.topY() + CloudField.LOW_BASE) * 0.5 - VIEW_Y, away)) * 0.6F;
        if (source.getEntity() == null) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.storm.need_entity"));
            return 0;
        }
        source.getEntity().teleportTo(level, x, VIEW_Y, z, java.util.Set.of(), yaw, Mth.clamp(pitch, -60.0F, 60.0F));
        boolean slow = false;
        if (source.getEntity() instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 60, 0));
            slow = true;
        }
        boolean slowFall = slow;
        source.sendSuccess(() -> {
            MutableComponent c = Component.translatable("commands.overgrown_vegetation.storm.teleported", Math.round(distance), f.storm.topY(),
                    Math.round(x), Math.round(VIEW_Y), Math.round(z));
            if (slowFall) {
                c.append(Component.translatable("commands.overgrown_vegetation.storm.slow_fall"));
            }
            return c;
        }, false);
        return 1;
    }

    /** 見る位置 (x, VIEW_Y, z) がどの雲の中でもなく、目当ての雲の手前 3 割までの間にも雲が無いか(世界の座標)。 */
    private static boolean clear(List<Found> found, Found target, double x, double z, long gameTime) {
        double wx = CloudField.offsetX(gameTime);
        double wz = CloudField.offsetZ(gameTime);
        double ty = (target.storm.topY() + CloudField.LOW_BASE) * 0.5;
        List<CloudField.Storm> all = new ArrayList<>();
        for (Found o : found) {
            all.add(o.storm);
        }
        if (target.storm.style() >= CloudField.STYLE_VALLEY && VIEW_Y < CloudField.LOW_BASE + CloudField.landscape(x - wx, z - wz, all, 1.0F) + 30.0) {
            return false;
        }
        // 見る所のまわり(水平 80・160 ブロック、上下 ±60)にも雲が無いこと(11-09: 線の上だけ確かめていたので、横の塔の壁のすぐ脇に出た)。
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8.0;
            double rr = k % 2 == 0 ? 80.0 : 160.0;
            for (int dy = -60; dy <= 60; dy += 60) {
                double px = x + Math.cos(a) * rr;
                double pz = z + Math.sin(a) * rr;
                for (Found o : found) {
                    double r = o.storm.reach() + 40.0;
                    if (Math.abs(px - o.x) > r || Math.abs(pz - o.z) > r) {
                        continue;
                    }
                    if (CloudField.inside(CloudField.puffs(o.storm), px - wx, VIEW_Y + dy, pz - wz)) {
                        return false;
                    }
                }
            }
        }
        for (int i = 0; i <= 30; i++) {
            double t = i / 30.0 * 0.7;
            double px = Mth.lerp(t, x, target.x);
            double py = Mth.lerp(t, VIEW_Y, ty);
            double pz = Mth.lerp(t, z, target.z);
            for (Found o : found) {
                double r = o.storm.reach() + 40.0;
                if (Math.abs(px - o.x) > r || Math.abs(pz - o.z) > r) {
                    continue;
                }
                if (CloudField.inside(CloudField.puffs(o.storm), px - wx, py, pz - wz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 16 方位(北 = −z、東 = +x)。 */
    private static String direction(double dx, double dz) {
        String[] names = {"n", "nne", "ne", "ene", "e", "ese", "se", "sse", "s", "ssw", "sw", "wsw", "w", "wnw", "nw", "nnw"};
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        int i = Math.floorMod((int) Math.round(a / 22.5), 16);
        return names[i];
    }

    private StormCommand() {
    }
}
