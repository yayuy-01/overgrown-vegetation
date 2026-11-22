package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <b>濃霧の中の明かりの暈</b>(2026-11-17)。霧の日は、松明やランタンのまわりの霧が光を散らして、ぼやけた丸い光に見える。
 * 光のにじみ({@code GlowBloom})は画面の中の小さな炎をほとんど拾わず暈にならなかったので、明かりのブロックの所へ、加算のぼやけた丸を直接足す
 * (霧の中の太陽 {@link DenseFog#renderSun} と同じ描き方)。光の量(ブロックの明るさ)は変えない。
 * <p>
 * 明かりは、濃霧の間だけ自分で集める。{@link CaveLights} の一覧は地形のシェーダーにも渡っていて、昼の地上では空なので、そちらの条件は緩めない
 * (種類の見分け {@link CaveLights#lookup} だけ借りる)。洞窟の中には天気の霧が無いので出さない({@code MistView} の霞と同じ係数)。
 * 値はどれも仮決め。
 */
final class FogHalos {

    /** 描く数の上限(近い順)。 */
    private static final int MAX = 40;
    /** これより近くに並ぶ明かりは 1 つにまとめる(シャンデリアや松明の列が白く飛ばないように)。 */
    private static final double CLUSTER = 2.5;
    private static final int SCAN_TICKS = 40;
    /** 集める半径の上限(ブロック)。 */
    private static final int SCAN_MAX = 72;
    /** 全体の強さ(昼・夜)。昼は霧そのものが明るく、足しても白く飛ぶだけなので弱め。 */
    private static final float DAY = 0.4F;
    private static final float NIGHT = 0.8F;

    private record Halo(double x, double y, double z, float radius, float r, float g, float b, float strength) {
    }

    private static volatile Halo[] halos = new Halo[0];
    private static CompletableFuture<Halo[]> scanning;
    private static long scannedAt = -1_000_000L;

    /** 洞窟の中では出さない分(霧の霞と同じ)。 */
    private static float open() {
        return 1.0F - Math.max(CaveLights.mix(), ForestPresence.underground());
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks) {
        if (scanning != null && scanning.isDone()) {
            Halo[] done = scanning.getNow(null);
            scanning = null;
            if (done != null) {
                halos = done;
            }
        }
        if (DenseFog.amount() * open() <= 0.01F) {
            if (halos.length > 0) {
                halos = new Halo[0];
            }
            scannedAt = -1_000_000L;
            return;
        }
        if (scanning == null && ticks - scannedAt >= SCAN_TICKS) {
            scannedAt = ticks;
            int cx = Mth.floor(eye.x);
            int cy = Mth.floor(eye.y);
            int cz = Mth.floor(eye.z);
            // 霧の見通しの 1.6 倍より先の暈は、霧に消えて見えない。
            int radius = Math.min(SCAN_MAX, Math.round(DesignData.atmosphere().denseFog().visibility() * 1.6F));
            scanning = CompletableFuture.supplyAsync(() -> scan(level, cx, cy, cz, radius), ScanThread.EXECUTOR).exceptionally(t -> {
                OvergrownVegetation.LOGGER.warn("霧の中の明かりを集められなかった", t);
                return null;
            });
        }
    }

    static void clear() {
        halos = new Halo[0];
        scanning = null;
        scannedAt = -1_000_000L;
    }

    /** 作業スレッド: カメラのまわりの区画から明かりを集める({@code CaveLights.scan} と同じ読み方)。 */
    private static Halo[] scan(ClientLevel level, int cx, int cy, int cz, int radius) {
        List<Halo> out = new ArrayList<>();
        int r2 = radius * radius;
        int minSx = SectionPos.blockToSectionCoord(cx - radius);
        int maxSx = SectionPos.blockToSectionCoord(cx + radius);
        int minSz = SectionPos.blockToSectionCoord(cz - radius);
        int maxSz = SectionPos.blockToSectionCoord(cz + radius);
        int minSy = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(cy - radius));
        int maxSy = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(cy + radius));
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = sections[level.getSectionIndexFromSectionY(sy)];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> CaveLights.lookup(s) != null)) {
                        continue;
                    }
                    int bx = SectionPos.sectionToBlockCoord(sx);
                    int by = SectionPos.sectionToBlockCoord(sy);
                    int bz = SectionPos.sectionToBlockCoord(sz);
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                CaveLights.Kind kind = CaveLights.lookup(state);
                                if (kind == null) {
                                    continue;
                                }
                                int wx = bx + x;
                                int wy = by + y;
                                int wz = bz + z;
                                int dx = wx - cx;
                                int dy = wy - cy;
                                int dz = wz - cz;
                                if (dx * dx + dy * dy + dz * dz > r2) {
                                    continue;
                                }
                                // 溶岩は面が多いので、上が溶岩でない面だけを 4 マスに 1 つ(CaveLights と同じ間引き)。
                                if (state.is(Blocks.LAVA)
                                        && (Math.floorMod(wx * 7 + wz * 13 + wy * 3, 4) != 0
                                        || y < 15 && section.getBlockState(x, y + 1, z).is(Blocks.LAVA))) {
                                    continue;
                                }
                                // 暈の大きさは光源の届く半径の 1/4(松明で約 2.5 ブロック)。弱い光は小さく、強い光も 3 まで。
                                float radiusWorld = Mth.clamp(kind.radius() * 0.25F, 0.9F, 3.0F);
                                out.add(new Halo(wx + 0.5, wy + 0.5, wz + 0.5, radiusWorld, kind.r(), kind.g(), kind.b(), kind.strength()));
                            }
                        }
                    }
                }
            }
        }
        // 近い順に、そばに並ぶものをまとめながら上限まで。
        out.sort(Comparator.comparingDouble(h -> sq(h.x - cx, h.y - cy, h.z - cz)));
        List<Halo> kept = new ArrayList<>(MAX);
        for (Halo h : out) {
            boolean near = false;
            for (Halo k : kept) {
                if (sq(h.x - k.x, h.y - k.y, h.z - k.z) < CLUSTER * CLUSTER) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                kept.add(h);
                if (kept.size() >= MAX) {
                    break;
                }
            }
        }
        return kept.toArray(new Halo[0]);
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    /** {@link Atmosphere} の加算の光の所から。 */
    static void render(RenderLevelStageEvent event) {
        Halo[] list = halos;
        float amount = DenseFog.amount() * open();
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (list.length == 0 || amount <= 0.01F || level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float night = SunLight.night(level, partial);
        float base = amount * Mth.lerp(night, DAY, NIGHT);
        float k = DenseFog.hazeK();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        for (Halo h : list) {
            double dx = h.x - cam.x, dy = h.y - cam.y, dz = h.z - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.5) {
                continue;
            }
            // 遠いほど間の霧が増えて暈は広がり、やがて霧に消える(暈そのものは霧が掛からないので、ここで消す)。
            float radius = h.radius * (1.0F + (float) dist * 0.02F);
            float far = (float) Math.exp(-0.6 * k * dist);
            // すぐそばでは消す(画面いっぱいの光の膜にしない)。
            float close = SunLight.smooth(Mth.clamp(((float) dist - 1.0F) / (radius * 1.2F), 0.0F, 1.0F));
            float alpha = base * h.strength * far * close;
            if (alpha <= 0.004F) {
                continue;
            }
            // 壁や地面に切られないよう、見かけの大きさを変えずに手前へ引く。
            double pull = Math.min(radius, dist * 0.5);
            double s = (dist - pull) / dist;
            float x = (float) (dx * s), y = (float) (dy * s), z = (float) (dz * s);
            float rr = radius * (float) s;
            double ix = dx / dist, iy = dy / dist, iz = dz / dist;
            disc(c, x, y, z, ix, iy, iz, rr, h.r, h.g, h.b, alpha * 0.45F);
            disc(c, x, y, z, ix, iy, iz, rr * 0.4F, h.r, h.g, h.b, alpha);
        }
        buffers.endBatch(AddLight.renderType());
    }

    /** 視線に直交する面の丸(真ん中が明るく、縁へ消える)。 */
    private static void disc(VertexConsumer c, float x, float y, float z, double dirX, double dirY, double dirZ, float radius, float r, float g, float b, float alpha) {
        double ux = -dirZ, uz = dirX;
        double ul = Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4) {
            ux = 1.0;
            uz = 0.0;
            ul = 1.0;
        }
        ux /= ul;
        uz /= ul;
        // v = dir × u
        double vx = dirY * uz, vy = dirZ * ux - dirX * uz, vz = -dirY * ux;
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2.0 / n;
            double a1 = (i + 1) * Math.PI * 2.0 / n;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex((float) (x + ux * c0 + vx * s0), (float) (y + vy * s0), (float) (z + uz * c0 + vz * s0)).setColor(r, g, b, 0.0F);
            c.addVertex((float) (x + ux * c1 + vx * s1), (float) (y + vy * s1), (float) (z + uz * c1 + vz * s1)).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    static String describe() {
        return String.format("霧の暈 %d 個(霧 %.2f・外 %.2f)", halos.length, DenseFog.amount(), open());
    }

    private FogHalos() {
    }
}
