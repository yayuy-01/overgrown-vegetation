package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 縄を登っている間は相手を外さない(2026-11-20 ユーザー決定)。
 *
 * <p>縄を上ること自体は経路({@link RootPath})に任せた。前の最小限の手当て(縄を探して押し付ける・しがみつく)は、11-20 の決定で消した。
 *
 * <p>ここで手当てするのは、相手を外す件だけ。G1 などの相手選びは、上へ離れた相手を途中で外す(見えない・遠い)。
 * 縄に取り付いている植物側の Mob では、その tick に外れた相手を戻す(相手が生きていて同じ次元にいる間)。
 * 地面に下りたら、ふつうの相手選びに戻る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class RootClimbAssist {
    private RootClimbAssist() {
    }

    /** その tick の初めの相手(縄に取り付いている植物側の Mob だけ覚える)。 */
    private static final Map<Mob, LivingEntity> HELD = new WeakHashMap<>();

    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Mob m) || !(m.level() instanceof ServerLevel)) return;
        LivingEntity t = m.getTarget();
        if (t == null || m.onGround() || !PlantSideRules.isPlantSide(m) || !RootClimb.touching(m)) {
            if (!HELD.isEmpty()) HELD.remove(m);
            return;
        }
        HELD.put(m, t);
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob m) || !(m.level() instanceof ServerLevel level)) return;
        cling(level, m);
        if (HELD.isEmpty()) return;
        LivingEntity held = HELD.get(m);
        if (held == null) return;
        if (m.getTarget() == null && held.isAlive() && held.level() == m.level() && RootClimb.touching(m)) {
            m.setTarget(held);
        }
    }

    /**
     * 経路の次の節が縄の節(上の節で、縄に触れる所)なら、縄にしがみついて上る。縄はゆらいで横へずれるので、節と節の間で体が根から
     * 一瞬離れる(はしご扱いが切れる)ことがあり、そのまま落ちて上り直していた(GameTest で踏んだ)。上る速さははしごの式と同じ正味。
     */
    static void cling(ServerLevel level, Mob m) {
        net.minecraft.world.level.pathfinder.Path path = m.getNavigation().getPath();
        if (path == null || path.isDone()) return;
        net.minecraft.core.BlockPos next = path.getNextNodePos();
        double rise = next.getY() - m.getY();
        boolean up = rise > 0.05D;
        // 地面に立っているときは、上の節(縄の取り付き)へ向かうときだけ。宙(縄の上)では横・上の節どちらも
        if (m.onGround() && !up) return;
        if (rise < -0.6D || rise > 6.0D) return; // 縄の途中で少し滑っても(節より下にずれても)掴み直して上る
        if (rise > 1.6D && !RootClimb.touching(m)) return; // 節から遠いときは、根に触れているときだけ(宙を飛ばない)
        double dx = next.getX() + 0.5D - m.getX(), dz = next.getZ() + 0.5D - m.getZ();
        double h2 = dx * dx + dz * dz;
        if (h2 > 1.5D * 1.5D) return;
        if (!touchesRoot(level, next) && !RootClimb.touching(m)) return;
        if (!PlantSideRules.isPlantSide(m)) return;
        net.minecraft.world.phys.Vec3 v = m.getDeltaMovement();
        double vy = v.y;
        double climb = (RootClimb.riseFor(m) - RootClimb.gravityOf(m)) * 0.98D;
        if (up) vy = Math.max(vy, climb);
        else if (!m.onGround()) vy = Math.max(vy, 0.0D); // 横の節: 縄に掴まったまま(滑り落ちない)
        double vx = v.x, vz = v.z;
        if (!m.onGround() && h2 > 0.01D) {
            // 宙の横の動きはバニラだと遅い(飛ぶ速さ 0.02)ので、縄を手繰って節の真ん中へ寄せる
            double h = Math.sqrt(h2), s = Math.min(0.15D, h) / h;
            vx = dx * s;
            vz = dz * s;
        }
        m.setDeltaMovement(vx, vy, vz);
        m.resetFallDistance();
    }

    /** 縄の経路の途中か(次の節が縄に触れる節で、1.5 マス以内)。宙でも経路を進めてよい({@code mixin/GroundPathNavigationRootMixin})。 */
    public static boolean onRopePath(Mob m) {
        if (!(m.level() instanceof ServerLevel level)) return false;
        net.minecraft.world.level.pathfinder.Path path = m.getNavigation().getPath();
        if (path == null || path.isDone()) return false;
        net.minecraft.core.BlockPos next = path.getNextNodePos();
        double dx = next.getX() + 0.5D - m.getX(), dz = next.getZ() + 0.5D - m.getZ(), dy = next.getY() - m.getY();
        return dx * dx + dz * dz <= 2.25D && dy > -1.0D && dy < 2.0D && touchesRoot(level, next);
    }

    /** そのマスの横(足元か腰の高さ)に絡む根があるか。 */
    static boolean touchesRoot(ServerLevel level, net.minecraft.core.BlockPos p) {
        net.minecraft.core.BlockPos.MutableBlockPos q = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(q.set(p.getX() + d.getStepX(), p.getY() + dy, p.getZ() + d.getStepZ())).is(com.yayuy.overgrownvegetation.organ.OrganBlocks.TANGLED_ROOT.get())) return true;
            }
        }
        return false;
    }

    /** テスト用: 縄の上で相手を覚えているか。 */
    public static boolean holding(Mob m) {
        return HELD.containsKey(m);
    }
}
