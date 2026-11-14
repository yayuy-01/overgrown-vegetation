package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * 監視塔フィロセンスに見られているときの画面の縁の<b>見た目</b>(2026-11-14。Vault「Mob の能力/フィロセンス」の「見た目の決定」)。
 * 時間の流れ・設定の読み口は {@link PhyllosensGazeEdge}、ここは描くだけ。
 *
 * <p>ユーザーが了承したプレビュー {@code tools/g5-models/out/phyllosens_tower/watched_preview/watched.html} の {@code frame}・{@code drawEye} を
 * そのまま写した: ユーザーのドット絵の目(まぶた)を縁全体に角度と大きさをばらばらにして開かせ、瞳は別の絵をまぶたの内側だけに描いて動かす。
 * 仕掛け ① 印の瞬間に一斉に瞬いてこちらを向く ② 瞳が太くなる ③ ぴくつき ④ 隠れた後も残る目 ⑤ 鼓動の縁 ⑥ 視界の内側に一瞬開く目。
 * プレビューに無い「慣れ」(印から約 10 秒で目を約半分へ・鼓動も落ち着く)と設定の効き方はここで足した。
 *
 * <h2>絵</h2>
 * {@code textures/gui/phyllosens_gaze_eyes.png}(128×64): 左上 64×32 = まぶた(ユーザーの絵から真ん中の縦の線 x29〜35・y9〜23 を抜いただけ)/
 * (64,0) の 64×32 = 目の内側の形(白。暗い色を掛けて塗る)/ (2,34) と (14,34) の 9×13 = 瞳(細い・太い。プレビューの式で作った。
 * 楕円の外は目の中の暗い色で埋めてある)/ (28,34) の白。
 * 作る道具は {@code tools/g5-models/out/phyllosens_tower/gaze_eyes/MakeAtlas.java}。
 *
 * <h2>描き方</h2>
 * 目 1 つは「暗い地・瞳・まぶた」が重ならないように行ごとに切って並べ、目の濃さを 1 回だけ掛ける(プレビューは目を 1 枚に組んでから
 * 濃さを掛けている。3 枚を半透明で重ねると濁る)。全部の目を小さい順に 1 回の描画で出す。値は下にまとめた(全部【仮】)。
 */
public final class PhyllosensGazeEyes {

