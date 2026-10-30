package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * <b>林冠の影を全部の面に</b>(風の案 C、2026-10-16。ユーザー「霧の色、地脈の光、風の案 C の実装をやっていきましょう」)。
 * 前は林冠の影を地面の上面にだけ掛け算の板で落としていた({@link CanopyShade})ので、影の中の草・シダ・幹は明るいままで浮いていた
 * (Vault 光と風の演出/木漏れ日と光の筋の教科書「斑点が地面にしか落ちない」)。ここでは地形のシェーダーに<b>太陽の当たり方の小さな地図</b>を渡し、
 * 地面・草・シダ・幹・葉のどの面にも同じ影を落とす。
 *
 * <h2>太陽の向きに沿った地図</h2>
 * 太陽の方向に沿った 1 本の線の上の点は、どれも同じ (u, v) = (x − y·sun.x/sun.y, z − y·sun.z/sun.y) を持つ。
 * 地図の 1 マスは 1 本の線(1 ブロックおき、{@link #SIZE}×{@link #SIZE} マス = カメラの周り ±48 ブロック)で、線を下(カメラの {@link #BELOW} 下)
 * から上(カメラの {@link #ABOVE} 上)まで 0.5 ブロック刻みでたどり、通ったブロックから
 * <ul>
 *     <li>いちばん高い固いブロック(不透明)の上端、</li>
 *     <li>いちばん高い葉(当たり判定があって不透明でない)の上端と、葉の枚数、</li>
 *     <li>過繁茂の地域か(外は影を落とさない)</li>
 * </ul>
 * を 1 画素(RGBA)に書く。シェーダー({@code shaders/include/ov_light.glsl})は描く点の (u, v) で地図を引き、
 * 「その線の上で、自分より 1 ブロック以上高い所に固いブロックか葉がある」なら影にする(隣の 4 マスの結果をなめらかに混ぜる)。
 * 固いブロックと葉を分けたのは、線が地面の下で地面を通るため(地面より上の葉だけで影を決める)。
 * 葉の層(cutout_mipped)と固いブロック(solid)は、太陽と反対を向いた面も影にする(草の層は薄いので、しない)。
 * 影の色は地面の影({@link CanopyShade})と同じ 75% のグレー。
 *
 * <h2>作り方</h2>
 * 地図は毎 tick {@link #CELLS_PER_TICK} マスずつ作り、全部できたら入れ替える(約 3.2 秒ごと。作っている間は前の地図を使う)。
 * 地図ごとにその時の太陽の向きを持ち、シェーダーはその向きで (u, v) を求める(作り直しの合間に太陽が動いても線がずれない)。
 * 太陽が低い(高さ {@link #MIN_SUN_Y} 未満)・夜・過繁茂の地域から離れている間は作らず、影も落とさない。
 *
 * <p>シェーダーを差し替えるのはバニラの描画のときだけ。Sodium・Embeddium・Iris が入っているとき、または地図がまだ無いときは、
 * 前のとおり地面の上面にだけ影を落とす({@link CanopyShade}。{@link #active()} が false)。設定 {@code canopySurfaceShade}。
 * 数値はすべて仮決め。
 *
 * <h2>草・シダ・幹の斑点</h2>
 * 葉が 1〜3 枚だけの所(固いブロックは無い)の下では、地面の上面以外の面(草・シダ・幹・葉の横)に小さな斑点が抜ける。模様は太陽の向きの
 * 座標 (u, v) のなめらかな雑音なので、同じ光の通り道の上のシダの葉と後ろの幹には同じ斑点が落ち、斜めの面では太陽の向きに伸びる。
 * 葉が多いほど少なく暗い(1 枚・2 枚・3 枚で、地面の斑点 {@link CanopyDapple} と同じ明るさの比)。枝の揺れで模様ごと行き来し、
 * 1 粒ずつ明滅する。当たった所は少し明るく金色に(草は黄緑に光る)。地面の上面は前のとおり {@link CanopyDapple} の斑点(承認済みの見た目)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopySunMap {

    /** 地図の 1 辺のマス数(1 マス 1 ブロック)。 */
    private static final int SIZE = 96;
    /** 線をたどり始める高さ(カメラの何ブロック下か)と、たどり終える高さ(何ブロック上か)。 */
    private static final int BELOW = 24;
    private static final int ABOVE = 72;
    /** 1 tick に作るマスの数。 */
    private static final int CELLS_PER_TICK = 144;
    /** これより太陽が低いと影を落とさない(線が長くなりすぎる)。 */
    private static final float MIN_SUN_Y = 0.2F;
    /** 影の色(掛け算)。地面の影 {@link CanopyShade} と同じ 75%。 */
    private static final float SHADE = 0.75F;
    /** 逆光に慣れきったときの影の色({@link CanopyLight#backlight()} が 1 のとき。地面の影と同じ 58%)。仮決め。 */
    static final float SHADE_BACKLIT = 0.58F;
    /** 青緑の影の色の比(生の比 0.80 : 1.00 : 1.06 を、明るさ 0.3R + 0.59G + 0.11B が 1 になるようそろえた)。仮決め。 */
    private static final float TINT_R = 0.845F;
    private static final float TINT_G = 1.056F;
    private static final float TINT_B = 1.120F;

    /**
     * <b>林冠の影の色</b>(全部影のときの掛け算、RGB)を {@code out} へ。明るさは逆光の度合いで 75% → 58%({@link CanopyLight#backlight()})。
     * 色は設定 {@code canopyShadeTint} の強さで灰色から青緑へ寄せる(2026-10-16、ユーザー選択「昼の光の改善案 (3)」: 影を青緑に寄せ、
     * 光の筋・木漏れ日・胞子の金色を浮かせる。林の影は空の青と葉を透けた緑の光で照らされるので、日向の暖かい色と対になる)。
     * 明るさ(0.3R + 0.59G + 0.11B)は灰色のときと同じにして、色だけ変える。シェーダーの影と地面の影 {@link CanopyShade} の両方が使う。
     * 開発用: {@link CanopyLight#debugHide} の 2048 で灰色。
     */
    static void shadeColor(float[] out) {
        float level = shadeLevel();
        hue(out);
        out[0] *= level;
        out[1] *= level;
        out[2] *= level;
    }

    /** 全部影のときの明るさ(灰色の掛け算。逆光で 75% → 58%)。 */
    private static float shadeLevel() {
        return Mth.lerp(CanopyLight.backlight(), SHADE, SHADE_BACKLIT);
    }

    /** 青緑の色合い(明るさ 1 の掛け算。設定 {@code canopyShadeTint} の強さ。開発用の 2048 で灰色 = 1, 1, 1)。 */
    private static void hue(float[] out) {
        // 画面の色の調子(2026-10-21、client/light/MoodState)が効いている間は、色はライトマップと LUT が持つ(ここは明暗だけ。Sodium でも同じ色味に)。
        float tint = (CanopyLight.debugHide & 2048) != 0 || false ? 0.0F
                : OvergrownVegetationConfig.COMMON.canopyShadeTint.get().floatValue();
        out[0] = Mth.lerp(tint, 1.0F, TINT_R);
        out[1] = Mth.lerp(tint, 1.0F, TINT_G);
        out[2] = Mth.lerp(tint, 1.0F, TINT_B);
    }

    /**
     * <b>森の中はいつも青緑</b>(2026-10-16、ユーザー「影が青緑色になるのが思ったよりきれい。日光が差す場所以外でも、過繁茂の森の中にいるときは
     * 常にこうなるように」)の強さ(0〜1)。過繁茂の地域にいる度合い({@link CorruptionAtmosphere#forestFactor()}、霧と同じ 10 秒で寄せる)×
     * 設定 {@code canopyForestTint}。シェーダーは、影の地図が日向・影を知っている所(昼・地図の中・過繁茂の線)では影だけを、
     * 知らない所(地図の外・太陽の低い朝夕・夜)では全部の面を、この強さで青緑に寄せる(明るさは変えない)。
     * 松明などのブロックの明かりで照らされた面は寄せない(人類の魔法の琥珀色を残す。{@code ov_light.glsl})。開発用の 2048 で 0。
     */
    private static float forestTint() {
        if ((CanopyLight.debugHide & 2048) != 0 || false) {
            return 0.0F;
        }
        return ForestPresence.forestFactor() * OvergrownVegetationConfig.COMMON.canopyForestTint.get().floatValue();
    }

    private static final float[] SHADE_RGB = new float[3];

    // 描画を置き換える MOD(Sodium・Embeddium・Iris)のときに地図を使うかは SodiumTerrain#unsupported(2026-10-22 から。
    // それまでの OTHER_RENDERER は「入っていれば使わない」だった。Sodium 0.8 には地形のシェーダーへ差し込む)。

    /** 作りかけ・表示中の地図。 */
    private static final class Map {
        final int originU;
        final int originV;
        final int baseY;
        final int topY;
        final double sunX;
        final double sunY;
        final double sunZ;
        final int camY;
        final int[] pixels = new int[SIZE * SIZE];
        int next;

        Map(int originU, int originV, int baseY, int topY, Vec3 sun, int camY) {
            this.originU = originU;
            this.originV = originV;
            this.baseY = baseY;
            this.topY = topY;
            this.sunX = sun.x;
            this.sunY = sun.y;
            this.sunZ = sun.z;
            this.camY = camY;
        }
    }

    /** 裏のスレッド(ScanThread)で作っている地図(2026-10-20 まで描画のスレッドで 1 tick に CELLS_PER_TICK マスずつ作っていた)。 */
    @Nullable
    private static java.util.concurrent.CompletableFuture<Map> building;
    /** 前の地図を作り始めた tick。作り直しの間隔は前と同じ(SIZE * SIZE / CELLS_PER_TICK = 64 tick)。 */
    private static long buildStartTick = -1_000_000L;
    /** 裏のスレッドで続けて失敗した回数(成功で 0 に戻す)。 */
    private static volatile int buildFailures;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static ClientLevel mapLevel;
    private static boolean nearOvergrown;
    private static long ticks;
    private static long buildNanos;
    private static boolean broken;

    /** 地図を使って全部の面に影を落としているか(false なら {@link CanopyShade} が地面にだけ落とす)。 */
    static boolean active() {
        return !SodiumTerrain.unsupported() && !broken && shown != null && strength() > 0.0F;
    }

    private static float strength() {
        if ((CanopyLight.debugHide & 64) != 0) {
            return 0.0F;
        }
        return OvergrownVegetationConfig.COMMON.canopySurfaceShade.get().floatValue();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != mapLevel) {
            building = null;
            buildStartTick = -1_000_000L;
            shown = null;
            mapLevel = level;
        }
        if (level == null || mc.player == null || mc.isPaused() || SodiumTerrain.unsupported() || broken) {
            return;
        }
        ticks++;
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        if (strength() <= 0.0F || !level.dimensionType().hasSkyLight() || sun.y < MIN_SUN_Y
                || !OvergrownVegetationConfig.COMMON.canopyLightEnabled.get()) {
            building = null;
            buildStartTick = -1_000_000L;
            shown = null;
            return;
        }
        // 過繁茂の地域が近くにあるか(1 秒に 1 回、足元と周り 24 ブロックの 8 か所)。無ければ作らない。
        if (ticks % 20 == 1) {
            nearOvergrown = false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 9 && !nearOvergrown; i++) {
                double angle = i * Math.PI / 4.0;
                double r = i == 8 ? 0.0 : 24.0;
                pos.set(mc.player.getX() + Math.cos(angle) * r, mc.player.getY(), mc.player.getZ() + Math.sin(angle) * r);
                nearOvergrown = ModBiomes.isOvergrownLike(level.getBiome(pos));
            }
        }
        if (!nearOvergrown) {
            building = null;
            buildStartTick = -1_000_000L;
            shown = null;
            return;
        }
        if (building != null) {
            if (!building.isDone()) {
                return;
            }
            Map done = building.getNow(null);
            building = null;
            if (done != null && mapLevel == level) {
                shown = done;
                buildFailures = 0;
                uploadPending = true;
            }
        }
        // 前と同じ間隔で作り直す(前は 1 tick に CELLS_PER_TICK マスずつ、地図 1 枚に 64 tick)
        if (ticks - buildStartTick < (long) SIZE * SIZE / CELLS_PER_TICK) {
            return;
        }
        buildStartTick = ticks;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double camU = eye.x - eye.y * sun.x / sun.y;
        double camV = eye.z - eye.y * sun.z / sun.y;
        int camY = Mth.floor(eye.y);
        Map m = new Map(Mth.floor(camU) - SIZE / 2, Mth.floor(camV) - SIZE / 2, camY - BELOW, camY + ABOVE, sun, camY);
        // 線をたどるのは裏のスレッドで(クライアントのワールドを読むだけ。2026-10-20、飛行中に描画のスレッドで 1 tick 2 ms 前後・最大 8 ms だった)
        building = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            for (int index = 0; index < SIZE * SIZE; index++) {
                m.pixels[index] = trace(level, m, index % SIZE, index / SIZE);
            }
            m.next = SIZE * SIZE;
            buildNanos = System.nanoTime() - start;
            return m;
        }, ScanThread.EXECUTOR).exceptionally(t -> {
            // 裏のスレッドで読めないブロックなどで失敗し続けるなら、3 回で止めて地面の影だけに戻す(警告を出し続けない)
            if (++buildFailures >= 3) {
                broken = true;
                OvergrownVegetation.LOGGER.error("林冠の日向の地図を 3 回続けて作れなかったので、このセッションでは使わない(地面の影だけに戻す)", t);
            } else {
                OvergrownVegetation.LOGGER.warn("林冠の日向の地図を作れなかった(次の回にもう一度): {}", t.toString());
            }
            return null;
        });
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("CanopySunMap 表示中={} 1 枚 {} ms(裏のスレッド)", shown != null, String.format("%.2f", buildNanos / 1.0E6));
        }
    }

    /**
     * devbridge の {@code sunmapcheck}: 今出している地図を、描画のスレッドで同じたどり方で作り直し、違う画素の数を返す
     * (裏のスレッドで作っても前と同じ地図になるかの確かめ、2026-10-20)。ワールドが変わっていなければ 0。
     */
    public static String debugCheckSync() {
        Map m = shown;
        ClientLevel level = mapLevel;
        if (m == null || level == null) {
            return "地図なし";
        }
        int diff = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            if (trace(level, m, index % SIZE, index / SIZE) != m.pixels[index]) {
                diff++;
            }
        }
        return "違う画素 " + diff + " / " + SIZE * SIZE;
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、作る重さを 5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 地図のマス (i, j) の線をたどって、画素(ABGR)を返す。 */
    private static int trace(ClientLevel level, Map m, int i, int j) {
        double u = m.originU + i + 0.5;
        double v = m.originV + j + 0.5;
        double kx = m.sunX / m.sunY;
        double kz = m.sunZ / m.sunY;
        // 過繁茂の地域か: 線がカメラの高さを通る所のバイオームで見る。
        BlockPos at = BlockPos.containing(u + m.camY * kx, m.camY, v + m.camY * kz);
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(at));
        double y = m.baseY;
        double x = u + y * kx;
        double z = v + y * kz;
        double dx = m.sunX * 0.5;
        double dy = m.sunY * 0.5;
        double dz = m.sunZ * 0.5;
        int solidTop = 0;
        int leafTop = 0;
        int leaves = 0;
        // 樹冠の数(光の筋・木漏れ日と同じ数え方 CanopyLight#CROWN_GAP・CROWN_DEPTH。厚さで重み付け)。
        int crowns = 0;
        int run = 0;
        double lastLeafY = Double.NEGATIVE_INFINITY;
        double gapY = CanopyLight.CROWN_GAP * m.sunY;
        long last = Long.MIN_VALUE;
        long lastColumn = Long.MIN_VALUE;
        int columnTop = 0;
        long lastSection = Long.MIN_VALUE;
        boolean sectionAir = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (y < m.topY) {
            x += dx;
            y += dy;
            z += dz;
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            long column = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (column != lastColumn) {
                lastColumn = column;
                columnTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            }
            if (by >= columnTop) {
                continue;
            }
            long here = BlockPos.asLong(bx, by, bz);
            if (here == last) {
                continue;
            }
            last = here;
            long section = SectionPos.asLong(bx >> 4, by >> 4, bz >> 4);
            if (section != lastSection) {
                lastSection = section;
                sectionAir = CanopyLight.sectionIsAir(level, bx, by, bz);
            }
            if (sectionAir) {
                continue;
            }
            BlockState state = level.getBlockState(pos.set(bx, by, bz));
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            int top = Mth.clamp(by + 1 - m.baseY, 0, 255);
            if (state.canOcclude()) {
                solidTop = Math.max(solidTop, top);
            } else {
                leafTop = Math.max(leafTop, top);
                leaves++;
                if (run > 0 && y - lastLeafY > gapY) {
                    crowns += CanopyLight.crownWeight(run);
                    run = 0;
                }
                run++;
                lastLeafY = y;
            }
        }
        if (run > 0) {
            crowns += CanopyLight.crownWeight(run);
        }
        int r = solidTop;
        int g = leafTop;
        // シェーダーの斑点は葉の層 1〜3 で出る。層は樹冠の数(開発用の 1024 で前の葉のブロックの数)。
        int layers = (CanopyLight.debugHide & 1024) != 0 ? leaves : crowns;
        int b = Math.min(layers, 15) * 17;
        int a = overgrown ? 255 : 128;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    /** 空を描いた後・地形を描く前に、地図と太陽の向きを地形のシェーダー(固い・草・葉の 3 つ)へ渡す。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || SodiumTerrain.unsupported()) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()};
        try {
            Minecraft mc = Minecraft.getInstance();
            Map m = shown;
            float amount = 0.0F;
            float mapDay = 0.0F;
            Vec3 sun = null;
            if (m != null && mc.level != null) {
                float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
                sun = CanopyLight.sunDirection(mc.level, partialTick);
                mapDay = CanopyLight.daylight(mc.level, sun, partialTick);
                // 太陽が低い所の切り替えは MIN_SUN_Y の 1 か所だけ(ここで更に薄めると、地面の影の板が消えたのに影が薄い間ができる)。
                amount = strength() * mapDay;
            }
            // 森の中の青緑(地図が無い・使わない間も渡す。夜・朝夕・地図の外は全部の面を寄せる)。
            hue(SHADE_RGB);
            float forest = mc.level == null ? 0.0F : forestTint();
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvTint").set(SHADE_RGB[0], SHADE_RGB[1], SHADE_RGB[2], forest);
                }
            }
            if (amount <= 0.0F || sun == null) {
                for (ShaderInstance shader : shaders) {
                    if (shader != null) {
                        shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                    }
                }
                return;
            }
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
            }
            if (uploadPending) {
                NativeImage image = texture.getPixels();
                if (image != null) {
                    for (int j = 0; j < SIZE; j++) {
                        for (int i = 0; i < SIZE; i++) {
                            image.setPixelRGBA(i, j, m.pixels[j * SIZE + i]);
                        }
                    }
                    texture.upload();
                }
                uploadPending = false;
            }
            Vec3 cam = event.getCamera().getPosition();
            double kx = m.sunX / m.sunY;
            double kz = m.sunZ / m.sunY;
            double camU = cam.x - cam.y * kx;
            double camV = cam.z - cam.y * kz;
            // 斑点(葉 1〜3 枚の下の草・シダ・幹): 模様は太陽の向きの座標で世界に固定する。カメラの (u, v) を 1.7 倍して 64 で折り返して渡す
            // (シェーダーの模様は 64 ごとに繰り返すので、折り返しても途切れない)。枝の揺れで模様ごと風の向きへ行き来し、
            // 1 粒ずつはシェーダーの中で明滅する(地面の斑点 CanopyDapple と同じ揺れ)。
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            double windTime = CanopyWind.time(partialTick);
            double gust = Math.min(1.0, CanopyWind.strength(cam.x, cam.z, windTime));
            double branch = (0.02 + 0.22 * gust) * CanopyWind.branch(windTime, 0.0);
            double fleckU = wrap64((camU + branch * CanopyWind.dirX()) * FLECK_SCALE);
            double fleckV = wrap64((camV + branch * CanopyWind.dirZ()) * FLECK_SCALE);
            float fleckOn = (CanopyLight.debugHide & 128) != 0 ? 0.0F : 1.0F;  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)
            float seconds = (float) ((windTime / 20.0) % 1000.0);
            // 影の明るさ: 逆光(太陽の方を向いている)の間は深く(明るい筋に目が慣れた見え方)。色合い(青緑)は OvTint で別に掛ける。
            float level = shadeLevel();
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvSunMap", texture.getId());
                // w = 地図が日向・影を決める度合い(昼の強さ。設定の影の強さは入れない)。
                shader.safeGetUniform("OvSunA").set((float) kx, (float) kz, amount, mapDay);
                shader.safeGetUniform("OvSunDir").set((float) sun.x, (float) sun.y, (float) sun.z, 1.0F);
                shader.safeGetUniform("OvMapA").set((float) (m.originU - camU), (float) (m.originV - camV), (float) SIZE,
                        (float) (m.baseY - cam.y));
                shader.safeGetUniform("OvShadeColor").set(level, level, level, 1.0F);
                shader.safeGetUniform("OvFleck").set((float) fleckU, (float) fleckV, seconds, fleckOn);
            }
        } catch (RuntimeException e) {
            broken = true;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                    shader.safeGetUniform("OvTint").set(1.0F, 1.0F, 1.0F, 0.0F);
                }
            }
            OvergrownVegetation.LOGGER.error("林冠の影の地図で例外が出たので、このセッションでは使わない(地面の影だけに戻す)", e);
        }
    }

    /** 斑点の模様の細かさ(シェーダーの {@code uvRel * 1.7} と同じ値)。 */
    private static final double FLECK_SCALE = 1.7;

    private static double wrap64(double value) {
        return value - Math.floor(value / 64.0) * 64.0;
    }

    private CanopySunMap() {
    }
}
