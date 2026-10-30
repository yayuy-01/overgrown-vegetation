package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 雲に穴が開く演出(2026-10-18。ユーザー 10-16「ティタニカの極太ビームや攻城砲の砲弾などが雲に入ったとき、アニメや漫画によくある円形にぼこっと穴が開く演出を」、
 * 10-18「巻雲の後に雲に穴が開く演出を」)。
 *
 * <ul>
 *   <li>穴は「入った所 A から出た所 B までの、半径 r の筒(両端は丸い)」。場の座標で持つ(雲と一緒に風で流れる)。雲のシェーダーが筒の中の面を捨て
 *       (雲の層を貫く丸い穴になる。後ろの面は描かないので、向こうの空が見える)、切り口の縁を少し暗く、開いた直後はしばらく光らせる。</li>
 *   <li>砲弾({@link ClientShells}。攻城砲の砲弾・ティタニカの胞子爆弾。どちらも放物線の式がクライアントにある)が雲の箱({@link CloudInterior#solidAt})に入った所で穴を
 *       開け、中を進む間は B を伸ばし、出た所で閉じる。開くときは 8 tick で半径が広がり(ぼこっと)、入った所に水蒸気の輪が広がる。</li>
 *   <li>花のビーム(照射中): 花から着弾点の先まで 8 ブロックおきに雲の中かを見て、雲の中の区間ごとに穴を開け・伸ばす。</li>
 *   <li>穴の内側の壁: 筋の筒(半径の 1.06 倍、入口から出口まで)を雲の色で描く。両端(入口・出口)は明るく、奥ほど暗く、日の当たる側は明るい(井戸のように奥が見える)。
 *       筒は切り口より外にあるので、穴の外からは雲に隠れ、穴の口からだけ向こう側の壁が見える。</li>
 *   <li>穴は 1 分後から埋まり始め、3 分で元に戻る。同時に {@link #MAX} まで(古い物から消す)。雲の中の演出({@link CloudInterior})も穴の中は雲の外として扱う。</li>
 * </ul>
 * サーバーは穴を知らない(空の巨大生物が雲に隠れる判定で穴を使うなら、そのときサーバーへ移す)。数値はすべて仮決め。設定 {@code cloudHoles}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudHoles {
    static final int MAX = 8;
    /** 半径(ブロック): 攻城砲の砲弾・胞子爆弾・花のビーム。 */
    static final float R_CANNON = 28.0F;
    static final float R_SPORE = 34.0F;
    static final float R_BEAM = 40.0F;
    /** 開く速さ・光る長さ・埋まり始めと埋まりきり(tick)。 */
    static final int GROW = 8;
    static final float GLOW_TICKS = 60.0F;
    static final int FILL_FROM = 1200;
    static final int FILL_TO = 3600;
    /** 水蒸気の輪の長さ(tick)。 */
    static final int RING_TICKS = 32;

    static final class Hole {
        double ax;
        double ay;
        double az;
        double bx;
        double by;
        double bz;
        float r;
        long born;
        long lastLit;
        boolean open;
        boolean beam;
        /** 開く長さ・埋まり始め・埋まりきり(tick。10-19 からサーバーが決める穴 {@link #onHole} は自分の値)。 */
        int grow = GROW;
        int fillFrom = FILL_FROM;
        int fillTo = FILL_TO;
    }

    private record Ring(double x, double y, double z, float dx, float dy, float dz, float r, long born) {
    }

    private static final List<Hole> HOLES = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final Map<Integer, Hole> SHELL_HOLES = new HashMap<>();
    private static final Map<Integer, Boolean> SHELL_INSIDE = new HashMap<>();
    private static final List<Hole> BEAM_HOLES = new ArrayList<>();
    private static ClientLevel lastLevel;
    private static boolean broken;
    // 雲の箱を確かめるための雲の量と積乱雲(穴を探す所のまわり。カメラのまわりではない)。
    private static float coverage;
    private static float threshold;
    private static List<CloudField.Storm> storms = List.of();
    private static double stormsX = Double.NaN;
    private static double stormsZ = Double.NaN;
    private static long stormsTick;
    private static VertexBuffer ringVbo;
    private static VertexBuffer wallVbo;

    static float setting() {
        return OvergrownVegetationConfig.COMMON.cloudHoles.get().floatValue();
    }

    /** 場の座標の点が穴の中か(雲の中の演出・雲の箱の判定が読む)。 */
    static boolean inside(double fx, double y, double fz) {
        if (HOLES.isEmpty()) {
            return false;
        }
        long now = lastLevel == null ? 0L : lastLevel.getGameTime();
        for (Hole h : HOLES) {
            float r = radius(h, now, 1.0F);
            if (r > 0.5F && capsule(fx, y, fz, h) < r) {
                return true;
            }
        }
        return false;
    }

    private static double capsule(double px, double py, double pz, Hole h) {
        double abx = h.bx - h.ax;
        double aby = h.by - h.ay;
        double abz = h.bz - h.az;
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 < 1.0E-6 ? 0.0 : Mth.clamp(((px - h.ax) * abx + (py - h.ay) * aby + (pz - h.az) * abz) / len2, 0.0, 1.0);
        double dx = px - h.ax - abx * t;
        double dy = py - h.ay - aby * t;
        double dz = pz - h.az - abz * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 今の半径(開く・埋まる)。 */
    static float radius(Hole h, long gameTime, float partialTick) {
        float age = (float) (gameTime - h.born) + partialTick;
        float grow = Mth.clamp(age / h.grow, 0.0F, 1.0F);
        float fill = Mth.clamp((age - h.fillFrom) / (float) (h.fillTo - h.fillFrom), 0.0F, 1.0F);
        return h.r * grow * (2.0F - grow) * (1.0F - fill * fill * (3.0F - 2.0F * fill));
    }

    /** 切り口の光(開いた直後・ビームの照射中は 1)。 */
    static float glow(Hole h, long gameTime, float partialTick) {
        float since = (float) (gameTime - h.lastLit) + partialTick;
        return (float) Math.exp(-Math.max(0.0F, since) / GLOW_TICKS);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            HOLES.clear();
            RINGS.clear();
            SHELL_HOLES.clear();
            SHELL_INSIDE.clear();
            BEAM_HOLES.clear();
            storms = List.of();
            stormsX = Double.NaN;
        }
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        try {
            tick(level);
        } catch (RuntimeException e) {
            broken = true;
            HOLES.clear();
            OvergrownVegetation.LOGGER.error("雲の穴で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(ClientLevel level) {
        long now = level.getGameTime();
        HOLES.removeIf(h -> !h.open && now - h.born > h.fillTo);
        RINGS.removeIf(r -> now - r.born() > RING_TICKS);
        if (setting() <= 0.0F || !VoxelClouds.active()) {
            return;
        }
        coverage = VoxelClouds.coverage(level, 1.0F);
        threshold = CloudField.threshold(coverage);
        shells(level, now);
        beam(level, now);
        while (HOLES.size() > MAX) {
            Hole oldest = null;
            for (Hole h : HOLES) {
                if (!h.open && (oldest == null || h.born < oldest.born)) {
                    oldest = h;
                }
            }
            HOLES.remove(oldest == null ? HOLES.get(0) : oldest);
        }
    }

    /** 世界の座標の点が雲の箱の中か(まわりの積乱雲は点の近くで引き直す)。 */
    private static boolean solid(ClientLevel level, long now, double x, double y, double z) {
        if (y < CloudField.LOW_BASE || y > CloudField.GIANT_TOP + 40.0) {
            return false;
        }
        double fx = CloudField.fieldX(x, now, 1.0F);
        double fz = CloudField.fieldZ(z, now, 1.0F);
        if (Double.isNaN(stormsX) || Math.abs(fx - stormsX) > 1200.0 || Math.abs(fz - stormsZ) > 1200.0 || now - stormsTick > 100) {
            double r = 3200.0;
            storms = VoxelClouds.storms(level, 1.0F, coverage, fx - r, fz - r, fx + r, fz + r);
            stormsX = fx;
            stormsZ = fz;
            stormsTick = now;
        }
        // 穴は数えない(数えると、開けたばかりの自分の穴を雲の外と見て、筒が伸びずに小さな穴が連なった)。
        return CloudInterior.solidAt(storms, threshold, coverage, fx, y, fz, true) != 0;
    }

    private static void shells(ClientLevel level, long now) {
        java.util.Set<Integer> alive = new java.util.HashSet<>();
        for (ClientShells.Shell s : ClientShells.shells()) {
            alive.add(s.id());
            double t1 = now - s.startGameTime();
            if (t1 < 1.0) {
                continue;
            }
            Vec3 from = s.positionAt(t1 - 1.0);
            Vec3 to = s.positionAt(t1);
            int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 4.0));
            boolean inside = SHELL_INSIDE.getOrDefault(s.id(), false);
            for (int k = 1; k <= steps; k++) {
                Vec3 p = s.positionAt(t1 - 1.0 + k / (double) steps);
                boolean now2 = solid(level, now, p.x, p.y, p.z);
                if (now2 && !inside) {
                    Vec3 v = s.velocityAt(t1);
                    Hole h = open(level, now, p, s.kind() == 1 ? R_SPORE : R_CANNON, false);
                    SHELL_HOLES.put(s.id(), h);
                    ring(now, p, v, h.r);
                } else if (now2) {
                    Hole h = SHELL_HOLES.get(s.id());
                    if (h != null) {
                        extend(h, now, p);
                    }
                } else if (inside) {
                    Hole h = SHELL_HOLES.remove(s.id());
                    if (h != null) {
                        h.open = false;
                    }
                }
                inside = now2;
            }
            SHELL_INSIDE.put(s.id(), inside);
        }
        for (Iterator<Map.Entry<Integer, Hole>> it = SHELL_HOLES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Hole> e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().open = false;
                it.remove();
            }
        }
        SHELL_INSIDE.keySet().retainAll(alive);
    }

    private static void beam(ClientLevel level, long now) {
        SiegeBossStatePayload.BeamState flower = ClientSiegeBoss.flower();
        if (flower == null || flower.phase() != SiegeData.BEAM_FIRE) {
            for (Hole h : BEAM_HOLES) {
                h.open = false;
            }
            BEAM_HOLES.clear();
            return;
        }
        Vec3 start = ClientSiegeBoss.flowerPosition(1.0F);
        Vec3 target = flower.target();
        if (start == null || target == null) {
            return;
        }
        Vec3 dir = target.subtract(start);
        double len = dir.length();
        if (len < 1.0) {
            return;
        }
        dir = dir.scale(1.0 / len);
        double total = len + OvergrownVegetationConfig.COMMON.siegeBeamOvershoot.get();
        // 雲の中の区間を探す(8 ブロックおき)。区間ごとに、入った所が近い穴を伸ばす(無ければ開ける)。
        boolean inside = false;
        Vec3 entry = null;
        Vec3 last = null;
        List<Vec3[]> spans = new ArrayList<>();
        for (double d = 0.0; d <= total; d += 8.0) {
            Vec3 p = start.add(dir.scale(d));
            boolean s = solid(level, now, p.x, p.y, p.z);
            if (s && !inside) {
                entry = p;
            } else if (!s && inside) {
                spans.add(new Vec3[]{entry, last});
            }
            inside = s;
            last = p;
        }
        if (inside && entry != null) {
            spans.add(new Vec3[]{entry, last});
        }
        for (Vec3[] span : spans) {
            double fx = CloudField.fieldX(span[0].x, now, 1.0F);
            double fz = CloudField.fieldZ(span[0].z, now, 1.0F);
            Hole match = null;
            for (Hole h : BEAM_HOLES) {
                if (Math.abs(h.ax - fx) < 40.0 && Math.abs(h.ay - span[0].y) < 40.0 && Math.abs(h.az - fz) < 40.0) {
                    match = h;
                }
            }
            if (match == null) {
                match = open(level, now, span[0], R_BEAM, true);
                BEAM_HOLES.add(match);
                ring(now, span[0], dir, R_BEAM);
            }
            extend(match, now, span[1]);
            match.lastLit = now;
        }
    }

    private static Hole open(ClientLevel level, long now, Vec3 at, float r, boolean beam) {
        Hole h = new Hole();
        h.ax = h.bx = CloudField.fieldX(at.x, now, 1.0F);
        h.ay = h.by = at.y;
        h.az = h.bz = CloudField.fieldZ(at.z, now, 1.0F);
        h.r = r;
        h.born = now;
        h.lastLit = now;
        h.open = true;
        h.beam = beam;
        HOLES.add(h);
        return h;
    }

    private static void extend(Hole h, long now, Vec3 to) {
        h.bx = CloudField.fieldX(to.x, now, 1.0F);
        h.by = to.y;
        h.bz = CloudField.fieldZ(to.z, now, 1.0F);
    }

    private static void ring(long now, Vec3 at, Vec3 dir, float r) {
        Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : dir.normalize();
        RINGS.add(new Ring(CloudField.fieldX(at.x, now, 1.0F), at.y, CloudField.fieldZ(at.z, now, 1.0F), (float) d.x, (float) d.y, (float) d.z, r, now));
    }

    /**
     * サーバーから雲の丸い穴が来た(10-19、{@link com.yayuy.overgrownvegetation.sky.CloudSurface#hole}: 空の巨大生物が雲海へ潜った所)。縦の筒を開き、
     * 上の口に水蒸気の輪を広げる。開く長さ・埋まり方はサーバーが決めた値。
     */
    public static void onHole(com.yayuy.overgrownvegetation.network.CloudHolePayload p) {
        Hole h = new Hole();
        h.ax = h.bx = p.fx();
        h.az = h.bz = p.fz();
        h.ay = p.bottom();
        h.by = p.top();
        h.r = p.radius();
        h.born = p.start();
        h.lastLit = p.start() - 100000L;
        h.open = false;
        h.grow = p.open();
        h.fillFrom = p.fillFrom();
        h.fillTo = p.fillTo();
        HOLES.add(h);
        RINGS.add(new Ring(p.fx(), p.top(), p.fz(), 0.0F, 1.0F, 0.0F, p.radius(), p.start()));
    }

    /** 開発用(テストクライアントの {@code wind hole}): その点を縦に貫く穴を開ける。 */
    public static void devPunch(double x, double y, double z, float r) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        coverage = VoxelClouds.coverage(level, 1.0F);
        threshold = CloudField.threshold(coverage);
        // 縦の線で、y にいちばん近い雲の区間(下の面から上の面まで)を探す(本物の砲弾と同じく、穴は雲の中だけ)。
        double bottom = Double.NaN;
        double top = Double.NaN;
        double spanFrom = Double.NaN;
        for (double yy = y - 200.0; yy <= y + 202.0; yy += 2.0) {
            boolean in = yy <= y + 200.0 && solid(level, now, x, yy, z);
            if (in && Double.isNaN(spanFrom)) {
                spanFrom = yy;
            } else if (!in && !Double.isNaN(spanFrom)) {
                double spanTo = yy - 2.0;
                double gap = y < spanFrom ? spanFrom - y : y > spanTo ? y - spanTo : 0.0;
                double best = Double.isNaN(bottom) ? Double.MAX_VALUE : (y < bottom ? bottom - y : y > top ? y - top : 0.0);
                if (gap < best) {
                    bottom = spanFrom;
                    top = spanTo;
                }
                spanFrom = Double.NaN;
            }
        }
        if (Double.isNaN(bottom)) {
            return;
        }
        Hole h = open(level, now, new Vec3(x, bottom, z), r, false);
        extend(h, now, new Vec3(x, top, z));
        h.open = false;
        ring(now, new Vec3(x, bottom, z), new Vec3(0.0, 1.0, 0.0), r);
    }

    /** 今ある穴の数(開発用のログ)。 */
    public static int count() {
        return HOLES.size();
    }

    /**
     * 雲のシェーダーへ穴を渡す(近い順に {@link #MAX} まで、2 つずつ 4 つの行列に。列 = A(カメラからの位置, 半径)・B(カメラからの位置, 光))。
     */
    static void uniforms(ShaderInstance shader, ClientLevel level, float partialTick, double camX, double camY, double camZ) {
        long now = level.getGameTime();
        double windX = CloudField.offsetX((double) now + partialTick);
        double windZ = CloudField.offsetZ((double) now + partialTick);
        List<Hole> list = new ArrayList<>(HOLES);
        list.sort((a, b) -> Double.compare(dist2(a, windX, windZ, camX, camZ), dist2(b, windX, windZ, camX, camZ)));
        float setting = setting();
        for (int m = 0; m < 4; m++) {
            Matrix4f mat = new Matrix4f().zero();
            for (int k = 0; k < 2; k++) {
                int i = m * 2 + k;
                if (i >= list.size() || setting <= 0.0F) {
                    continue;
                }
                Hole h = list.get(i);
                float r = radius(h, now, partialTick);
                mat.setColumn(k * 2, new Vector4f((float) (h.ax + windX - camX), (float) (h.ay - camY), (float) (h.az + windZ - camZ), r));
                mat.setColumn(k * 2 + 1, new Vector4f((float) (h.bx + windX - camX), (float) (h.by - camY), (float) (h.bz + windZ - camZ),
                        glow(h, now, partialTick) * setting));
            }
            shader.safeGetUniform("Holes" + m).set(mat);
        }
    }

    private static double dist2(Hole h, double windX, double windZ, double camX, double camZ) {
        double dx = (h.ax + h.bx) * 0.5 + windX - camX;
        double dz = (h.az + h.bz) * 0.5 + windZ - camZ;
        return dx * dx + dz * dz;
    }

    /** 水蒸気の輪(穴の入口に、道筋に垂直に広がって消える輪)。雲を描いた後に、同じ奥行きの幅で。 */
    static void drawRings(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                          boolean far, float split) {
        ShaderInstance shader = ModShaders.cloudHole;
        if (RINGS.isEmpty() || shader == null || setting() <= 0.0F) {
            return;
        }
        long now = level.getGameTime();
        double windX = CloudField.offsetX((double) now + partialTick);
        double windZ = CloudField.offsetZ((double) now + partialTick);
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        ByteBufferBuilder bytes = new ByteBufferBuilder(RINGS.size() * 64 * 4 * 16 + 256);
        try {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int quads = 0;
            for (Ring ring : RINGS) {
                float age = (float) (now - ring.born()) + partialTick;
                float t = Mth.clamp(age / RING_TICKS, 0.0F, 1.0F);
                float rr = ring.r() * (0.6F + 3.4F * (1.0F - (1.0F - t) * (1.0F - t)));
                float alpha = 0.7F * (1.0F - t) * Math.min(1.0F, age / 3.0F);
                if (alpha <= 0.01F) {
                    continue;
                }
                Vec3 n = new Vec3(ring.dx(), ring.dy(), ring.dz());
                Vec3 u = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0)).normalize();
                Vec3 v = n.cross(u).normalize();
                double cx = ring.x() + windX - camX;
                double cy = ring.y() - camY;
                double cz = ring.z() + windZ - camZ;
                float[] radii = {rr * 0.72F, rr, rr * 1.22F};
                float[] alphas = {0.0F, alpha, 0.0F};
                int seg = 40;
                for (int band = 0; band < 2; band++) {
                    for (int i = 0; i < seg; i++) {
                        double a0 = i * Math.PI * 2.0 / seg;
                        double a1 = (i + 1) * Math.PI * 2.0 / seg;
                        vertex(b, cx, cy, cz, u, v, a0, radii[band], cloud, alphas[band]);
                        vertex(b, cx, cy, cz, u, v, a1, radii[band], cloud, alphas[band]);
                        vertex(b, cx, cy, cz, u, v, a1, radii[band + 1], cloud, alphas[band + 1]);
                        vertex(b, cx, cy, cz, u, v, a0, radii[band + 1], cloud, alphas[band + 1]);
                        quads++;
                    }
                }
            }
            if (quads == 0) {
                return;
            }
            MeshData mesh = b.build();
            if (mesh == null) {
                return;
            }
            if (ringVbo == null) {
                ringVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            ringVbo.bind();
            ringVbo.upload(mesh);
            ringVbo.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        } finally {
            bytes.close();
        }
    }

    /** 穴の内側の壁(雲の色の筒。雲の面を塗った後、巻雲・輪より先に、奥行きを書いて描く)。 */
    static void drawWalls(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                          boolean far, float split) {
        ShaderInstance shader = ModShaders.cloudHole;
        if (HOLES.isEmpty() || shader == null || setting() <= 0.0F) {
            return;
        }
        long now = level.getGameTime();
        double windX = CloudField.offsetX((double) now + partialTick);
        double windZ = CloudField.offsetZ((double) now + partialTick);
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        if (sun.y < 0.0) {
            sun = sun.scale(-1.0);
        }
        int seg = 32;
        int rows = 6;
        ByteBufferBuilder bytes = new ByteBufferBuilder(HOLES.size() * seg * rows * 4 * 16 + 256);
        try {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int quads = 0;
            for (Hole h : HOLES) {
                float r = radius(h, now, partialTick) * 1.06F;
                if (r < 1.0F) {
                    continue;
                }
                Vec3 a = new Vec3(h.ax + windX - camX, h.ay - camY, h.az + windZ - camZ);
                Vec3 c = new Vec3(h.bx + windX - camX, h.by - camY, h.bz + windZ - camZ);
                Vec3 axis = c.subtract(a);
                double len = axis.length();
                if (len < 2.0) {
                    continue;
                }
                Vec3 n = axis.scale(1.0 / len);
                Vec3 u = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0)).normalize();
                Vec3 v = n.cross(u).normalize();
                for (int j = 0; j < rows; j++) {
                    double t0 = j / (double) rows;
                    double t1 = (j + 1) / (double) rows;
                    for (int i = 0; i < seg; i++) {
                        double a0 = i * Math.PI * 2.0 / seg;
                        double a1 = (i + 1) * Math.PI * 2.0 / seg;
                        wall(b, a, axis, u, v, a0, t0, r, cloud, sun);
                        wall(b, a, axis, u, v, a1, t0, r, cloud, sun);
                        wall(b, a, axis, u, v, a1, t1, r, cloud, sun);
                        wall(b, a, axis, u, v, a0, t1, r, cloud, sun);
                        quads++;
                    }
                }
            }
            if (quads == 0) {
                return;
            }
            MeshData mesh = b.build();
            if (mesh == null) {
                return;
            }
            if (wallVbo == null) {
                wallVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            wallVbo.bind();
            wallVbo.upload(mesh);
            wallVbo.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            RenderSystem.enableCull();
        } finally {
            bytes.close();
        }
    }

    /** 壁の頂点 1 つ: 内向きの向きで日の当たり方を、筒の長さの真ん中ほど暗く(奥)。色は雲の中間と影の色の間(セル画の 2 つの色)。 */
    private static void wall(BufferBuilder b, Vec3 a, Vec3 axis, Vec3 u, Vec3 v, double ang, double t, float r, Vec3 cloud, Vec3 sun) {
        double cs = Math.cos(ang);
        double sn = Math.sin(ang);
        Vec3 out = u.scale(cs).add(v.scale(sn));
        Vec3 p = a.add(axis.scale(t)).add(out.scale(r));
        double lit = Math.max(0.0, -(out.x * sun.x + out.y * sun.y + out.z * sun.z));
        double depth = 1.0 - Math.abs(t - 0.5) * 2.0;
        float k = (float) Mth.clamp((0.35 + 0.65 * lit) * (1.0 - 0.75 * depth), 0.0, 1.0);
        float cr = (float) cloud.x * Mth.lerp(k, 0.55F, 0.86F);
        float cg = (float) cloud.y * Mth.lerp(k, 0.59F, 0.88F);
        float cb = (float) cloud.z * Mth.lerp(k, 0.8F, 0.95F);
        b.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(cr, cg, cb, 1.0F);
    }

    private static void vertex(BufferBuilder b, double cx, double cy, double cz, Vec3 u, Vec3 v, double a, float r, Vec3 cloud, float alpha) {
        double c = Math.cos(a) * r;
        double s = Math.sin(a) * r;
        b.addVertex((float) (cx + u.x * c + v.x * s), (float) (cy + u.y * c + v.y * s), (float) (cz + u.z * c + v.z * s))
                .setColor((float) Math.min(1.0, cloud.x * 1.05), (float) Math.min(1.0, cloud.y * 1.05), (float) Math.min(1.0, cloud.z * 1.05), alpha);
    }

    private CloudHoles() {
    }
}
