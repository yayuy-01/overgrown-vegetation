package com.yayuy.overgrownvegetation.client.wall;

import com.yayuy.overgrownvegetation.network.CreepingWallPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;

/**
 * 蠢く壁の、画面の側のデータ(2026-11-16 試作)。サーバーから届いた先頭の升を貯め、描く面({@link Face})を出す。
 * <b>見た目は持たない</b>: 描くのは {@link CreepingWallRenderer}(差し替えられる。今は仮の {@link BoxCreepingWallRenderer})。
 *
 * <p>面 = 埋まった升の、まだ埋まっていない隣(空いている所)へ向いた側。届いてからの時間と速さから「次の升へどこまで進んだか」({@link Face#progress})
 * を出し、層と層の間も面が滑らかに進むように描ける。
 */
public final class ClientCreepingWall {

    /** 描く面 1 つ。{@code cell} の {@code dir} の側。{@code progress} は 0〜1(次の升へ進んだ割合)、{@code age} は届いてからの tick。 */
    public record Face(long cell, Direction dir, float progress, float age) {
    }

    /** 壁 1 つの画面の側。 */
    public static final class View {
        final int id;
        /** 届いた升(埋まった升のうち、近くで見えたもの)。 */
        final LongOpenHashSet filled = new LongOpenHashSet();
        /** 先頭の升 → 届いた tick(面を描く候補)。 */
        final Long2LongOpenHashMap front = new Long2LongOpenHashMap();
        float blocksPerTick = 0.28F;
        boolean stopped;
        long stoppedAt;

        View(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public boolean isStopped() {
            return stopped;
        }

        /** 止まってからの tick(止まっていなければ −1)。 */
        public float sinceStop(float time) {
            return stopped ? time - stoppedAt : -1.0F;
        }

        public boolean isFilled(long pos) {
            return filled.contains(pos);
        }

        public int frontCount() {
            return front.size();
        }

        /** 描く面を {@code out} へ。カメラから {@code radius} 以内。面の無くなった先頭の升はここで外す。 */
        public void collectFaces(ClientLevel level, double camX, double camY, double camZ, double radius, float time, java.util.function.Consumer<Face> out) {
            double r2 = radius * radius;
            BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
            float perBlock = blocksPerTick > 0 ? 1.0F / blocksPerTick : 4.0F;
            for (Iterator<Long2LongOpenHashMap.Entry> it = front.long2LongEntrySet().iterator(); it.hasNext(); ) {
                Long2LongOpenHashMap.Entry e = it.next();
                long p = e.getLongKey();
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                double dx = x + 0.5 - camX, dy = y + 0.5 - camY, dz = z + 0.5 - camZ;
                if (dx * dx + dy * dy + dz * dz > r2) {
                    continue;
                }
                n.set(x, y, z);
                if (stopped && level.getBlockState(n).isSolidRender(level, n)) {
                    it.remove(); // 止まった所の塊(本物のブロック)に置き換わった
                    continue;
                }
                float age = time - e.getLongValue();
                float progress = stopped ? Math.min(0.5F, Math.max(0.0F, (stoppedAt - e.getLongValue()) / perBlock))
                        : Math.min(0.85F, Math.max(0.0F, age / perBlock));
                boolean any = false;
                for (Direction d : Direction.values()) {
                    n.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                    if (filled.contains(n.asLong())) {
                        continue;
                    }
                    BlockState s = level.getBlockState(n);
                    if (!s.isAir() && s.isSolidRender(level, n)) {
                        continue; // 岩の面の向こうは描かない
                    }
                    any = true;
                    out.accept(new Face(p, d, progress, age));
                }
                if (!any) {
                    it.remove();
                }
            }
        }
    }

    private static final Int2ObjectOpenHashMap<View> VIEWS = new Int2ObjectOpenHashMap<>();
    private static ClientLevel lastLevel;
    /** この時刻まで環境音を止める(前触れと、近くで動いている間)。 */
    private static long quietUntil = Long.MIN_VALUE;

    /** 今、環境音を止めるか。 */
    public static boolean isQuiet() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getGameTime() < quietUntil;
    }

    /** 視線の先 {@code reach} ブロック以内で、最初に当たる塊の升(無ければ null)。殴ったときに使う。 */
    @javax.annotation.Nullable
    public static BlockPos raycast(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 dir, double reach) {
        if (VIEWS.isEmpty()) {
            return null;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (double t = 0.0D; t <= reach; t += 0.1D) {
            m.set(net.minecraft.util.Mth.floor(from.x + dir.x * t), net.minecraft.util.Mth.floor(from.y + dir.y * t), net.minecraft.util.Mth.floor(from.z + dir.z * t));
            long p = m.asLong();
            for (View v : VIEWS.values()) {
                if (!v.stopped && v.filled.contains(p)) {
                    return m.immutable();
                }
            }
        }
        return null;
    }

    private ClientCreepingWall() {
    }

    public static Iterable<View> views() {
        return VIEWS.values();
    }

    public static boolean isEmpty() {
        return VIEWS.isEmpty();
    }

    private static void resetIfLevelChanged() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            VIEWS.clear();
            quietUntil = Long.MIN_VALUE;
            lastLevel = level;
        }
    }

    public static void accept(CreepingWallPayload payload) {
        resetIfLevelChanged();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        if (payload.kind() == CreepingWallPayload.OMEN) {
            // 前触れ: 環境音と水滴の音が止む(決定 その 1)
            quietUntil = Math.max(quietUntil, now + (payload.cells().length > 0 ? payload.cells()[0] : 100));
            return;
        }
        if (payload.kind() == CreepingWallPayload.ADD) {
            quietUntil = Math.max(quietUntil, now + 100); // 近くで動いている間は静かなまま(止まって 5 秒で戻る)
        }
        if (payload.kind() == CreepingWallPayload.CLEAR) {
            VIEWS.remove(payload.wallId());
            return;
        }
        View v = VIEWS.computeIfAbsent(payload.wallId(), View::new);
        v.blocksPerTick = payload.blocksPerTick();
        if (payload.kind() == CreepingWallPayload.STOP) {
            v.stopped = true;
            v.stoppedAt = now;
            return;
        }
        BlockPos base = payload.base();
        boolean snapshot = payload.kind() == CreepingWallPayload.SNAPSHOT;
        for (int c : payload.cells()) {
            long p = BlockPos.asLong(base.getX() + CreepingWallPayload.dx(c), base.getY() + CreepingWallPayload.dy(c), base.getZ() + CreepingWallPayload.dz(c));
            if (v.filled.add(p)) {
                v.front.put(p, snapshot ? now - 40 : now);
            }
        }
    }

    /** 止まってから長く経った壁・遠すぎる升を捨てる(毎 tick)。 */
    public static void tick() {
        resetIfLevelChanged();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || VIEWS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        VIEWS.values().removeIf(v -> v.stopped && now - v.stoppedAt > 20 * 60 * 5);
        if (now % 100 == 0) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                BlockPos at = player.blockPosition();
                for (View v : VIEWS.values()) {
                    v.filled.removeIf(p -> BlockPos.of(p).distManhattan(at) > 400);
                }
            }
        }
    }
}
