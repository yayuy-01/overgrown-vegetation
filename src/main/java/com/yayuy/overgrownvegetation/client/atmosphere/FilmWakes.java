package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.corruption.FilmTrails;
import com.yayuy.overgrownvegetation.network.FilmTrailPayload;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 水面の膜をかき分けた筋の描画(クライアント。2026-11-13、ユーザー「水草が大量に浮いている所を人や動物が通ったとき、軌跡が少しだけ残る」・
 * 見本 B「筋の中にも草が少し残る薄い筋」・約 1 分で閉じる)。跡の点はサーバーの {@link FilmTrails} から届く(Mob が読む跡と同じ)。
 * 浮く落ち葉の跡だけはここで足す(クライアントだけの物)。
 * <p>
 * 筋はカプセル(線分 + 半分の幅)で持ち、カメラのまわり {@value #BLOCKS} ブロック四方の地図(1 ブロック {@value #PER} 画素)に焼く。
 * 画素 = (焼いた時刻 tick の下位 16 bit を r・g、断面の強さ 0〜1 を b、焼いたら a = 1)。膜を描くシェーダー(ov_wake.glsl。バニラの
 * rendertype_cutout_mipped と Sodium の地形)が、膜の絵の画素ごとに「強さ − 経過 ÷ 1 分」を 4 画素から補間し、正なら抜く(少しだけ残す)、
 * 縁のすぐ外は寄せられた草として暗くする。経過で筋は外から細って閉じる。地図はカメラが 16 ブロック動くと焼き直す。
 * シェーダーパック(Iris)では出ない。数値は 1 分(ユーザー指定)のほかは仮決め。
 */
public final class FilmWakes {

    /** 地図の広さ(ブロック)と 1 ブロックの画素数。 */
    static final int BLOCKS = 96;
    static final int PER = 4;
    static final int SIZE = BLOCKS * PER;
    /** 同じ持ち主の前の点とつなぐのは、この距離・この経過まで。 */
    static final double LINK_DIST = 2.5;
    static final int LINK_TICKS = 30;
    /** 覚えておくカプセルの数の上限(サーバーから届いた筋と、落ち葉の筋は別に数える = 見た目だけの筋が本物の筋を押し出さない)。 */
    static final int MAX_CAPSULES = 6000;
    static final int MAX_LOCAL = 1500;
    /** 胞子の膜を割ったとき胞子が舞う割合(点ごと)。 */
    static final float PUFF_CHANCE = 0.45F;

    private static final ResourceLocation[] FILMS = {
            OvergrownVegetation.id("block/algae_film_sheet"), OvergrownVegetation.id("block/spore_film_sheet"), OvergrownVegetation.id("block/sea_spore_band_sheet")};
    /** 膜の濃淡・色むらの雑音が繰り返す幅(ブロック。ov_wake.glsl の各尺度で割り切れる)。 */
    private static final double LOOK_WRAP = 4200.0;

    private record Capsule(double x0, double z0, double x1, double z1, float width, float strength, long time) {
    }

    private static final List<Capsule> CAPSULES = new ArrayList<>();
    private static final List<Capsule> LOCAL = new ArrayList<>();
    private static final Int2ObjectOpenHashMap<double[]> LAST = new Int2ObjectOpenHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    /** 焼いた時刻(tick、-1 = 無し)と強さ(0〜1)。CPU 側の写し。 */
    private static final long[] TIME = new long[SIZE * SIZE];
    private static final float[] STRENGTH = new float[SIZE * SIZE];

    static {
        java.util.Arrays.fill(TIME, -1L);
    }
    private static DynamicTexture texture;
    private static int originX = Integer.MIN_VALUE;
    private static int originZ = Integer.MIN_VALUE;
    private static int dirtyX0 = Integer.MAX_VALUE;
    private static int dirtyZ0 = Integer.MAX_VALUE;
    private static int dirtyX1 = Integer.MIN_VALUE;
    private static int dirtyZ1 = Integer.MIN_VALUE;
    private static boolean broken;
    private static long now;
    private static int received;
    private static int puffs;

    private FilmWakes() {
    }

    static String describe() {
        return String.format("膜の筋 %d 本・落ち葉 %d 本(届いた点 %d・胞子 %d)", CAPSULES.size(), LOCAL.size(), received, puffs);
    }

    static void clear() {
        CAPSULES.clear();
        LOCAL.clear();
        LAST.clear();
        java.util.Arrays.fill(TIME, -1L);
        originX = Integer.MIN_VALUE;
        markAll();
    }

    static void tick(ClientLevel level) {
        now++;
        if (now % 100 == 0) {
            CAPSULES.removeIf(c -> now - c.time > FilmTrails.LIFETIME);
            LOCAL.removeIf(c -> now - c.time > FilmTrails.LIFETIME);
            LAST.values().removeIf(v -> now - (long) v[2] > LINK_TICKS);
        }
        if (now % 600 == 0) {
            // 閉じきった画素を消す(シェーダーは時刻の下位 16 bit で経過を測るので、約 54 分後に新しく見えてしまう)
            boolean any = false;
            for (int i = 0; i < TIME.length; i++) {
                if (TIME[i] >= 0 && now - TIME[i] > FilmTrails.LIFETIME + 100) {
                    TIME[i] = -1L;
                    any = true;
                }
            }
            if (any) {
                markAll();
            }
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int ox = (Mth.floor(cam.x) >> 4 << 4) - BLOCKS / 2 + 8;
        int oz = (Mth.floor(cam.z) >> 4 << 4) - BLOCKS / 2 + 8;
        if (ox != originX || oz != originZ) {
            originX = ox;
            originZ = oz;
            java.util.Arrays.fill(TIME, -1L);
            for (Capsule c : CAPSULES) {
                bake(c);
            }
            for (Capsule c : LOCAL) {
                bake(c);
            }
            markAll();
        }
    }

    // ---- 筋を足す ----

    /** サーバーから届いた跡の点(ネットワークのスレッドからメインへ回したもの)。 */
    public static void onPoints(FilmTrailPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        float[] v = payload.values();
        for (int i = 0; i < payload.size(); i++) {
            int o = i * FilmTrailPayload.STRIDE;
            double x = v[o];
            double y = v[o + 1];
            double z = v[o + 2];
            float width = v[o + 3];
            float strength = v[o + 4];
            int owner = payload.owners()[i];
            double[] prev = LAST.get(owner);
            if (prev != null && now - (long) prev[2] <= LINK_TICKS && Mth.lengthSquared(x - prev[0], z - prev[1]) <= LINK_DIST * LINK_DIST) {
                add(CAPSULES, MAX_CAPSULES, prev[0], prev[1], x, z, width, strength);
            } else {
                add(CAPSULES, MAX_CAPSULES, x, z, x, z, width, strength);
            }
            LAST.put(owner, new double[]{x, z, now});
            received++;
            puff(level, x, y, z);
        }
    }

    /** クライアントだけの物(浮く落ち葉)の筋。 */
    public static void stampLocal(double x0, double z0, double x1, double z1, float width, float strength) {
        add(LOCAL, MAX_LOCAL, x0, z0, x1, z1, width, strength);
    }

    private static void add(List<Capsule> list, int max, double x0, double z0, double x1, double z1, float width, float strength) {
        Capsule c = new Capsule(x0, z0, x1, z1, width, strength, now);
        list.add(c);
        if (list.size() > max) {
            list.subList(0, list.size() - max).clear();
        }
        if (originX != Integer.MIN_VALUE) {
            bake(c);
        }
    }

    /** カプセルを地図に焼く。画素の「強さ − 経過 ÷ 寿命」が今のものより大きいときだけ上書き。 */
    private static void bake(Capsule c) {
        double r = c.width;
        int tx0 = Math.max(0, Mth.floor((Math.min(c.x0, c.x1) - r - originX) * PER));
        int tx1 = Math.min(SIZE - 1, Mth.floor((Math.max(c.x0, c.x1) + r - originX) * PER));
        int tz0 = Math.max(0, Mth.floor((Math.min(c.z0, c.z1) - r - originZ) * PER));
        int tz1 = Math.min(SIZE - 1, Mth.floor((Math.max(c.z0, c.z1) + r - originZ) * PER));
        if (tx0 > tx1 || tz0 > tz1) {
            return;
        }
        double dx = c.x1 - c.x0;
        double dz = c.z1 - c.z0;
        double len2 = dx * dx + dz * dz;
        double cAge = (now - c.time) / (double) FilmTrails.LIFETIME;
        for (int tz = tz0; tz <= tz1; tz++) {
            double wz = originZ + (tz + 0.5) / PER;
            for (int tx = tx0; tx <= tx1; tx++) {
                double wx = originX + (tx + 0.5) / PER;
                double t = len2 > 1.0E-9 ? Mth.clamp(((wx - c.x0) * dx + (wz - c.z0) * dz) / len2, 0.0, 1.0) : 0.0;
                double d = Math.sqrt(Mth.lengthSquared(wx - (c.x0 + dx * t), wz - (c.z0 + dz * t)));
                double prof = c.strength * (1.0 - d / r);
                if (prof <= 0.0) {
                    continue;
                }
                int i = tz * SIZE + tx;
                double old = TIME[i] < 0 ? -1.0 : STRENGTH[i] - (now - TIME[i]) / (double) FilmTrails.LIFETIME;
                if (prof - cAge > old) {
                    TIME[i] = c.time;
                    STRENGTH[i] = (float) prof;
                }
            }
        }
        dirtyX0 = Math.min(dirtyX0, tx0);
        dirtyZ0 = Math.min(dirtyZ0, tz0);
        dirtyX1 = Math.max(dirtyX1, tx1);
        dirtyZ1 = Math.max(dirtyZ1, tz1);
    }

    private static void markAll() {
        dirtyX0 = 0;
        dirtyZ0 = 0;
        dirtyX1 = SIZE - 1;
        dirtyZ1 = SIZE - 1;
    }

    /** 胞子の膜を割った: 胞子が低く舞う(ユーザー選択。見た目だけ)。 */
    private static void puff(ClientLevel level, double x, double y, double z) {
        if (RANDOM.nextFloat() >= PUFF_CHANCE) {
            return;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.getBlockState(pos).is(ModBlocks.SPORE_FILM.get())) {
            return;
        }
        int n = 1 + RANDOM.nextInt(2);
        for (int i = 0; i < n; i++) {
            level.addParticle(ModParticles.SPORE_JET.get(), x + (RANDOM.nextDouble() - 0.5) * 0.8, y + 0.05, z + (RANDOM.nextDouble() - 0.5) * 0.8,
                    (RANDOM.nextDouble() - 0.5) * 0.04, 0.04 + RANDOM.nextDouble() * 0.05, (RANDOM.nextDouble() - 0.5) * 0.04);
        }
        puffs++;
    }

    // ---- シェーダーへ ----

    /** 空を描いた後・地形の前に({@link Atmosphere} から)、地図と値を cutout_mipped のシェーダーへ(Sodium へは SodiumTerrain が写す)。 */
    static void upload(boolean on) {
        ShaderInstance shader = GameRenderer.getRendertypeCutoutMippedShader();
        if (shader == null) {
            return;
        }
        setLook(shader);
        if (broken || !on || SodiumTerrain.unsupported() || originX == Integer.MIN_VALUE) {
            shader.safeGetUniform("OvWakeA").set(0.0F, 0.0F, 1.0F, 0.0F);
            return;
        }
        try {
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
                markAll();
            }
            NativeImage image = texture.getPixels();
            if (image != null && dirtyX0 <= dirtyX1) {
                for (int tz = dirtyZ0; tz <= dirtyZ1; tz++) {
                    for (int tx = dirtyX0; tx <= dirtyX1; tx++) {
                        int i = tz * SIZE + tx;
                        long t = TIME[i];
                        if (t < 0) {
                            image.setPixelRGBA(tx, tz, 0);
                        } else {
                            int tt = (int) (t & 0xFFFF);
                            int s = Mth.clamp(Math.round(STRENGTH[i] * 255.0F), 0, 255);
                            // NativeImage は ABGR: r = 時刻の下位、g = 上位、b = 強さ
                            image.setPixelRGBA(tx, tz, 0xFF000000 | (s << 16) | ((tt >> 8) << 8) | (tt & 0xFF));
                        }
                    }
                }
                texture.bind();
                image.upload(0, dirtyX0, dirtyZ0, dirtyX0, dirtyZ0, dirtyX1 - dirtyX0 + 1, dirtyZ1 - dirtyZ0 + 1, false, false);
                dirtyX0 = Integer.MAX_VALUE;
                dirtyZ0 = Integer.MAX_VALUE;
                dirtyX1 = Integer.MIN_VALUE;
                dirtyZ1 = Integer.MIN_VALUE;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            float pt = mc.getPartialTick();
            shader.setSampler("OvWakeMap", texture.getId());
            shader.safeGetUniform("OvWakeA").set((float) (originX - cam.x), (float) (originZ - cam.z), (float) BLOCKS, 1.0F);
            shader.safeGetUniform("OvWakeB").set((float) ((now & 0xFFFF) + pt), (float) FilmTrails.LIFETIME, (float) PER, 0.0F);
            shader.safeGetUniform("OvWakeC").set((float) Math.floorMod(originX, 1024), (float) Math.floorMod(originZ, 1024), 0.0F, 0.0F);
        } catch (RuntimeException e) {
            broken = true;
            shader.safeGetUniform("OvWakeA").set(0.0F, 0.0F, 1.0F, 0.0F);
            OvergrownVegetation.LOGGER.error("水面の膜の筋で例外が出たので、このセッションでは使わない", e);
        }
    }

    /**
     * 膜そのものの見た目(2026-11-13、ユーザー「パターン化しないように」): 膜の絵の場所(4 枚のシート)と、数ブロック単位の濃淡・色むらのための
     * カメラの位置。筋とは別に、雰囲気の入り切りに関係なく毎フレーム渡す。
     */
    private static void setLook(ShaderInstance shader) {
        try {
            Minecraft mc = Minecraft.getInstance();
            TextureAtlas atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            String[] names = {"OvWakeUV0", "OvWakeUV1", "OvWakeUV2"};
            for (int k = 0; k < FILMS.length; k++) {
                TextureAtlasSprite s = atlas.getSprite(FILMS[k]);
                shader.safeGetUniform(names[k]).set(s.getU0(), s.getV0(), s.getU1(), s.getV1());
            }
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double x = cam.x - Math.floor(cam.x / LOOK_WRAP) * LOOK_WRAP;
            double z = cam.z - Math.floor(cam.z / LOOK_WRAP) * LOOK_WRAP;
            shader.safeGetUniform("OvFilmLook").set((float) x, (float) z, 1.0F, 0.0F);
        } catch (RuntimeException e) {
            shader.safeGetUniform("OvFilmLook").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
    }
}
