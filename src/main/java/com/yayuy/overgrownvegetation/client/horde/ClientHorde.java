package com.yayuy.overgrownvegetation.client.horde;

import com.yayuy.overgrownvegetation.network.TdHordePayload;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.td.HordeFormation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * クライアント側の <b>TD の行進中の群れ</b>(2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」の 2 段目)。
 * {@link TdHordePayload} を受け取って覚え、毎フレーム群れの一員の位置を出して {@link HordeRenderer} へ渡す(身代わりの絵で描く)。
 * 描画距離の外の群れは {@link HordeStormRenderer} が地平線の胞子の嵐で描く(4 段目)。
 *
 * <ul>
 *     <li>位置: 受け取った中心から、送られた向きへ {@code speed} で {@code travel} まで延ばす(サーバーの行進は直線。置けなかった群れが
 *     待つ輪に沿って横へ歩くときは、その接線の向き)。新しいパケットで位置が食い違ったら、{@value #SMOOTH_TICKS} tick かけて滑らかに寄せる。</li>
 *     <li>隊列: {@link HordeFormation}(サーバーが空の群れを湧かせる位置と同じ式)。隊列の向きは送られた向きへ 1 秒に
 *     {@value #TURN_DEGREES_PER_SECOND} 度まで回す(向きが変わった瞬間に隊列ごと回って一員が横へ跳ばないように)。
 *     地上の群れは一員ごとにクライアントの地形の高さに立たせる(木・蔦は突き抜けて地面へ。読み込まれていない所は群れの中心の高さ)。
 *     空の群れは梢の上より下りない。歩いている間は小さく上下に揺れる。空の群れは高さを少しずつずらして漂う。</li>
 *     <li>{@value #STALE_TICKS} tick パケットが来なければ全部捨てる(範囲の外へ出た・サーバーが止まった)。ディメンションを移っても捨てる。</li>
 * </ul>
 * 実体になった群れはサーバーの一覧から消えるので、ここでも同じ tick に消える(本物は近ければバニラが、遠ければ 1 段目の身代わりが描く)。
 */
public final class ClientHorde {

    /** これだけ次のパケットが来なければ全部捨てる(帳簿は 20 tick ごと)。 */
    private static final long STALE_TICKS = 100L;
    /** 新しい位置と描いていた位置の食い違いを消すのにかける tick。 */
    private static final float SMOOTH_TICKS = 10.0F;
    /** 隊列の向きを回す速さの上限(度 / 秒)。仮決め 2026-09-25。 */
    private static final float TURN_DEGREES_PER_SECOND = 120.0F;
    /** 同じ、ラジアン / tick。 */
    private static final float TURN_PER_TICK = TURN_DEGREES_PER_SECOND * Mth.DEG_TO_RAD / 20.0F;
    /** 地面を探して下りる最大の段数(木・蔦・空気を突き抜ける。{@code TreeCanopyGroundFinder} と同じ 48)。 */
    private static final int GROUND_SCAN = 48;

    private static final Int2ObjectOpenHashMap<Swarm> SWARMS = new Int2ObjectOpenHashMap<>();
    private static long lastReceived;
    @Nullable
    private static ClientLevel lastLevel;

    /** 群れ 1 つ。 */
    static final class Swarm {
        final int id;
        double x;
        float y;
        double z;
        float dirX;
        float dirZ = 1.0F;
        float speed;
        float travel;
        boolean air;
        long at;
        double errX;
        double errZ;
        /** 隊列の向き(ラジアン、atan2(前の z, 前の x))の回し始め・行き先・回し始めた tick・かける tick。 */
        float headingFrom;
        float headingTo;
        double turnAt;
        float turnTicks;
        /** 頭数の合計。 */
        int heads;
        List<TdHordePayload.Count> counts = List.of();
        List<Member> members = List.of();

        Swarm(int id) {
            this.id = id;
        }

        /** {@code tick}(+ 端数)に描く中心の x・z。 */
        void center(double tick, double[] out) {
            double t = Math.max(0.0D, tick - this.at);
            double dist = Math.min(this.travel, this.speed * t);
            double fade = Math.max(0.0D, 1.0D - t / SMOOTH_TICKS);
            out[0] = this.x + this.dirX * dist + this.errX * fade;
            out[1] = this.z + this.dirZ * dist + this.errZ * fade;
        }

        /** {@code tick} の隊列の向き(ラジアン)。 */
        float heading(double tick) {
            if (this.turnTicks <= 0.0F) {
                return this.headingTo;
            }
            float f = (float) Mth.clamp((tick - this.turnAt) / this.turnTicks, 0.0D, 1.0D);
            return this.headingFrom + Mth.wrapDegrees((this.headingTo - this.headingFrom) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * f;
        }

        /** まだ歩いているか(止まる輪に着くまで)。 */
        boolean marching(double tick) {
            return this.speed > 0.0F && (tick - this.at) * this.speed < this.travel;
        }

        boolean air() {
            return this.air;
        }

        float y() {
            return this.y;
        }

        int heads() {
            return this.heads;
        }

        List<Member> members() {
            return this.members;
        }
    }

    /** 一員 1 体(隊列の席 + 足元の高さの控え)。 */
    static final class Member {
        final HordeFormation.Slot slot;
        int groundX = Integer.MIN_VALUE;
        int groundZ;
        float groundY;

        Member(HordeFormation.Slot slot) {
            this.slot = slot;
        }

        EntityType<?> type() {
            return this.slot.type();
        }
    }

    /** パケットを受け取った(描画スレッド)。例外は外へ出さない(受け取りの失敗で接続を切らせない。群れの絵が出ないだけ)。 */
    public static void onPayload(TdHordePayload payload) {
        try {
            accept(payload);
        } catch (RuntimeException e) {
            SWARMS.clear();
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn("TD の行進中の群れを受け取れない", e);
        }
    }

    private static void accept(TdHordePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level != lastLevel) {
            SWARMS.clear();
            lastLevel = level;
        }
        long now = level.getGameTime();
        Int2ObjectOpenHashMap<Swarm> next = new Int2ObjectOpenHashMap<>();
        double[] at = new double[2];
        for (TdHordePayload.Swarm s : payload.swarms()) {
            float heading = (float) Math.atan2(s.dirZ(), s.dirX());
            Swarm swarm = SWARMS.get(s.id());
            if (swarm == null) {
                swarm = new Swarm(s.id());
                swarm.headingFrom = heading;
                swarm.headingTo = heading;
                swarm.turnTicks = 0.0F;
            } else {
                // いま描いている位置との食い違いを覚えて、少しずつ消す(新しい位置へ瞬間移動しない)。
                swarm.center(now, at);
                swarm.errX = at[0] - s.x();
                swarm.errZ = at[1] - s.z();
                // 隊列の向きは、いま描いている向きから新しい向きへ一定の速さで回す。
                float current = swarm.heading(now);
                float turn = Math.abs(Mth.wrapDegrees((heading - current) * Mth.RAD_TO_DEG)) * Mth.DEG_TO_RAD;
                swarm.headingFrom = current;
                swarm.headingTo = heading;
                swarm.turnAt = now;
                swarm.turnTicks = turn / TURN_PER_TICK;
            }
            swarm.x = s.x();
            swarm.y = s.y();
            swarm.z = s.z();
            swarm.dirX = s.dirX();
            swarm.dirZ = s.dirZ();
            swarm.speed = Math.max(0.0F, s.speed());
            swarm.travel = Math.max(0.0F, s.travel());
            swarm.air = s.air();
            swarm.at = now;
            if (!swarm.counts.equals(s.counts())) {
                swarm.counts = List.copyOf(s.counts());
                List<Member> members = new ArrayList<>();
                for (HordeFormation.Slot slot : HordeFormation.slots(s.id(), s.counts())) {
                    members.add(new Member(slot));
                }
                swarm.members = members;
                swarm.heads = members.size();
            }
            next.put(s.id(), swarm);
        }
        SWARMS.clear();
        SWARMS.putAll(next);
        lastReceived = now;
    }

    /**
     * いま描いてよい群れ(古い・別のディメンションなら捨てて空)。{@link HordeStormRenderer} も使う。
     */
    static Collection<Swarm> swarms(ClientLevel level) {
        if (level != lastLevel) {
            SWARMS.clear();
            lastLevel = level;
            return List.of();
        }
        long now = level.getGameTime();
        if (!SWARMS.isEmpty() && (now - lastReceived > STALE_TICKS || now < lastReceived)) {
            SWARMS.clear();
        }
        return SWARMS.values();
    }

    /** {@code from} から一番近い行進中の群れまでの水平の距離(ブロック。群れが無ければ +∞)。前触れ(client/TdOmen)が読む。 */
    public static double nearestDistance(ClientLevel level, Vec3 from) {
        double best = Double.POSITIVE_INFINITY;
        for (Swarm s : swarms(level)) {
            double dx = s.x - from.x;
            double dz = s.z - from.z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    /**
     * 毎フレーム: 群れの一員を集めて {@link HordeRenderer} へ足す。描く奥行き {@code limit} の中は絵のまま。外は、{@code far}(4 段目)なら
     * 空の群れの一員だけを空の影として足す(地上の群れは {@link HordeStormRenderer} が地平線の嵐で描く)。
     */
    static void collect(ClientLevel level, Vec3 camera, float partialTick, double limit, Frustum frustum, boolean far) {
        Collection<Swarm> swarms = swarms(level);
        if (swarms.isEmpty()) {
            return;
        }
        double limitSq = limit * limit;
        double tick = level.getGameTime() + partialTick;
        double[] center = new double[2];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        FarSky sky = far ? new FarSky(level, partialTick, limit) : null;
        for (Swarm swarm : swarms) {
            swarm.center(tick, center);
            float heading = swarm.heading(tick);
            float fx = Mth.cos(heading);
            float fz = Mth.sin(heading);
            float yaw = HordeFormation.yaw(fx, fz);
            // まだ歩いているか(止まる輪に着くまで)。歩いている間だけ上下に揺らす。
            boolean marching = swarm.marching(tick);
            for (Member m : swarm.members) {
                HordeFormation.Slot slot = m.slot;
                double x = HordeFormation.x(center[0], fx, fz, slot);
                double z = HordeFormation.z(center[1], fx, fz, slot);
                double dx = x - camera.x;
                double dz = z - camera.z;
                double horizontalSq = dx * dx + dz * dz;
                if (horizontalSq > limitSq) {
                    if (sky != null && swarm.air) {
                        sky.add(swarm, m, x, z, tick, camera, frustum, yaw, cursor);
                    }
                    continue;
                }
                HordeSprites.Sprite sprite = HordeSprites.forType(slot.type());
                if (sprite == null) {
                    continue;
                }
                double y = memberY(level, swarm, m, x, z, tick, marching, cursor);
                double dy = y - camera.y;
                double distanceSq = horizontalSq + dy * dy;
                if (distanceSq > limitSq || !HordeRenderer.visible(frustum, sprite.sheet(), x, y, z, slot.scale())) {
                    continue;
                }
                HordeRenderer.add(sprite, x, y, z, yaw + slot.yawJitter(), slot.scale(), distanceSq);
            }
        }
    }

    /**
     * 描く奥行きの外の<b>空の影</b>(4 段目)。空の群れの一員の絵を、霧を切って半透明で描く({@link HordeRenderer#addFar}):
     * 描く奥行きのすぐ外で薄れて消え(そこから内は霧の中の絵)、遠いほど霧の色へ寄せ(霞は地平線の嵐と同じ {@link StormPuffs#haze})、
     * 昼夜の明るさを掛ける。投影の奥の面より遠ければ同じ視角のまま近くへ寄せて縮める。
     */
    private static final class FarSky {
        /** 影の暗さ(絵に掛ける明るさ。仮決め 2026-09-25)。 */
        static final float SILHOUETTE = 0.45F;
        final double limit;
        final double maxDraw;
        final float[] fog;
        final float brightness;
        final ClientLevel level;

        FarSky(ClientLevel level, float partialTick, double limit) {
            this.level = level;
            this.limit = limit;
            this.maxDraw = HordeRenderer.maxDrawDistance(Minecraft.getInstance());
            this.fog = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
            this.brightness = level.getSkyDarken(partialTick);
        }

        void add(Swarm swarm, Member m, double x, double z, double tick, Vec3 camera, Frustum frustum, float yaw,
                 BlockPos.MutableBlockPos cursor) {
            double dx = x - camera.x;
            double dz = z - camera.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float fade = StormPuffs.fadeIn(horizontal, this.limit);
            if (fade <= 0.0F) {
                return;
            }
            HordeSprites.Sprite sprite = HordeSprites.forType(m.type());
            if (sprite == null) {
                return;
            }
            double y = memberY(this.level, swarm, m, x, z, tick, false, cursor);
            double dy = y - camera.y;
            double distance = Math.sqrt(horizontal * horizontal + dy * dy);
            double shrink = distance > this.maxDraw ? this.maxDraw / distance : 1.0D;
            double sx = camera.x + dx * shrink;
            double sy = camera.y + dy * shrink;
            double sz = camera.z + dz * shrink;
            float scale = (float) (m.slot.scale() * shrink);
            if (!HordeRenderer.visible(frustum, sprite.sheet(), sx, sy, sz, scale)) {
                return;
            }
            // 影: 絵を暗く(昼夜の明るさ × 0.45)して霧の色へ寄せる。
            float haze = StormPuffs.haze(horizontal, this.limit);
            float shade = this.brightness * SILHOUETTE;
            int a = Math.round(Mth.clamp(fade, 0.0F, 1.0F) * 255.0F);
            int r = channel(shade + (this.fog[0] - shade) * haze);
            int g = channel(shade + (this.fog[1] - shade) * haze);
            int b = channel(shade + (this.fog[2] - shade) * haze);
            HordeRenderer.addFar(sprite, sx, sy, sz, yaw + m.slot.yawJitter(), scale, a << 24 | r << 16 | g << 8 | b);
        }

        private static int channel(float v) {
            return Math.round(Mth.clamp(v, 0.0F, 1.0F) * 255.0F);
        }
    }

    /**
     * 一員の足元の高さ。空の群れ: サーバーの高さ(地面 + 16)は背の高い森だと梢に埋まるので、梢の上 {@link HordeFormation#AIR_CLEARANCE}
     * より下へは下りない({@link HordeFormation#airY}。サーバーが湧かせる高さと同じ)+ ゆっくり漂う。地上の群れ: 地形の高さ + 歩きの揺れ。
     */
    static double memberY(ClientLevel level, Swarm swarm, Member m, double x, double z, double tick, boolean marching,
                          BlockPos.MutableBlockPos cursor) {
        HordeFormation.Slot slot = m.slot;
        if (swarm.air) {
            float top = surface(level, m, x, z, swarm.y - HordeFormation.AIR_CLEARANCE, cursor, true);
            return HordeFormation.airY(swarm.y, top, slot) + Mth.sin((float) (tick * 0.08D) + slot.phase()) * 0.6F;
        }
        double y = surface(level, m, x, z, swarm.y, cursor, false);
        if (marching) {
            y += Math.abs(Mth.sin((float) (tick * 0.35D) + slot.phase())) * 0.06F * slot.scale();
        }
        return y;
    }

    /**
     * 一員の列の高さ(同じマスの列にいる間は控えを使う)。
     * <ul>
     *     <li>{@code canopy}(空の群れ): 一番上 = 動きを止めるブロック(葉を含む)の上。</li>
     *     <li>そうでなければ足元の地面: 一番上から、木(葉・幹。過繁茂の葉・巨大樹の幹を含む {@link TreeCanopyGroundFinder#isTreePart})・
     *     動きを止めないもの(蔦・草・花・空気)を突き抜けて、最初の踏める地面か水・溶岩の面まで {@value #GROUND_SCAN} 段下りる。</li>
     * </ul>
     * チャンクが読み込まれていない・地面が見つからないときは {@code fallback}(サーバーが測った群れの中心の高さ)。
     *
     * <p>2026-09-25 ユーザー報告「森の木の葉の上に絵が出て、葉の上から mob が迫ってくるように見える」: はじめはバニラの葉タグと空気
     * だけを突き抜けていたので、過繁茂の葉(タグを持たない)・葉の下の蔦・幹で止まり、サーバーの高さ(葉を除く高さ地図 = 過繁茂の
     * 葉の上)とも食い違わないので、そのまま梢の上に立たせていた。
     */
    private static float surface(ClientLevel level, Member m, double x, double z, float fallback, BlockPos.MutableBlockPos cursor,
                                 boolean canopy) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (bx == m.groundX && bz == m.groundZ) {
            return m.groundY;
        }
        float y = fallback;
        if (level.hasChunk(bx >> 4, bz >> 4)) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            if (canopy) {
                y = top;
            } else {
                int bottom = Math.max(level.getMinBuildHeight(), top - GROUND_SCAN);
                for (int floor = top; floor > bottom; floor--) {
                    BlockState below = level.getBlockState(cursor.set(bx, floor - 1, bz));
                    if (TreeCanopyGroundFinder.isTreePart(below)) {
                        continue;
                    }
                    if (below.blocksMotion() || !below.getFluidState().isEmpty()) {
                        y = floor;
                        break;
                    }
                }
            }
        }
        m.groundX = bx;
        m.groundZ = bz;
        m.groundY = y;
        return y;
    }

    /** 全部捨てる。 */
    public static void clear() {
        SWARMS.clear();
    }

    private ClientHorde() {
    }
}