    // ================================================================== 仮の値(ここだけを詰めればよい)
    // ---- プレビューの既定(Vault「初めの値」)
    /** 目の数(設定の倍率 100% のとき)。 */
    static final int EYES = 40;
    /** 見られ続けて目が出そろうまで・視線が切れてから閉じきるまで(秒)。 */
    static final float FULL_SECONDS = 6.0F, LINGER_SECONDS = 4.0F;
    /** ドットの大きさ(画面の短い辺 360 分の 1 × これ が絵の 1 ドット。画面の実ピクセルで決め、GUI の拡大率に左右されない)。 */
    static final float DOT = 0.9F;
    /** 大きさのばらつき・ときどき大きい目の割合・角度のばらつき(度)・縁からの深さ(短い辺に対する割合)。 */
    static final float SIZE_SPREAD = 0.6F, BIG_CHANCE = 0.12F, ANGLE_DEG = 70.0F, BAND = 0.16F;
    /** 目の濃さ・縁の暗さ。 */
    static final float EYE_ALPHA = 0.7F, EDGE_DARK = 0.6F;
    /** 目の置き方の種(プレビューの初めの置き方と同じ)。 */
    static final long LAYOUT_SEED = 7L;
    /** 目の開く速さ・閉じる速さ・④ 残る目がぱちんと閉じる速さ(1 秒あたり)・④ 残る目の割合。 */
    static final float OPEN_SPEED = 3.5F, CLOSE_SPEED = 2.2F, STUBBORN_CLOSE_SPEED = 9.0F, STUBBORN_CHANCE = 0.1F;
    /** ① 印の瞬間の一斉の瞬きの長さ・瞳が向くまでの遅れの幅・向ききるまで(秒)。 */
    static final float MARK_BLINK = 0.22F, SNAP_DELAY = 0.12F, SNAP_TURN = 0.08F;
    /** 瞳のさまよう幅・こちらを向いたときの寄り(ドット。横・縦)。 */
    static final float WANDER_X = 9.0F, WANDER_Y = 3.0F, LOOK_X = 11.0F, LOOK_Y = 4.0F;
    /** ③ ぴくつきの長さ(秒)・次までの間(秒。初め 1〜7、その後 2〜9)。 */
    static final float TWITCH_SECONDS = 0.12F, TWITCH_MIN = 2.0F, TWITCH_RANGE = 7.0F;
    /** ⑤ 鼓動: 1 分の拍(目が無い〜出そろった)・暗さの底と山。鼓動を切ったときの暗さ(揺れない)。 */
    static final float BPM_MIN = 50.0F, BPM_RANGE = 60.0F, BEAT_BASE = 0.72F, BEAT_GAIN = 0.4F, STEADY = 0.85F;
    /** ⑥ 視界の内側の目: 出るのは目が半分出てから・間(秒)・開いている長さ(秒)・照準からの距離(短い辺に対する割合。横は 1.6 倍)・濃さの倍率。 */
    static final float INNER_FROM = 0.5F, INNER_FIRST = 3.0F, INNER_MIN = 3.0F, INNER_RANGE = 5.0F, INNER_LIFE = 0.55F,
            INNER_NEAR = 0.12F, INNER_FAR = 0.24F, INNER_WIDE = 1.6F, INNER_ALPHA = 0.7F;
    // ---- プレビューに無い物(慣れ。Vault「両方足す」)
    /** 慣れ: 印からこの tick 見られ続けたら始まり、この秒数をかけて、1 つおきの目をゆっくり(この速さで)閉じる。 */
    static final int HABIT_AFTER_TICKS = 200;
    static final float HABIT_FADE_SECONDS = 8.0F, HABIT_CLOSE_SPEED = 0.8F;
    /** 慣れきったときの鼓動: 拍・山の高さの倍率。視界の内側の目の間は (1 + これ) 倍に延びる。 */
    static final float HABIT_BPM = 46.0F, HABIT_BEAT_GAIN = 0.4F, HABIT_INNER_SLOWER = 1.5F;
    // ================================================================== ここまで

    private static final ResourceLocation ATLAS = OvergrownVegetation.id("textures/gui/phyllosens_gaze_eyes.png");
    private static final float ATLAS_W = 128.0F, ATLAS_H = 64.0F;
    private static final int INSIDE_U = 64, PUPIL_U = 2, PUPIL_V = 34, PUPIL_STEP = 12, PUPIL_W = 9, PUPIL_H = 13;
    private static final float WHITE_U = 31.0F / ATLAS_W, WHITE_V = 37.0F / ATLAS_H;
    /** 目の中の暗い地(プレビューの rgba(8,10,3,0.92))・縁の暗さの色(内 14,10,4 → 外 6,5,2)。 */
    private static final float IN_R = 8 / 255F, IN_G = 10 / 255F, IN_B = 3 / 255F, IN_A = 0.92F;
    private static final int RING_SEGMENTS = 64;

    /** 瞳の行ごとの幅(9×13 の楕円)。 */
    private static final int[] PUPIL_C0 = new int[PUPIL_H], PUPIL_C1 = new int[PUPIL_H];
    /** 目の内側(瞳が見える所)の行ごとの区間 {始め, 終わり, …}。まぶたのドットがある所は除く。 */
    private static int[][] inside;
    private static boolean[] lidRow;

    private static final class Eye {
        float t, depth, scale, rot, appear, blinkPhase, blinkEvery, wanderA, wanderB, wanderS, twitchNext, twitch, snapDelay, open;
        boolean flip, stubborn, calm;
        float calmAt, jx, jy, jp;
    }

