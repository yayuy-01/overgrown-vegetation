package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 植物側の Mob が絡む根の縄を伝って崖を上る手当て(2026-11-20 崖の汚染。ユーザー案「植物側の Mob が根の幕を登ってくる」)。
 *
 * <p>バニラの地上の経路探索は、はしご・縄の縦の登りを経路に入れない(崖の上の相手へは「崖の下のいちばん近い所」までの経路になって止まる)。
 * そこで最小限の手当てとして、次の Mob だけをここで動かす:
 * <ol>
 *   <li><b>探す</b>(10 tick に 1 回): 植物側の Mob で、相手が {@code climb.seekMinRise} 段以上上にいて、経路が相手へ届いていない
 *   (経路が無い・終わった・届かない経路)。まわり {@code climb.seekRadius} の足元の高さに、上へ続く絡む根と、その手前の立てるマスを探す。</li>
 *   <li><b>根元へ歩く</b>: 手前のマスが遠ければ経路で歩かせる。</li>
 *   <li><b>押し付ける</b>: 近づいたら経路を止め、根へ向けて毎 tick 動かす(MoveControl の行き先は 1 tick しか効かないので毎 tick 渡す)。
 *   横にぶつかると {@link RootClimb} で登る。上りきって相手と同じ高さに来たら、相手へ向けて押して縁を越える。</li>
 *   <li>上りきった・相手がいなくなった・{@code climb.giveUpTicks} を過ぎたらやめる(やめた後 100 tick は探さない)。</li>
 * </ol>
 * 群れ(swarm・horde)の独自の移動や、種ごとの Goal とぶつかる所は確かめていない(GameTest は 1 種)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RootClimbAssist {
    private RootClimbAssist() {
    }

    static final class State {
        BlockPos root;
        BlockPos stand;
        int ticks;
        double bestY = -1e9;
        int cooldownUntil;
        boolean engaged;
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();

    /** テスト用: 今、縄を使っている Mob か。 */
    public static boolean engaged(Mob m) {
        State s = STATES.get(m);
        return s != null && s.engaged;
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob m) || !(m.level() instanceof ServerLevel level)) return;
        State s = STATES.get(m);
        if (s == null || !s.engaged) {
            if ((m.tickCount + m.getId()) % 10 != 0) return;
            if (s != null && m.tickCount < s.cooldownUntil) return;
            if (!PlantSideRules.isPlantSide(m) || m.isNoAi() || m.isPassenger()) return;
            tryEngage(level, m, s);
            return;
        }
        drive(level, m, s);
    }

    static void tryEngage(ServerLevel level, Mob m, State prev) {
        CliffTable.Climb c = DesignData.cliff().climb;
        LivingEntity target = m.getTarget();
        if (target == null || !target.isAlive()) return;
        if (target.getY() - m.getY() < c.seekMinRise) return;
        Path path = m.getNavigation().getPath();
        if (path != null && !path.isDone() && path.canReach()) return; // ふつうに歩いて届く
        BlockPos[] found;
        BlockPos near = ropeInFront(level, m);
        if (near != null) {
            found = new BlockPos[]{near, m.blockPosition()}; // もう縄に取り付いている(縄の途中の段で止まった・落ちかけ): その場から続ける
        } else {
            if (!m.onGround()) return;
            found = findRope(level, m, c.seekRadius, target);
        }
        if (found == null) return;
        State s = prev != null ? prev : new State();
        s.root = found[0];
        s.stand = found[1];
        s.ticks = 0;
        s.bestY = m.getY();
        s.engaged = true;
        STATES.put(m, s);
    }

    /** まわりの、上へ続く絡む根(2 段以上)と、その手前の立てるマス。相手に近いほう・自分に近いほうを選ぶ。 */
    static BlockPos[] findRope(ServerLevel level, Mob m, int radius, LivingEntity target) {
        BlockPos feet = m.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 3; dy++) { // 縄の先が地面から少し浮いていても、跳べば届く高さ(3 段)まで
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    p.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!isRoot(level, p) || !ropeAbove(level, p)) continue;
                    {
                        // 立つのは縄の外側(面と反対の側)だけ。縄の横(面の列)のすき間から登ると、ゆらいだ上の段の根に頭がつかえる
                        BlockPos stand = p.relative(outward(level, p, m));
                        for (int i = 0; i < 3 && !standable(level, stand); i++) stand = stand.below(); // 浮いた縄の先の下の地面
                        if (!standable(level, stand)) continue;
                        double score = stand.distSqr(feet) + 0.25D * stand.distSqr(target.blockPosition());
                        if (score < bestScore) {
                            bestScore = score;
                            best = new BlockPos[]{p.immutable(), stand};
                        }
                    }
                }
            }
        }
        return best;
    }

    /** p の上 3 段のうち 2 段以上で、真上か斜め上(まわり 1 マス)に絡む根が続くか(縄はゆらいで横へずれるので、真上だけでは見ない)。 */
    static boolean ropeAbove(ServerLevel level, BlockPos p) {
        int levels = 0;
        for (int dy = 1; dy <= 3; dy++) {
            boolean any = false;
            for (int dx = -1; dx <= 1 && !any; dx++) for (int dz = -1; dz <= 1 && !any; dz++) any = isRoot(level, p.offset(dx, dy, dz));
            if (any) levels++;
        }
        return levels >= 2;
    }

    static boolean isRoot(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).is(OrganBlocks.TANGLED_ROOT.get());
    }

    /** 立てるマス: 足と頭が空き、下が固い。 */
    static boolean standable(ServerLevel level, BlockPos p) {
        return !level.getBlockState(p).blocksMotion() && !level.getBlockState(p.above()).blocksMotion()
                && level.getBlockState(p.below()).blocksMotion();
    }

    static void drive(ServerLevel level, Mob m, State s) {
        CliffTable.Climb c = DesignData.cliff().climb;
        LivingEntity target = m.getTarget();
        s.ticks++;
        boolean onTop = target != null && m.onGround() && m.getY() >= target.getY() - 1.0D;
        if (target == null || !target.isAlive() || s.ticks > c.giveUpTicks || onTop) {
            s.engaged = false;
            s.cooldownUntil = m.tickCount + (onTop ? 20 : 100);
            return;
        }
        double sx = s.stand.getX() + 0.5D, sz = s.stand.getZ() + 0.5D;
        double horiz = (m.getX() - sx) * (m.getX() - sx) + (m.getZ() - sz) * (m.getZ() - sz);
        boolean onRope = RootClimb.touching(m);
        if (!onRope && horiz > 2.0D * 2.0D) {
            // 根元へ歩く。遠ければ経路で、近ければ(縄を探す半径の中)まっすぐ。種の Goal が相手へ経路を引き直して取り合うので、
            // 近い間は経路を止めて MoveControl に毎 tick 渡す(行き先は 1 tick しか効かない)
            if (horiz > 10.0D * 10.0D) {
                if (s.ticks % 10 == 1) m.getNavigation().moveTo(sx, s.stand.getY(), sz, 1.0D);
            } else {
                m.getNavigation().stop();
                m.getMoveControl().setWantedPosition(sx, s.stand.getY(), sz, 1.0D);
            }
            return;
        }
        // 縄に沿って登る(毎 tick)。縄の外側(面と反対の側)のマスに体を置き、跳ぶ(= はしごの上り)。縄の上の端を越えたら相手へ
        m.getNavigation().stop();
        if (m.getY() > s.bestY + 0.5D) {
            s.bestY = m.getY();
            s.ticks = Math.min(s.ticks, c.giveUpTicks / 2); // 登れている間は諦めない
        }
        BlockPos front = ropeInFront(level, m);
        double tx, ty, tz;
        BlockPos head = BlockPos.containing(m.getX(), m.getBoundingBox().maxY + 0.2D, m.getZ());
        if (isRoot(level, head)) {
            // 縄の列のすき間に入り込み、頭の上が根でつかえている: 先に縄の外側へ出る(ゆらいだ縄の段の下にはまる。クライアントで踏んだ)
            Direction out = outward(level, head, m);
            tx = head.getX() + 0.5D + out.getStepX();
            ty = m.getY();
            tz = head.getZ() + 0.5D + out.getStepZ();
            net.minecraft.world.phys.Vec3 hv = m.getDeltaMovement();
            if (hv.y < 0.0D) m.setDeltaMovement(hv.x, 0.0D, hv.z); // 出る間も根にしがみつく
            m.resetFallDistance();
        } else if (front != null) {
            Direction out = outward(level, front, m);
            tx = front.getX() + 0.5D + out.getStepX();
            ty = front.getY() + 1.0D;
            tz = front.getZ() + 0.5D + out.getStepZ();
            if (m.getY() < target.getY()) {
                m.setJumping(true);
                // しがみつく: 縄がゆらいで横へずれ、体が根から一瞬離れても(はしごの扱いが切れても)落ちずに登り続ける。
                // 上りの速さははしごの式と同じ正味((上りの縦 − 重力) × 0.98)。植物側の Mob は根を伝うのが得意、という設定。
                net.minecraft.world.phys.Vec3 v = m.getDeltaMovement();
                double climb = (RootClimb.riseFor(m) - m.getGravity()) * 0.98D;
                if (v.y < climb) m.setDeltaMovement(v.x, climb, v.z);
                m.resetFallDistance();
            }
        } else if (onRope || !m.onGround()) {
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
        } else {
            tx = s.root.getX() + 0.5D;
            ty = s.root.getY() + 1.0D;
            tz = s.root.getZ() + 0.5D;
        }
        m.getMoveControl().setWantedPosition(tx, ty, tz, 1.0D);
        m.getLookControl().setLookAt(tx, ty, tz);
    }

    /**
     * 縄の根 q の外側(空いていて、反対側が固い向き)。崖の面から離れる向き。見つからなければ q から体への向き。
     * 縄のマスそのものへ入ると、ゆらいだ上の段の根に頭がつかえて登れない(GameTest で踏んだ)ので、縄の外側に沿って登らせる。
     */
    static Direction outward(ServerLevel level, BlockPos q, Mob m) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(q.relative(d)).blocksMotion() && level.getBlockState(q.relative(d.getOpposite())).blocksMotion()) return d;
        }
        return Direction.getNearest(m.getX() - (q.getX() + 0.5D), 0.0D, m.getZ() - (q.getZ() + 0.5D));
    }

    /**
     * 押し付ける先の絡む根:体のまわり 1 マス(斜めも)の、足元〜頭の上 1 段の根のうち、いちばん上の段の、体にいちばん近いもの
     * (縄はゆらいで横へずれるので、上の段の根へ寄っていけば縄に沿って登れる)。無ければ null。
     */
    static BlockPos ropeInFront(ServerLevel level, Mob m) {
        BlockPos feet = m.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = 3; dy >= 0; dy--) { // 頭の上 2 段まで(浮いた縄の先へ跳びつく)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos q = feet.offset(dx, dy, dz);
                    if (!isRoot(level, q)) continue;
                    double ddx = q.getX() + 0.5D - m.getX(), ddz = q.getZ() + 0.5D - m.getZ();
                    double d = ddx * ddx + ddz * ddz;
                    if (d < bestD) {
                        bestD = d;
                        best = q;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
