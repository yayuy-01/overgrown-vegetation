package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensWatch;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * テストクライアントの監視塔フィロセンスの命令(2026-11-14)。{@link DevBridge} から(サーバーのスレッドで)。
 * <ul>
 *   <li>{@code towerdev state <状態>}: 一番近い塔をその状態へ(CLOSED・OPENING・WATCH・CLOSING・RECOIL・WAKE・COLLAPSE・DEAD など。時間で進む段は進む)。</li>
 *   <li>{@code towerdev lock <x> <y> <z>}: 見張りでその点を見つめる。</li>
 *   <li>{@code towerdev peek <gap|top|side> <x> <y> <z> [lx ly lz]}: 頭の軸をその点へ置いて覗く(見る点は既定で自分の目)。</li>
 *   <li>{@code towerdev free}: 行動を戻す。{@code towerdev info}: 状態と記録。{@code towerdev hit head|root <i>}: 部位を叩く。</li>
 *   <li>{@code towerdev bench <n> <tick> [watch|peek]}: 自分のまわり(半径 18)に n 本立てて、見る行動の 1 tick の全個体の合計の最大・平均を返す。</li>
 * </ul>
 */
final class TowerDev {

    private TowerDev() {
    }

    private static List<PhyllosensEntity> bench;
    private static long benchUntil;
    private static int benchTicks;

    static PhyllosensEntity nearest(ServerLevel level, Vec3 me) {
        PhyllosensEntity best = null;
        for (PhyllosensEntity e : level.getEntitiesOfClass(PhyllosensEntity.class, new AABB(me, me).inflate(64.0D))) {
            if (best == null || e.distanceToSqr(me) < best.distanceToSqr(me)) best = e;
        }
        return best;
    }

    static void run(ServerLevel level, ServerPlayer sp, String[] p, Consumer<String> log) {
        if (p.length == 0) return;
        Vec3 me = sp.position();
        if (p[0].equals("bench")) {
            int n = Integer.parseInt(p[1]);
            benchTicks = Integer.parseInt(p[2]);
            boolean peek = p.length > 3 && p[3].equals("peek");
            bench = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                PhyllosensEntity e = ModPhyllosens.PHYLLOSENS.get().create(level);
                if (e == null) continue;
                e.moveTo(me.x + Math.cos(a) * 18.0D, me.y, me.z + Math.sin(a) * 18.0D, (float) Math.toDegrees(a) + 90.0F, 0.0F);
                level.addFreshEntity(e);
                e.watch().forceWatch();
                if (peek) e.watch().noteSeen(me, level.getGameTime());
                bench.add(e);
            }
            PhyllosensWatch.resetStats();
            benchUntil = level.getGameTime() + benchTicks;
            log.accept("TOWERBENCH 始め " + bench.size());
            return;
        }
        PhyllosensEntity e = nearest(level, me);
        if (e == null) {
            log.accept("TOWERDEV 塔が無い");
            return;
        }
        PhyllosensWatch w = e.watch();
        switch (p[0]) {
            case "state" -> {
                w.devHold = true;
                w.devState(PhyllosensWatch.State.valueOf(p[1].toUpperCase(Locale.ROOT)));
            }
            case "lock" -> w.devLockAt(new Vec3(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            case "peek" -> {
                Vec3 h = new Vec3(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]));
                Vec3 l = p.length > 7 ? new Vec3(Double.parseDouble(p[5]), Double.parseDouble(p[6]), Double.parseDouble(p[7])) : sp.getEyePosition();
                boolean ok = w.devPeek(PhyllosensWatch.PeekKind.valueOf(p[1].toUpperCase(Locale.ROOT)), h, l);
                log.accept("TOWERDEV peek " + (ok ? "届く" : "届かない"));
            }
            case "free" -> w.devHold = false;
            case "hit" -> {
                int i = p[1].equals("head") ? 0 : Integer.parseInt(p[2]);
                e.hitParts().part(i).hurt(level.damageSources().playerAttack(sp), p.length > 3 ? Float.parseFloat(p[3]) : 2.0F);
            }
            default -> {
            }
        }
        Vec3 eye = w.eye();
        log.accept(String.format(Locale.ROOT, "縁 %.2f ", com.yayuy.overgrownvegetation.client.PhyllosensGazeEdge.strength(1.0F)));
        log.accept(String.format(Locale.ROOT, "TOWERDEV %s 状態 %s(%d)覗き %s 効き %.2f 見つめ %.2f 目 (%.1f %.1f %.1f) 塔 (%.1f %.1f %.1f) 向き %.0f 記録 [%s]",
                p[0], w.state(), w.stateTicks(), w.peekKind(), w.peekWeight(), w.lockWeight(), eye.x, eye.y, eye.z, e.getX(), e.getY(), e.getZ(), e.getYRot(), w.note));
    }

    /** 毎 tick(DevBridge から)。計測の終わり。 */
    static void tick(ServerLevel level, Consumer<String> log) {
        if (bench == null || level.getGameTime() < benchUntil) return;
        StringBuilder modes = new StringBuilder();
        for (PhyllosensEntity e : bench) modes.append(' ').append(e.watch().state());
        long t = Math.max(1L, PhyllosensWatch.statTicks);
        log.accept(String.format(Locale.ROOT, "TOWERBENCH %d 本 %d tick 最悪 %.3f ms 平均 %.3f ms 状態%s", bench.size(), PhyllosensWatch.statTicks,
                PhyllosensWatch.statWorst / 1.0e6, PhyllosensWatch.statSum / 1.0e6 / t, modes));
        for (PhyllosensEntity e : bench) e.discard();
        bench = null;
    }
}