    private static Eye[] eyes = new Eye[0];
    private static int builtFor = -1;
    private static final Random RNG = new Random();
    private static long lastMillis = -1L;
    private static float presence, habit, beatPhase, clock, innerNext = INNER_FIRST;
    private static int jitterFrame = -1;
    /** 視界の内側の目 {x, y, 経った秒, 傾き}(窓のピクセル)。無ければ null。 */
    private static float[] inner;

    /** 重さの計り(開発用の口から読む): 描いたフレームの数と合計の時間。 */
    public static long perfFrames, perfNanos;

    static {
        for (int y = 0; y < PUPIL_H; y++) {
            int c0 = PUPIL_W, c1 = 0;
            for (int x = 0; x < PUPIL_W; x++) {
                double dx = (x - 4) / 4.5, dy = (y - 6) / 6.5;
                if (dx * dx + dy * dy > 1.0) continue;
                c0 = Math.min(c0, x);
                c1 = Math.max(c1, x + 1);
            }
            PUPIL_C0[y] = c0;
            PUPIL_C1[y] = c1;
        }
    }

    private PhyllosensGazeEyes() {
    }

    // ------------------------------------------------------------------ 形の用意

    /** 目の内側 = プレビューのアーモンド形(真ん中 (32,16)・半幅 22・半高 8.5)から、まぶたのドットを除いた所。 */
    private static void ensureShape() {
        if (inside != null) return;
        boolean[][] lid = new boolean[32][64];
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(ATLAS); NativeImage img = NativeImage.read(in)) {
            for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) lid[y][x] = (img.getPixelRGBA(x, y) >>> 24) != 0;
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("見られているときの目の絵を読めない: {}", e.toString());
        }
        inside = new int[32][];
        lidRow = new boolean[32];
        int[] tmp = new int[64];
        for (int y = 0; y < 32; y++) {
            int n = 0, start = -1;
            for (int x = 0; x <= 64; x++) {
                boolean free = false;
                if (x < 64) {
                    lidRow[y] |= lid[y][x];
                    double u = (x - 32) / 22.0;
                    if (Math.abs(u) < 1.0) {
                        int hh = (int) Math.floor(8.5 * (1.0 - u * u));
                        free = hh >= 1 && y >= 16 - hh && y < 16 + hh && !lid[y][x];
                    }
                }
                if (free && start < 0) start = x;
                if (!free && start >= 0) {
                    tmp[n++] = start;
                    tmp[n++] = x;
                    start = -1;
                }
            }
            inside[y] = Arrays.copyOf(tmp, n);
        }
    }

    /** プレビューの makeEyes と同じ並び(同じ乱数・同じ順)。数が少ないときは同じ並びの初めの n 個。 */
    private static void build(int n) {
        builtFor = n;
        long[] seed = {LAYOUT_SEED};
        Eye[] list = new Eye[n];
        for (int i = 0; i < n; i++) {
            Eye e = new Eye();
            float sizeK = 1.0F + (rnd(seed) * 2.0F - 1.0F) * SIZE_SPREAD * 0.6F;
            float big = rnd(seed) < BIG_CHANCE ? 1.0F + SIZE_SPREAD : 1.0F;
            e.t = rnd(seed);
            e.depth = (float) Math.pow(rnd(seed), 1.8) * BAND;
            e.scale = Math.max(0.5F, sizeK * big);
            e.rot = (rnd(seed) * 2.0F - 1.0F) * ANGLE_DEG * Mth.DEG_TO_RAD;
            e.flip = rnd(seed) < 0.5F;
            e.appear = (float) Math.pow(rnd(seed), 0.9);
            e.blinkPhase = rnd(seed) * 10.0F;
            e.blinkEvery = 4.0F + rnd(seed) * 6.0F;
            e.wanderA = rnd(seed) * 6.28F;
            e.wanderB = rnd(seed) * 6.28F;
            e.wanderS = 0.4F + rnd(seed) * 0.8F;
            e.stubborn = rnd(seed) < STUBBORN_CHANCE;
            e.twitchNext = 1.0F + rnd(seed) * 6.0F;
            e.snapDelay = rnd(seed) * SNAP_DELAY;
            list[i] = e;
        }
        // 慣れで閉じる目 = 縁に沿って 1 つおき(残る目が片寄って方角の手がかりにならないように)。閉じる順はばらばら
        Eye[] around = list.clone();
        Arrays.sort(around, Comparator.comparingDouble(e -> e.t));
        for (int i = 0; i < n; i++) {
            around[i].calm = (i & 1) == 1;
            around[i].calmAt = around[i].appear * 0.9F;
        }
        Arrays.sort(list, Comparator.comparingDouble(e -> e.scale));
        eyes = list;
    }

    private static float rnd(long[] seed) {
        seed[0] = seed[0] * 16807L % 2147483647L;
        return (seed[0] - 1L) / 2147483646.0F;
    }

    private static void reset() {
        presence = 0.0F;
        habit = 0.0F;
        inner = null;
        innerNext = INNER_FIRST;
        for (Eye e : eyes) e.open = 0.0F;
    }

    // ------------------------------------------------------------------ 描く

    static void render(GuiGraphics g, DeltaTracker delta) {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        long millis = Util.getMillis();
        float dt = lastMillis < 0L ? 0.0F : Math.min(0.1F, (millis - lastMillis) / 1000.0F);
        lastMillis = millis;

        boolean watching = PhyllosensGazeEdge.visible();
        if (!watching && PhyllosensGazeEdge.lostTicks() < 0L) {
            // 一度も見られていない・ワールドを出た
            if (presence > 0.0F || inner != null) reset();
            return;
        }
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float markLevel = PhyllosensGazeEdge.strength(partial);
        boolean marked = watching && PhyllosensGazeEdge.markTick() >= 0L;
        float snapT = marked ? (PhyllosensGazeEdge.now() - PhyllosensGazeEdge.markTick() + partial) / 20.0F : 0.0F;

        clock += dt;
        if (watching) presence = Math.min(1.0F, presence + dt / FULL_SECONDS);
        else presence = Math.max(0.0F, presence - dt / LINGER_SECONDS);
        // 慣れ: 印から見られ続けると進む。見つけ直し(印がまだの見え始め)で戻る。隠れている間はそのまま
        if (marked && PhyllosensGazeEdge.ticksSinceMark() >= HABIT_AFTER_TICKS) habit = Math.min(1.0F, habit + dt / HABIT_FADE_SECONDS);
        else if (watching && !marked) habit = 0.0F;

        int count = Math.round(EYES * Mth.clamp(PhyllosensGazeEdge.eyeScale(), 0.0F, 1.0F));
        if (count != builtFor) build(count);
        boolean pulse = PhyllosensGazeEdge.pulse();
        float bpm = Mth.lerp(habit, BPM_MIN + BPM_RANGE * presence, HABIT_BPM);
        beatPhase = (beatPhase + dt * bpm / 60.0F) % 1.0F;

        if (mc.options.hideGui) return; // F1 では出さない【仮】(時間は進める)

        float vis = Math.max(presence, markLevel * 0.6F);
        boolean anyEye = inner != null;
        for (Eye e : eyes) anyEye |= e.open > 0.01F;
        if (vis <= 0.001F && !anyEye) return;

        ensureShape();
        g.flush();
        float scale = (float) mc.getWindow().getGuiScale();
        float w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight(); // 窓のピクセル
        Matrix4f pose = g.pose().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // ---- 縁の暗さ(⑤ 鼓動: どくん・どくん)
        if (vis > 0.001F) {
            float beat;
            if (pulse) {
                float p1 = (float) Math.exp(-Math.pow((beatPhase - 0.05F) / 0.05F, 2.0));
                float p2 = (float) Math.exp(-Math.pow((beatPhase - 0.25F) / 0.06F, 2.0)) * 0.7F;
                beat = BEAT_BASE + BEAT_GAIN * Mth.lerp(habit, 1.0F, HABIT_BEAT_GAIN) * (p1 + p2);
            } else {
                beat = STEADY;
            }
            float alpha = EDGE_DARK * ease(Mth.clamp(vis, 0.0F, 1.0F)) * beat;
            vignette(pose, w / scale, h / scale, alpha);
        }

        // ---- 目
        if (jitterFrame != (int) (clock * 60.0F)) {
            jitterFrame = (int) (clock * 60.0F);
            for (Eye e : eyes) {
                e.jx = RNG.nextBoolean() ? -1.0F : 1.0F;
                e.jy = RNG.nextBoolean() ? -1.0F : 0.0F;
                e.jp = (RNG.nextFloat() * 2.0F - 1.0F) * 2.0F;
            }
        }
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float unit = Math.min(w, h) / 360.0F * DOT;
        for (Eye e : eyes) {
            // 開く・閉じる(隠れた後、しつこい目は遅れて閉じる。慣れた目はゆっくり閉じる)
            boolean calmed = e.calm && habit > e.calmAt;
            float target;
            if (watching) target = presence >= e.appear * 0.98F ? 1.0F : 0.0F;
            else if (e.stubborn) target = presence > 0.02F || e.open > 0.9F && presence > 0.0F ? 1.0F : 0.0F;
            else target = presence >= e.appear ? 1.0F : 0.0F;
            if (calmed) target = 0.0F;
            float speed = target > e.open ? OPEN_SPEED : calmed && watching ? HABIT_CLOSE_SPEED : e.stubborn ? STUBBORN_CLOSE_SPEED : CLOSE_SPEED;
            e.open += Math.signum(target - e.open) * Math.min(Math.abs(target - e.open), dt * speed);
            if (e.open <= 0.01F) continue;

            // 瞬き。印の瞬間は全部の目が一度だけ一斉に瞬く(① こちらを向く合図)
            float open = ease(e.open);
            float bt = ((clock + e.blinkPhase) % e.blinkEvery) / e.blinkEvery;
            if (bt > 0.97F) open *= Math.abs((bt - 0.985F) / 0.015F);
            if (marked && snapT < MARK_BLINK) open *= Math.abs((snapT - MARK_BLINK / 2.0F) / (MARK_BLINK / 2.0F));

            // 位置と ③ ぴくつき
            float pix = Math.max(1.0F, unit * e.scale);
            float x, y;
            {
                float d = e.t * 2.0F * (w + h), inset = e.depth * Math.min(w, h);
                if (d < w) {
                    x = d;
                    y = inset;
                } else if ((d -= w) < h) {
                    x = w - inset;
                    y = d;
                } else if ((d -= h) < w) {
                    x = w - d;
                    y = h - inset;
                } else {
                    x = inset;
                    y = h - (d - w);
                }
            }
            e.twitchNext -= dt;
            if (e.twitchNext <= 0.0F) {
                e.twitch = TWITCH_SECONDS;
                e.twitchNext = TWITCH_MIN + RNG.nextFloat() * TWITCH_RANGE;
            }
            boolean twitching = e.twitch > 0.0F;
            if (twitching) {
                e.twitch -= dt;
                x += e.jx * pix;
                y += e.jy * pix;
            }

            // 瞳: さまよう → 印でこちら(画面の中心)を向く(②)
            float px = Mth.sin(clock * e.wanderS + e.wanderA) * WANDER_X, py = Mth.sin(clock * e.wanderS * 0.7F + e.wanderB) * WANDER_Y;
            float lookK = marked ? Mth.clamp((snapT - e.snapDelay) / SNAP_TURN, 0.0F, 1.0F) : 0.0F;
            if (lookK > 0.0F) {
                float a = (float) Math.atan2(h / 2.0F - y, w / 2.0F - x) - e.rot;
                float tx = Mth.cos(a) * LOOK_X, ty = Mth.sin(a) * LOOK_Y;
                if (e.flip) tx = -tx;
                px += (tx - px) * lookK;
                py += (ty - py) * lookK;
            }
            if (twitching) px += e.jp;
            float alpha = EYE_ALPHA * Mth.clamp(e.open * 1.4F, 0.0F, 1.0F);
            eye(b, pose, scale, x, y, pix, e.rot, e.flip, open, jsRound(px), jsRound(py), lookK > 0.5F, alpha);
        }

        // ---- ⑥ 視界の内側に一瞬だけ開く目
        if (count > 0 && PhyllosensGazeEdge.innerEyes()) {
            if (presence > INNER_FROM) {
                innerNext -= dt / (1.0F + HABIT_INNER_SLOWER * habit);
                if (inner == null && innerNext <= 0.0F) {
                    float ang = RNG.nextFloat() * Mth.TWO_PI, r = Math.min(w, h) * (INNER_NEAR + RNG.nextFloat() * (INNER_FAR - INNER_NEAR));
                    inner = new float[]{w / 2.0F + Mth.cos(ang) * r * INNER_WIDE, h / 2.0F + Mth.sin(ang) * r, 0.0F, (RNG.nextFloat() * 2.0F - 1.0F) * 0.8F};
                    innerNext = INNER_MIN + RNG.nextFloat() * INNER_RANGE;
                }
            }
        } else {
            inner = null;
        }
        if (inner != null) {
            inner[2] += dt;
            float t = inner[2];
            float o = t < 0.12F ? t / 0.12F : t > INNER_LIFE - 0.1F ? Math.max(0.0F, (INNER_LIFE - t) / 0.1F) : 1.0F;
            float a = (float) Math.atan2(h / 2.0F - inner[1], w / 2.0F - inner[0]) - inner[3];
            eye(b, pose, scale, inner[0], inner[1], Math.max(1.0F, unit * 0.9F), inner[3], false, o,
                    jsRound(Mth.cos(a) * LOOK_X), jsRound(Mth.sin(a) * LOOK_Y), true, EYE_ALPHA * INNER_ALPHA);
            if (t > INNER_LIFE) inner = null;
        }

        MeshData mesh = b.build();
        if (mesh != null) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, ATLAS);
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        perfFrames++;
        perfNanos += System.nanoTime() - t0;
    }

    /** 縁の暗さ: 画面の真ん中からの丸いぼかし(半対角 R の 0.32 倍までは透明 → 0.694R で 0.55 倍 → R で 1.2 倍。角がいちばん暗い)。 */
    private static void vignette(Matrix4f pose, float w, float h, float alpha) {
        float cx = w / 2.0F, cy = h / 2.0F, radius = (float) Math.hypot(w, h) / 2.0F;
        float[] r = {0.32F * radius, (0.32F + 0.68F * 0.55F) * radius, radius};
        float[] a = {0.0F, alpha * 0.55F, Math.min(1.0F, alpha * 1.2F)};
        float[][] c = {{14 / 255F, 10 / 255F, 4 / 255F}, {14 / 255F, 10 / 255F, 4 / 255F}, {6 / 255F, 5 / 255F, 2 / 255F}};
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int ring = 0; ring < 2; ring++) {
            for (int i = 0; i < RING_SEGMENTS; i++) {
                float a0 = Mth.TWO_PI * i / RING_SEGMENTS, a1 = Mth.TWO_PI * (i + 1) / RING_SEGMENTS;
                float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
                b.addVertex(pose, cx + c0 * r[ring], cy + s0 * r[ring], 0.0F).setColor(c[ring][0], c[ring][1], c[ring][2], a[ring]);
                b.addVertex(pose, cx + c0 * r[ring + 1], cy + s0 * r[ring + 1], 0.0F).setColor(c[ring + 1][0], c[ring + 1][1], c[ring + 1][2], a[ring + 1]);
                b.addVertex(pose, cx + c1 * r[ring + 1], cy + s1 * r[ring + 1], 0.0F).setColor(c[ring + 1][0], c[ring + 1][1], c[ring + 1][2], a[ring + 1]);
                b.addVertex(pose, cx + c1 * r[ring], cy + s1 * r[ring], 0.0F).setColor(c[ring][0], c[ring][1], c[ring][2], a[ring]);
            }
        }
        MeshData mesh = b.build();
        if (mesh == null) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
    }

    // 目 1 つを置くときの写し(絵のドット → 画面)
    private static float ex, ey, eCos, eSin, ePix, eOx, eOy, eInv, eFlip;

    /**
     * 目 1 つ(プレビューの drawEye)。(x, y)・pix は窓のピクセル、open 0〜1、瞳の位置 (px, py) は絵の中のドット。
     * まぶたと暗い地は開き具合でドットの行ごとに縦につぶし、瞳はつぶさずに目の内側で切り抜く。
     */
    private static void eye(BufferBuilder b, Matrix4f pose, float guiScale, float x, float y, float pix, float rot, boolean flip,
                            float open, int px, int py, boolean wide, float alpha) {
        if (open <= 0.02F || alpha <= 0.0F) return;
        int hOpen = Math.max(1, jsRound(32.0F * open));
        int top = jsRound(16.0F - hOpen / 2.0F);
        boolean pupil = open > 0.25F;
        int pupilX = 28 + px, pupilY = jsRound(10.0F + py * open);
        ex = x;
        ey = y;
        eCos = Mth.cos(rot);
        eSin = Mth.sin(rot);
        ePix = pix;
        eOx = jsRound(-64.0F * pix / 2.0F);
        eOy = jsRound(-32.0F * pix / 2.0F);
        eInv = 1.0F / guiScale;
        eFlip = flip ? -1.0F : 1.0F;
        float inA = IN_A * alpha;
        int pu = PUPIL_U + (wide ? PUPIL_STEP : 0);

        if (hOpen >= 32) {
            // 開ききった目(ほとんどの時間): 暗い地は瞳の四角のまわり 4 枚、瞳は目の内側に収まる行をまとめて 1 枚(四角の数を 1/5 に)
            int y0 = Mth.clamp(pupilY, 0, 32), y1 = Mth.clamp(pupilY + PUPIL_H, 0, 32);
            int x0 = Mth.clamp(pupilX, 0, 64), x1 = Mth.clamp(pupilX + PUPIL_W, 0, 64);
            insideQuad(b, pose, 0, 0, 64, y0, inA);
            insideQuad(b, pose, 0, y1, 64, 32, inA);
            insideQuad(b, pose, 0, y0, x0, y1, inA);
            insideQuad(b, pose, x1, y0, 64, y1, inA);
            int from = -1;
            for (int row = y0; row <= y1; row++) {
                boolean whole = false;
                if (row < y1 && x0 < x1) {
                    int[] runs = inside[row];
                    for (int i = 0; i < runs.length; i += 2) whole |= runs[i] <= x0 && runs[i + 1] >= x1;
                    if (!whole) {
                        for (int i = 0; i < runs.length; i += 2) {
                            int q0 = Math.max(runs[i], x0), q1 = Math.min(runs[i + 1], x1);
                            if (q0 < q1) pupilQuad(b, pose, q0, row, q1, row + 1, pu - pupilX, PUPIL_V - pupilY, inA);
                        }
                    }
                }
                if (whole && from < 0) from = row;
                if (!whole && from >= 0) {
                    pupilQuad(b, pose, x0, from, x1, row, pu - pupilX, PUPIL_V - pupilY, inA);
                    from = -1;
                }
            }
            quad(b, pose, 0, 0, 64, 32, 0.0F, 0.0F, 64.0F / ATLAS_W, 32.0F / ATLAS_H, 1.0F, 1.0F, 1.0F, alpha);
            return;
        }

        for (int r = 0; r < hOpen; r++) {
            int s = Math.min(31, (int) ((r + 0.5F) * 32.0F / hOpen));
            int row = top + r;
            int[] runs = inside[s];
            int pr = row - pupilY;
            boolean hasPupil = pupil && pr >= 0 && pr < PUPIL_H;
            int p0 = hasPupil ? pupilX + PUPIL_C0[pr] : 0, p1 = hasPupil ? pupilX + PUPIL_C1[pr] : 0;
            for (int i = 0; i < runs.length; i += 2) {
                int a = runs[i], z = runs[i + 1];
                if (!hasPupil || p1 <= a || p0 >= z) {
                    quad(b, pose, a, row, z, row + 1, WHITE_U, WHITE_V, WHITE_U, WHITE_V, IN_R, IN_G, IN_B, inA);
                    continue;
                }
                int q0 = Math.max(a, p0), q1 = Math.min(z, p1);
                if (a < q0) quad(b, pose, a, row, q0, row + 1, WHITE_U, WHITE_V, WHITE_U, WHITE_V, IN_R, IN_G, IN_B, inA);
                quad(b, pose, q0, row, q1, row + 1, (pu + q0 - pupilX) / ATLAS_W, (PUPIL_V + pr) / ATLAS_H,
                        (pu + q1 - pupilX) / ATLAS_W, (PUPIL_V + pr + 1) / ATLAS_H, 1.0F, 1.0F, 1.0F, inA);
                if (q1 < z) quad(b, pose, q1, row, z, row + 1, WHITE_U, WHITE_V, WHITE_U, WHITE_V, IN_R, IN_G, IN_B, inA);
            }
            if (lidRow[s]) {
                quad(b, pose, 0, row, 64, row + 1, 0.0F, s / ATLAS_H, 64.0F / ATLAS_W, (s + 1) / ATLAS_H, 1.0F, 1.0F, 1.0F, alpha);
            }
        }
    }

    /** 目の内側の暗い地(絵の束の「内側」の白い形に暗い色を掛ける)。 */
    private static void insideQuad(BufferBuilder b, Matrix4f pose, int x0, int y0, int x1, int y1, float a) {
        if (x0 >= x1 || y0 >= y1) return;
        quad(b, pose, x0, y0, x1, y1, (INSIDE_U + x0) / ATLAS_W, y0 / ATLAS_H, (INSIDE_U + x1) / ATLAS_W, y1 / ATLAS_H, IN_R, IN_G, IN_B, a);
    }

    /** 瞳の四角の一部(du・dv = 絵のドットから絵の束の画素へのずれ)。 */
    private static void pupilQuad(BufferBuilder b, Matrix4f pose, int x0, int y0, int x1, int y1, int du, int dv, float a) {
        quad(b, pose, x0, y0, x1, y1, (x0 + du) / ATLAS_W, (y0 + dv) / ATLAS_H, (x1 + du) / ATLAS_W, (y1 + dv) / ATLAS_H, 1.0F, 1.0F, 1.0F, a);
    }

    /** 絵のドットの四角 (x0, y0)-(x1, y1) を、今の目の置き方で画面へ。 */
    private static void quad(BufferBuilder b, Matrix4f pose, float x0, float y0, float x1, float y1,
                             float u0, float v0, float u1, float v1, float r, float g, float bl, float a) {
        vertex(b, pose, x0, y0, u0, v0, r, g, bl, a);
        vertex(b, pose, x0, y1, u0, v1, r, g, bl, a);
        vertex(b, pose, x1, y1, u1, v1, r, g, bl, a);
        vertex(b, pose, x1, y0, u1, v0, r, g, bl, a);
    }

    private static void vertex(BufferBuilder b, Matrix4f pose, float dx, float dy, float u, float v, float r, float g, float bl, float a) {
        float lx = (eOx + dx * ePix) * eFlip, ly = eOy + dy * ePix;
        float sx = (ex + eCos * lx - eSin * ly) * eInv, sy = (ey + eSin * lx + eCos * ly) * eInv;
        b.addVertex(pose, sx, sy, 0.0F).setUv(u, v).setColor(r, g, bl, a);
    }

    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** JavaScript の Math.round(半分は上へ)。 */
    private static int jsRound(float v) {
        return Mth.floor(v + 0.5F);
    }
}
