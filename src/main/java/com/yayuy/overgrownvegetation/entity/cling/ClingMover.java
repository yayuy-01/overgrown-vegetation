package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 全面に張り付く Mob の<b>体の動かし方と経路のたどり方</b>(2026-11-05、層 1 の移動の型 ①)。1 体に 1 つ持たせ、
 * サーバーで毎 tick {@link #tick()} を呼ぶ(Mob の {@code customServerAiStep})。{@code travel} は
 * {@link #handlesTravel()} が真の間はバニラの物理を走らせない。
 *
 * <h2>3 つの状態</h2>
 * <ul>
 *     <li>{@link Mode#FLOOR 床} —— 床の上。<b>体はバニラの物理</b>(重力・段差・押し合い・ノックバック)で、向かう先だけを
 *     経路の点から渡す({@code MoveControl})。半ブロック・階段・カーペットの上をそのまま歩ける。</li>
 *     <li>{@link Mode#CLING 張り付き} —— 壁・天井・葉の中。<b>重力と押し出しを止め、点から点へ自分で動かす</b>(当たり判定は回さない)。</li>
 *     <li>{@link Mode#FREE 離れている} —— 跳んでいる・叩き落とされた・水の中。バニラの物理のまま。
 *     床に着く・壁や天井に触れる・葉に入ると、また付く({@link #tryAttach})。水の中では付かない(11-05 ユーザー決定)。</li>
 * </ul>
 * 経路はどの状態でも同じ 1 つのグラフ({@link SurfaceGraph})で引く —— 床だけバニラの地面のナビに切り替えることはしない
 * (11-05 ユーザー決定「地面も同じ面を歩く仕組みに統一する」)。床の上の体の動かし方だけバニラの物理を借りている。
 */
public final class ClingMover {

    /** 状態。上の説明。 */
    public enum Mode { FLOOR, CLING, FREE }

    /** 体の向きの同期を受け取る側(Mob)。 */
    public interface Body {
        /** 張り付いている面({@link SurfaceNode#anchor} の値か {@link SurfaceNode#NONE})。 */
        void setClingAnchor(int anchor);

        /** 体の前の向き(面の上での進む向き。単位ベクトル)。 */
        void setClingForward(Vec3 forward);

        /** 今の速さ(ブロック/tick)。歩きのアニメの速さ合わせに使う。 */
        void setMoveSpeed(float blocksPerTick);
    }

    /** 体を面から浮かせる隙間(ブロック)。 */
    private static final double EPS = 0.01D;
    /** 経路を引き直す間隔の下限(tick)。【仮】 */
    private static final int REPATH_MIN = 10;
    /** 相手がこれ以上動いたら引き直す(ブロック)。【仮】 */
    private static final double REPATH_MOVED = 1.5D;
    /** 床の 1 歩がこの tick 数で終わらなければ詰まったとみなして引き直す。【仮】 */
    private static final int FLOOR_STEP_LIMIT = 40;
    /** 床の状態で宙に居てよい長さ(段を落ちる間)。過ぎたら離れた状態へ。 */
    private static final int FLOOR_AIR_LIMIT = 12;
    /** 葉の中の速さの倍率。【仮 11-05 Claude】 */
    public static final double FOLIAGE_SPEED = 0.6D;
    /** 壁・天井の速さの倍率。【仮 11-05 Claude】床と同じ。 */
    public static final double CLING_SPEED = 1.0D;

    private final Mob mob;
    private final Body body;

    private Mode mode = Mode.FREE;
    /** 今いる(張り付き中は、今の 1 歩を終えたら居る)点。離れている間は最後に居た点。 */
    @Nullable
    private SurfaceNode node;
    private final Deque<SurfacePathfinder.Step> queue = new ArrayDeque<>();
    @Nullable
    private SurfacePathfinder.Step current;
    private final List<Vec3> waypoints = new ArrayList<>(2);
    private final List<Integer> waypointAnchors = new ArrayList<>(2);
    private int stepTicks;

    @Nullable
    private Entity followEntity;
    @Nullable
    private Vec3 followPos;
    private double reach = 1.0D;
    private double speedMod = 1.0D;
    @Nullable
    private Vec3 requestedFor;
    private int repathCooldown;
    private boolean forceRepath;

    @Nullable
    private CompletableFuture<SurfacePathfinder.Result> pending;
    @Nullable
    private SurfaceNode pendingStart;
    @Nullable
    private SurfacePathfinder.Result lastResult;

    private int grabCooldown;
    /** 離れている間、天井には付かない(飛びつきの最中)。何かに付いたら消える。 */
    private boolean noCeilingGrab;
    private int airTicks;
    private Vec3 lastForward = new Vec3(0.0D, 0.0D, 1.0D);

    public ClingMover(Mob mob, Body body) {
        this.mob = mob;
        this.body = body;
    }

    // ------------------------------------------------------------------ 外から

    public Mode mode() {
        return mode;
    }

    /** 壁・天井・葉の中に張り付いている(バニラの物理を止めている)。 */
    public boolean handlesTravel() {
        return mode == Mode.CLING;
    }

    /** 床に居る(バニラの物理で歩いている)。 */
    public boolean onFloor() {
        return mode == Mode.FLOOR;
    }

    public boolean inFoliage() {
        return mode == Mode.CLING && node != null && node.isFoliage()
                && (current == null || current.node().isFoliage());
    }

    @Nullable
    public SurfaceNode node() {
        return node;
    }

    /** 最後に受け取った経路(調べもの・GameTest 用)。 */
    @Nullable
    public SurfacePathfinder.Result lastResult() {
        return lastResult;
    }

    /** 今の面の向き(同期の値と同じ)。 */
    public int anchor() {
        return currentAnchor;
    }

    private int currentAnchor = SurfaceNode.NONE;

    /** 相手を追う({@code reach} = 相手の箱との隙間がこれ以下の点まで行く)。 */
    public void follow(Entity target, double speedMod, double reach) {
        if (this.followEntity != target) {
            this.forceRepath = true;
        }
        this.followEntity = target;
        this.followPos = null;
        this.speedMod = speedMod;
        this.reach = reach;
    }

    /** 点へ行く(点を含むマスか、隣の 0.5 以内のマス)。 */
    public void moveTo(Vec3 pos, double speedMod) {
        moveTo(pos, speedMod, 0.5D);
    }

    /** 点へ行く。{@code reach} = マスの箱と点の隙間がこれ以下なら着いた。 */
    public void moveTo(Vec3 pos, double speedMod, double reach) {
        if (this.followPos == null || this.followPos.distanceToSqr(pos) > 0.25D) {
            this.forceRepath = true;
        }
        this.followEntity = null;
        this.followPos = pos;
        this.speedMod = speedMod;
        this.reach = reach;
    }

    /** 調べもの用の 1 行(状態・点・今の 1 歩・残りの歩数)。 */
    public String debugState() {
        return mode + " " + node + (current != null ? " ->" + current.kind() + ":" + current.node() : "") + " q" + queue.size()
                + (pending != null ? " pending" : "");
    }

    /** 止まる(張り付いたまま。今の 1 歩は終える)。 */
    public void stop() {
        this.followEntity = null;
        this.followPos = null;
        this.queue.clear();
        this.pending = null;
        this.pendingStart = null;
    }

    /** 目的地に着いた・何もしていない。 */
    public boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    /**
     * 面から離れる(跳ぶ・叩き落とされた)。{@code grabCooldown} tick の間はまた付かない。経路は捨てる。
     */
    public void release(int grabCooldown) {
        release(grabCooldown, true);
    }

    /**
     * 面から離れる。{@code ceilingOk} が false なら、次に何かへ付くまで<b>天井には張り付かない</b>(床・壁・葉には付く)。
     * 飛びつきの最中に低い天井へ吸い込まれて技が消えないように(11-06 ユーザー報告)。
     */
    public void release(int grabCooldown, boolean ceilingOk) {
        this.noCeilingGrab = !ceilingOk;
        this.mode = Mode.FREE;
        this.current = null;
        this.queue.clear();
        this.waypoints.clear();
        this.grabCooldown = Math.max(this.grabCooldown, grabCooldown);
        this.currentAnchor = SurfaceNode.NONE;
        this.body.setClingAnchor(SurfaceNode.NONE);
        this.forceRepath = true;
    }

    // ------------------------------------------------------------------ 毎 tick

    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (grabCooldown > 0) {
            grabCooldown--;
        }
        if (!mob.isAlive()) {
            if (mode != Mode.FREE) {
                release(0);
            }
            return;
        }
        if (mob.isInWater() || mob.isInLava()) {
            // 水の中では張り付かない。浮かんで、行き先へ泳ぐだけ(岸へ上がるのはバニラの水からの跳ね上げ)。
            if (mode != Mode.FREE) {
                release(0);
            }
            Vec3 goal = goalCenter();
            if (goal != null) {
                mob.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, speedMod);
            }
            body.setMoveSpeed((float) mob.getDeltaMovement().horizontalDistance());
            return;
        }
        if (mode == Mode.FREE) {
            tryAttach(level);
            if (mode == Mode.FREE) {
                // 跳んでいる・落ちている間は舵を切らない(MoveControl は行き先を渡さなければ次の tick に止まる)。
                body.setMoveSpeed((float) mob.getDeltaMovement().horizontalDistance());
                return;
            }
        }
        if (mode == Mode.FLOOR) {
            if (mob.onGround()) {
                airTicks = 0;
                if (current == null) {
                    SurfaceNode here = floorNodeHere(level);
                    if (here != null) {
                        node = here;
                    }
                }
            } else if (++airTicks > FLOOR_AIR_LIMIT) {
                release(0);
                return;
            }
        }
        if (mode == Mode.CLING) {
            mob.fallDistance = 0.0F;
            mob.setOnGround(false);
        }
        updatePath(level);
        advance(level);
    }

    // ------------------------------------------------------------------ 付く・離れる

    /** 離れている間: 床に着いた・壁や天井に触れた・葉に入ったら付く。 */
    private void tryAttach(ServerLevel level) {
        if (grabCooldown > 0) {
            return;
        }
        BlockPos cell = mob.blockPosition();
        if (mob.onGround()) {
            mode = Mode.FLOOR;
            noCeilingGrab = false;
            airTicks = 0;
            SurfaceNode here = floorNodeHere(level);
            node = here != null ? here : SurfaceNode.of(cell, Direction.DOWN);
            setAnchor(Direction.DOWN.get3DDataValue());
            forceRepath = true;
            return;
        }
        if (ClingRules.isFoliageCell(level, cell)) {
            attachCling(SurfaceNode.foliage(cell));
            return;
        }
        if (!ClingRules.canOccupy(level, cell)) {
            return;
        }
        AABB box = mob.getBoundingBox();
        for (Direction d : GRAB_ORDER) {
            if ((d == Direction.UP && noCeilingGrab) || !ClingRules.isAnchor(level, cell, d)) {
                continue;
            }
            double plane = ClingRules.facePlane(level, cell, d);
            double gap = switch (d) {
                case UP -> plane - box.maxY;
                case EAST -> plane - box.maxX;
                case WEST -> box.minX - plane;
                case SOUTH -> plane - box.maxZ;
                case NORTH -> box.minZ - plane;
                default -> 1.0D;
            };
            if (gap <= GRAB_REACH) {
                attachCling(SurfaceNode.of(cell, d));
                return;
            }
        }
    }

    /** 触れていると見なす面との隙間(ブロック)。 */
    private static final double GRAB_REACH = 0.12D;
    private static final Direction[] GRAB_ORDER = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

    private void attachCling(SurfaceNode n) {
        mode = Mode.CLING;
        noCeilingGrab = false;
        node = n;
        current = null;
        waypoints.clear();
        mob.setDeltaMovement(Vec3.ZERO);
        setAnchor(n.anchor());
        forceRepath = true;
    }

    @Nullable
    private SurfaceNode floorNodeHere(BlockGetter g) {
        BlockPos cell = BlockPos.containing(mob.getX(), mob.getY() + 0.1D, mob.getZ());
        if (ClingRules.canOccupy(g, cell) && ClingRules.hasFloor(g, cell)) {
            return SurfaceNode.of(cell, Direction.DOWN);
        }
        BlockPos up = cell.above();
        if (ClingRules.canOccupy(g, up) && ClingRules.hasFloor(g, up)) {
            return SurfaceNode.of(up, Direction.DOWN);
        }
        return null;
    }

    private void setAnchor(int anchor) {
        if (anchor != currentAnchor) {
            currentAnchor = anchor;
            body.setClingAnchor(anchor);
        }
    }

    // ------------------------------------------------------------------ 経路

    @Nullable
    private Vec3 goalCenter() {
        if (followEntity != null) {
            return followEntity.isAlive() ? followEntity.position() : null;
        }
        return followPos;
    }

    @Nullable
    private SurfacePathfinder.Goal goal() {
        if (followEntity != null) {
            if (!followEntity.isAlive() || followEntity.isRemoved()) {
                return null;
            }
            return new SurfacePathfinder.Goal(followEntity.getBoundingBox(), reach);
        }
        if (followPos != null) {
            return new SurfacePathfinder.Goal(new AABB(followPos, followPos).inflate(0.05D), reach);
        }
        return null;
    }

    private void updatePath(ServerLevel level) {
        if (pending != null && pending.isDone()) {
            SurfacePathfinder.Result r = null;
            try {
                r = pending.getNow(null);
            } catch (RuntimeException ignored) {
                // 裏の計算が落ちた: 捨てて引き直す
            }
            SurfaceNode start = pendingStart;
            pending = null;
            pendingStart = null;
            if (r != null && start != null) {
                adopt(r, start);
            }
        }
        SurfacePathfinder.Goal goal = goal();
        if (goal == null) {
            return;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        }
        if (pending != null) {
            return;
        }
        Vec3 center = goal.box().getCenter();
        boolean moved = requestedFor == null || requestedFor.distanceToSqr(center) > REPATH_MOVED * REPATH_MOVED;
        SurfaceNode at = current != null ? current.node() : node;
        boolean arrived = at != null && current == null && queue.isEmpty() && goal.reached(at);
        boolean need = forceRepath || moved || (current == null && queue.isEmpty() && !arrived);
        if (!need || (repathCooldown > 0 && !forceRepath)) {
            return;
        }
        if (at == null) {
            return;
        }
        forceRepath = false;
        repathCooldown = REPATH_MIN;
        requestedFor = center;
        pendingStart = at;
        pending = SurfacePaths.request(level, at, goal);
    }

    private void adopt(SurfacePathfinder.Result r, SurfaceNode start) {
        lastResult = r;
        if (mode == Mode.FREE) {
            return;
        }
        SurfaceNode at = current != null ? current.node() : node;
        List<SurfacePathfinder.Step> steps = r.steps();
        int from = -1;
        if (start.equals(at)) {
            from = 0;
        } else {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).node().equals(at)) {
                    from = i;
                    break;
                }
            }
        }
        if (from < 0) {
            forceRepath = true; // もう別の所に居る
            return;
        }
        queue.clear();
        for (int i = from + 1; i < steps.size(); i++) {
            queue.addLast(steps.get(i));
        }
    }

    // ------------------------------------------------------------------ 進む

    private void advance(ServerLevel level) {
        if (current == null) {
            current = queue.pollFirst();
            stepTicks = 0;
            waypoints.clear();
            if (current == null) {
                hold(level);
                return;
            }
            if (!(mode == Mode.FLOOR && node != null && current.kind().walksOnFloor(node, current.node()))) {
                beginKinematic(level, current);
            }
        }
        stepTicks++;
        if (waypoints.isEmpty()) {
            walkFloorStep(level, current);
        } else {
            moveKinematic(level);
        }
    }

    /** 床から床への 1 歩: 行き先をバニラの MoveControl に渡して歩かせる。 */
    private void walkFloorStep(ServerLevel level, SurfacePathfinder.Step step) {
        BlockPos cell = step.node().cell();
        BlockPos aim = step.kind() == SurfaceGraph.Kind.DROP && step.via() != null && !pastVia(step) ? step.via() : cell;
        double tx = aim.getX() + 0.5D;
        double tz = aim.getZ() + 0.5D;
        double ty = ClingRules.floorY(level, cell);
        mob.getMoveControl().setWantedPosition(tx, ty, tz, speedMod);
        double dx = cell.getX() + 0.5D - mob.getX();
        double dz = cell.getZ() + 0.5D - mob.getZ();
        boolean close = dx * dx + dz * dz < 0.35D * 0.35D && Math.abs(mob.getY() - ty) < 0.75D;
        if (close && mob.onGround()) {
            node = step.node();
            current = null;
        } else if (stepTicks > FLOOR_STEP_LIMIT) {
            current = null;
            queue.clear();
            forceRepath = true;
        }
        Vec3 v = mob.getDeltaMovement();
        body.setMoveSpeed((float) v.horizontalDistance());
    }

    private boolean pastVia(SurfacePathfinder.Step step) {
        BlockPos via = step.via();
        double dx = via.getX() + 0.5D - mob.getX();
        double dz = via.getZ() + 0.5D - mob.getZ();
        return dx * dx + dz * dz < 0.3D * 0.3D || mob.getY() < via.getY() - 0.2D;
    }

    /** 張り付いて動く 1 歩を始める: 通る点(外側の角は途中のマス)を並べる。 */
    private void beginKinematic(ServerLevel level, SurfacePathfinder.Step step) {
        if (mode != Mode.CLING) {
            mode = Mode.CLING;
            mob.setDeltaMovement(Vec3.ZERO);
        }
        waypoints.clear();
        waypointAnchors.clear();
        SurfaceNode to = step.node();
        if (step.via() != null && node != null && node.face() != null) {
            // 外側の角: 今の面のまま途中のマスまで出て、そこで裏の面へ回る。
            Vec3 here = clingPoint(level, node);
            BlockPos d = step.via().subtract(node.cell());
            waypoints.add(here.add(d.getX(), d.getY(), d.getZ()));
            waypointAnchors.add(node.anchor());
        }
        waypoints.add(clingPoint(level, to));
        waypointAnchors.add(to.anchor());
        setAnchor(waypointAnchors.get(0));
    }

    private void moveKinematic(ServerLevel level) {
        double speed = blocksPerTick() * (current.node().isFoliage() || (node != null && node.isFoliage()) ? FOLIAGE_SPEED : CLING_SPEED);
        Vec3 pos = mob.position();
        double left = speed;
        Vec3 moved = Vec3.ZERO;
        while (left > 1.0E-6D && !waypoints.isEmpty()) {
            Vec3 wp = waypoints.get(0);
            Vec3 d = wp.subtract(pos);
            double len = d.length();
            if (len <= left) {
                moved = moved.add(d);
                pos = wp;
                left -= len;
                waypoints.remove(0);
                waypointAnchors.remove(0);
                if (!waypointAnchors.isEmpty()) {
                    setAnchor(waypointAnchors.get(0));
                }
            } else {
                Vec3 s = d.scale(left / len);
                moved = moved.add(s);
                pos = pos.add(s);
                left = 0.0D;
            }
        }
        mob.setPos(pos.x, pos.y, pos.z);
        mob.setDeltaMovement(moved);
        if (moved.lengthSqr() > 1.0E-6D) {
            // 前 = 面に沿った動き(内側の角で面へ寄る動きは前に数えない)。
            Vec3 along = moved;
            if (currentAnchor < 6) {
                Vec3 n = Vec3.atLowerCornerOf(Direction.from3DDataValue(currentAnchor).getNormal());
                along = moved.subtract(n.scale(moved.dot(n)));
            }
            if (along.lengthSqr() > 0.09D * moved.lengthSqr() && along.lengthSqr() > 1.0E-8D) {
                lastForward = along.normalize();
                body.setClingForward(lastForward);
            }
        }
        body.setMoveSpeed((float) moved.length());
        if (waypoints.isEmpty()) {
            node = current.node();
            current = null;
            if (node.isFloor()) {
                // 床に下りた: バニラの物理へ返す。
                mode = Mode.FLOOR;
                airTicks = 0;
                setAnchor(Direction.DOWN.get3DDataValue());
            }
        }
    }

    /** 経路が無いとき。張り付き中は面に寄せたまま止まる。床ではその場で止まる。 */
    private void hold(ServerLevel level) {
        if (mode == Mode.CLING && node != null) {
            Vec3 at = clingPoint(level, node);
            Vec3 d = at.subtract(mob.position());
            double len = d.length();
            if (len > 1.0E-4D) {
                Vec3 s = len > 0.1D ? d.scale(0.1D / len) : d;
                mob.setPos(mob.getX() + s.x, mob.getY() + s.y, mob.getZ() + s.z);
            }
            mob.setDeltaMovement(Vec3.ZERO);
            Vec3 goal = goalCenter();
            if (goal != null && node.face() != null) {
                Vec3 n = Vec3.atLowerCornerOf(node.face().getOpposite().getNormal());
                Vec3 to = goal.subtract(mob.position());
                Vec3 along = to.subtract(n.scale(to.dot(n)));
                if (along.lengthSqr() > 1.0E-4D) {
                    lastForward = along.normalize();
                    body.setClingForward(lastForward);
                }
            }
        }
        // 床では MoveControl に行き先を渡さないだけで止まる(次の tick に WAIT へ戻って前進を 0 にする)。
        body.setMoveSpeed(0.0F);
    }

    /** 床の上の速さ(ブロック/tick)。バニラの歩き({@code MoveControl} → {@code travel})の釣り合いの速さと同じ式。 */
    public double blocksPerTick() {
        double s = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * speedMod;
        // zza = 0.98 s、加速 = zza × s(摩擦 0.6 の地面)、毎 tick 0.546 倍に減る → 釣り合い 0.98 s² / 0.454
        return 0.98D * s * s / (1.0D - 0.6D * 0.91D);
    }

    /** その点に張り付いているときの体の位置(足元の真ん中)。体は 1 マスに収まる前提。 */
    public Vec3 clingPoint(BlockGetter g, SurfaceNode n) {
        BlockPos c = n.cell();
        double w = mob.getBbWidth();
        double h = mob.getBbHeight();
        double cx = c.getX() + 0.5D;
        double cz = c.getZ() + 0.5D;
        if (n.isFoliage()) {
            return new Vec3(cx, c.getY() + Math.max(0.0D, (1.0D - h) * 0.5D), cz);
        }
        Direction f = n.face();
        if (f == Direction.DOWN) {
            return new Vec3(cx, ClingRules.floorY(g, c), cz);
        }
        double plane = ClingRules.facePlane(g, c, f);
        if (f == Direction.UP) {
            return new Vec3(cx, Math.min(c.getY() + 1.0D, plane) - h - EPS, cz);
        }
        double y = c.getY() + EPS;
        double half = w * 0.5D + EPS;
        return switch (f) {
            case EAST -> new Vec3(Math.min(plane - half, c.getX() + 1.0D), y, cz);
            case WEST -> new Vec3(Math.max(plane + half, c.getX()), y, cz);
            case SOUTH -> new Vec3(cx, y, Math.min(plane - half, c.getZ() + 1.0D));
            case NORTH -> new Vec3(cx, y, Math.max(plane + half, c.getZ()));
            default -> new Vec3(cx, y, cz);
        };
    }
}
