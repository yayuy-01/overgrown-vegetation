package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 牛の 1 段目の (c)「歩く発生源」(2026-11-13、能力の第 2 回。仕様 Vault「G1 牛(1 段目)」3 節【決定 11-09・数は仮】)。
 * 背の袋の列が、歩いた升の浮遊を少しずつ上げる。止まっている間は出さない(10-22 の「胞子袋の牛」{@code parked/…/SporesacCow} の働きを移した)。
 *
 * <ul>
 *   <li>{@value #EMIT_EVERY} tick ごとに、その間に {@value #MIN_MOVE} ブロック以上動いていれば、今いる升(チャンク)へ点の放出
 *   ({@link SporeFieldServer#emitPoint})。量は 1 日歩き続けて過繁茂 1 チャンクの 1 日の放出の {@value #PER_DAY} 倍。</li>
 *   <li>前触れ: 歩いている間、背の袋から胞子の粒が後ろへ漂う({@value #TRAIL_EVERY} tick ごと。見えている間ずっと)。</li>
 * </ul>
 * Mob が 1 つ持つ状態(サーバーだけ)。数値はすべて【仮】。
 */
public final class G1WalkingSource {

    public static final int EMIT_EVERY = 100;
    public static final double MIN_MOVE = 0.5D;
    /** 1 日(24000 tick)歩き続けたときの放出(過繁茂 1 チャンクの 1 日の放出が 1)。10-22 の 0.5 を引き継ぐ。 */
    public static final double PER_DAY = 0.5D;
    static final int TRAIL_EVERY = 8;

    private double lastX, lastZ;
    private boolean started;
    private int emits;

    /** 放出した回数(GameTest が読む。場の値は列に積まれてから反映されるので、回数で確かめる)。 */
    public int emits() {
        return emits;
    }

    /** Mob の tick から(サーバー)。 */
    public void tick(DevelopedParasitized mob) {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) return;
        if (!started) {
            started = true;
            lastX = mob.getX();
            lastZ = mob.getZ();
        }
        if (mob.tickCount % TRAIL_EVERY == 0 && mob.isWalkingNow()) {
            // 背の袋の列(体の後ろ寄り・背の高さ)から後ろへ漂う粒
            Vec3 back = Vec3.directionFromRotation(0.0F, mob.yBodyRot).scale(-0.35D);
            level.sendParticles(ModParticles.SPORE_JET.get(), mob.getX() + back.x, mob.getY() + mob.getBbHeight() * 0.95D, mob.getZ() + back.z,
                    2, 0.25D, 0.1D, 0.25D, 0.004D);
        }
        if (mob.tickCount % EMIT_EVERY != 0) return;
        double dx = mob.getX() - lastX, dz = mob.getZ() - lastZ;
        boolean moved = dx * dx + dz * dz >= MIN_MOVE * MIN_MOVE;
        lastX = mob.getX();
        lastZ = mob.getZ();
        if (moved && !mob.isNoAi()) {
            SporeFieldServer.emitPoint(level, mob.blockPosition(), PER_DAY * EMIT_EVERY / 24000.0D);
            emits++;
        }
    }
}
