package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereGiantTree;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>巨大樹の樹冠から昇る胞子の柱</b>(2026-11-06 巨大樹の改善案 ①、ユーザー採用。数は仮決め、節 {@code giantTree.sporeColumn})。
 * 汚染は発生源から風に乗って広がる設定で、巨大樹はその大きな発生源 —— それを目に見せる(遠くからの目印・風下の胞子の空気 {@link SporeAir} とつながる)。
 *
 * <h2>濃さ = サーバーの胞子の場と同じ規則</h2>
 * 木ごとの梢の 1 日の放出(育ち具合。発生源の本数の上限から外れた木・ハイヴノードを倒された木は 0。{@link GiantTrees})に、朝 6〜9 時の上乗せ
 * ({@link SporeFieldServer#canopyMorningPerDay}、場と同じ関数)を足し、成木の朝の放出で割る。それに天気の倍率 (depDay ÷ 今の沈着の速さ)^weatherPower
 * ({@link SporeField#deposition}、場と同じ関数。雨・夜・雨上がりは速く沈む = 薄い)を掛ける。止めたワールド・一覧の届かないサーバーでは出さない。
 *
 * <h2>描き方</h2>
 * 梢の上から昇って卓越風の風下へ曲がる 1 本の道筋に、カメラを向く帯を 3 本(芯と左右のゆらぎ)重ねる。縁は透明・真ん中が濃い。濃さは道筋に沿って流れる
 * まだら(煙の塊)で揺らし、終わりへ向けて薄れる。色はふつうの混ぜ方(足し算でない)で、明るさは空の明るさで落とす。
 * 霧を受けないシェーダーなので、距離で霧の色へ寄せ、描画距離の {@code farFade} 倍の手前で消す(描画距離の外でも見える。遠くの描画の切れ目は描画距離の 4 倍)。
 * 森の中の霞({@link MistView})の向こうは霞の濃さで薄める。シェーダーパック使用中・地下では描かない。形({@link GiantTrees#all} の scanned)が
 * まだ無い木(写し取りに数分かかる)は、根元のチャンクが読み込まれていれば地表の高さから仮の幹の先を見積もって描く(一度写した木は遠くへ離れても描く)。
 */
final class SporeColumns {

    private static final int NODES = 20;
    private static RenderType renderType;
    /** 最後に雨が降っていた時刻(ゲーム時刻。雨上がりの判定、場の afterRainTicks と同じ長さ)。 */
    private static long lastRain = Long.MIN_VALUE;
    private static ClientLevel rainLevel;
    /** 開発用: 濃さの上書き(−1 = しない)。撮り比べ用。 */
    static float devStrength = -1.0F;
    static boolean devOff;
    static boolean devAdd;
    private static int drawn;

    private SporeColumns() {
    }

    static int drawn() {
        return drawn;
    }

    /** 毎 tick: 雨上がりの判定のために、雨の最後の時刻を覚える。 */
    static void tick(ClientLevel level) {
        if (level != rainLevel) {
            rainLevel = level;
            lastRain = Long.MIN_VALUE;
            GUESSED.clear();
        }
        if (level.isRaining()) {
            lastRain = level.getGameTime();
        }
        if (DEV_LOG && level.getGameTime() % 100 == 0) {
            StringBuilder sb = new StringBuilder();
            AtmosphereGiantTree.SporeColumnParams p = DesignData.atmosphere().giantTree().sporeColumn();
            for (GiantTrees.Info t : GiantTrees.all()) {
                sb.append(String.format(" [根元 %d %d・軸 %.0f %.0f・高さ %d〜%d・傘 %.0f・形 %s・胞子 %.2f(濃さ %.2f)・地脈 %.2f]", t.originX(), t.originZ(),
                        t.axisX(), t.axisZ(), t.baseY(), t.topY(), t.crownRadius(), t.scanned() ? "有" : "無", t.spore(),
                        strength(level, t.spore(), p), t.ley()));
            }
            OvergrownVegetation.LOGGER.info("GiantTrees 場 {}・柱 {}・光 {}{}", GiantTrees.fieldRunning() ? "動" : "止", drawn, LeyPulses.drawn(), sb);
        }
    }

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 今の時刻・天気で、成木の朝の放出を 1 とした木の濃さ(天気の倍率込み)。spore は {@link GiantTrees.Info#spore()}。 */
    static float strength(ClientLevel level, float spore, AtmosphereGiantTree.SporeColumnParams p) {
        if (devStrength >= 0.0F) {
            return devStrength;
        }
        if (!(spore > 0.0F)) {
            return 0.0F;
        }
        SporeFieldTable table = DesignData.sporeField();
        double mature = Math.max(1.0E-6, table.giantTreePerDay(Double.MAX_VALUE));
        double peak = mature + table.canopyMorningPerDay() * 8;
        double perDay = spore * mature + SporeFieldServer.canopyMorningPerDay(table, level.getDayTime());
        SporeField.Params fp = table.field();
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        boolean afterRain = !raining && lastRain != Long.MIN_VALUE && level.getGameTime() - lastRain < fp.afterRainTicks;
        double dep = SporeField.deposition(fp, SporeField.dayness(Math.floorMod(level.getDayTime(), 24000L)), raining, thundering, afterRain);
        double weather = Math.pow(Math.min(1.0, fp.depDay / Math.max(1.0E-6, dep)), p.weatherPower());
        // 晴れた朝(6〜9 時の真ん中)の倍率で割り、成木の晴れた朝 = 1 にそろえる(朝は場の昼の度合いが 0 で沈着が速いので、そのままだと一番濃い時でも 0.45 だった)
        double morning = Math.pow(Math.min(1.0, fp.depDay / Math.max(1.0E-6, SporeField.deposition(fp, SporeField.dayness(1500.0), false, false, false))),
                p.weatherPower());
        return (float) (perDay / peak * weather / Math.max(1.0E-3, morning));
    }

    static void render(RenderLevelStageEvent event) {
        drawn = 0;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        AtmosphereGiantTree.SporeColumnParams p = DesignData.atmosphere().giantTree().sporeColumn();
        if (devOff || !p.enabled() || level == null || ShaderPacks.active() || ForestPresence.underground() > 0.5F
                || !GiantTrees.fieldRunning() && devStrength < 0.0F) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        double renderBlocks = mc.options.getEffectiveRenderDistance() * 16.0;
        // 雲より上にいる間は描かない(2026-11-18 ユーザー「雲より上にいる時、雲の下が緑に見える機能をなくしたい」: 雲の上から見下ろすと、柱が雲の下の
        // 黄緑の筋に見えた)。雲の底 y 300 から 80 ブロックで消える。地上・雲の下からは今までどおり。
        float aboveClouds = (float) Mth.clamp((eye.y - com.yayuy.overgrownvegetation.sky.CloudField.LOW_BASE) / 80.0, 0.0, 1.0);
        if (aboveClouds >= 1.0F) {
            return;
        }
        double far = renderBlocks * p.farFade();
        float sky = level.getSkyDarken(partialTick);
        float[] fog = RenderSystem.getShaderFogColor();
        double angle = PrevailingWindClient.angle(level);
        double windStrength = PrevailingWindClient.strength(level);
        if (Double.isNaN(angle)) {
            angle = 0.0;
            windStrength = 0.3;
        }
        double wx = Math.cos(angle);
        double wz = Math.sin(angle);
        double seconds = Atmosphere.time(partialTick) / 20.0;

        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = null;
        for (GiantTrees.Info t : GiantTrees.all()) {
            if (!t.scanned()) {
                t = estimated(level, t);
                if (t == null) {
                    continue;
                }
            }
            float s = Math.min(1.5F, strength(level, t.spore(), p)) * (1.0F - aboveClouds);
            if (s <= 0.01F) {
                continue;
            }
            double dx = t.axisX() - eye.x;
            double dz = t.axisZ() - eye.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > far + p.trail()) {
                continue;
            }
            if (c == null) {
                c = buffers.getBuffer(renderType());
            }
            drawColumn(c, t, s, p, eye, wx, wz, windStrength, seconds, far, renderBlocks, sky, fog);
            drawn++;
        }
        if (c != null) {
            buffers.endBatch(renderType());
        }
    }

    private static void drawColumn(VertexConsumer c, GiantTrees.Info t, float s, AtmosphereGiantTree.SporeColumnParams p, Vec3 eye,
                                   double wx, double wz, double windStrength, double seconds, double far, double renderBlocks, float sky, float[] fog) {
        double seed = (t.originX() * 0.37 + t.originZ() * 0.61) % 17.0;
        double rise = Mth.lerp(Math.min(1.0F, s), p.riseMin(), p.riseMax());
        double trail = p.trail() + p.windTrail() * Math.min(windStrength, 3.0);
        double startY = t.topY() + 8.0;
        double width0 = Math.max(6.0, t.crownRadius() * p.widthBase());
        // 横の向き(風に直角)
        double px = -wz;
        double pz = wx;
        boolean haze = MistView.hazeActive();
        double flow = seconds * p.drift() * (0.4 + Math.min(windStrength, 3.0));
        Vec3[] nodes = new Vec3[NODES + 1];
        float[] widths = new float[NODES + 1];
        float[] alphas = new float[NODES + 1];
        float[] mixes = new float[NODES + 1];
        for (int strand = 0; strand < 3; strand++) {
            double lane = strand == 0 ? 0.0 : strand == 1 ? -0.38 : 0.38;
            double phase = seed + strand * 2.3;
            float strandAlpha = strand == 0 ? 1.0F : 0.55F;
            for (int k = 0; k <= NODES; k++) {
                double u = k / (double) NODES;
                // 昇りきってから風下へ: 高さは早く立ち上がり(1 − e^(−6u))、横は u^1.6 で後から伸びる
                double up = rise * (1.0 - Math.exp(-6.0 * u)) / (1.0 - Math.exp(-6.0)) + rise * 0.25 * u;
                double along = trail * Math.pow(u, 1.6);
                double w = Mth.lerp(Math.pow(u, 0.8), width0, p.widthEnd());
                // ゆっくりうねる(道筋の先ほど大きく)
                double wobble = Math.sin(u * 5.0 + seconds * 0.05 + phase) * (4.0 + 26.0 * u)
                        + lane * w;
                double x = t.axisX() + wx * along + px * wobble;
                double z = t.axisZ() + wz * along + pz * wobble;
                double y = startY + up + Math.sin(u * 3.0 + seconds * 0.03 + phase) * 6.0 * u;
                nodes[k] = new Vec3(x, y, z);
                widths[k] = (float) (w * (strand == 0 ? 0.65 : 0.45));
                // 濃さ: 梢で湧き出し(初めの 1 割)・終わりへ薄れる・流れるまだら
                double puff = 0.65 + 0.35 * Math.sin((along + up - flow) / 23.0 + phase * 3.0)
                        * Math.sin((along + up - flow * 0.8) / 57.0 + phase);
                double a = p.alpha() * s * strandAlpha * smooth(u / 0.1) * Math.pow(1.0 - u, 1.0) * puff;
                a = Math.min(0.85, a); // 濃くしても空に貼った板に見えないよう頭打ち
                double d = Math.sqrt((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) + (y - eye.y) * (y - eye.y));
                a *= 1.0 - smooth((d - far * 0.6) / (far * 0.4));
                if (haze) {
                    a *= 1.0F - MistView.fogAmount((float) d); // 森の中の霞の間だけ(外ではバニラの霧の終わりが印で手前に寄っていて、遠くの柱が全部消えた)
                }
                alphas[k] = (float) Math.max(0.0, a);
                mixes[k] = (float) Math.min(0.4, d / (renderBlocks * 5.0));
            }
            ribbon(c, nodes, widths, alphas, mixes, eye, p.color(), sky, fog);
        }
    }

    /** 形の写し取り(数分かかる)を待つ間の仮の形: 根元の列の地表(傘の天辺)から 12 下を幹の先・傘の半径 {@value #GUESS_CROWN}。根元のチャンクが読み込まれていなければ null。 */
    private static GiantTrees.Info estimated(ClientLevel level, GiantTrees.Info t) {
        long key = ((long) t.originX() << 32) | (t.originZ() & 0xFFFFFFFFL);
        int top = GUESSED.getOrDefault(key, Integer.MIN_VALUE);
        if (top == Integer.MIN_VALUE) {
            if (!level.hasChunk(t.originX() >> 4, t.originZ() >> 4)) {
                return null;
            }
            top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, t.originX(), t.originZ()) - 12;
            if (top < level.getMinBuildHeight() + 64) {
                return null; // まだ中身が届いていない(地表が低すぎる)
            }
            GUESSED.put(key, top);
        }
        return new GiantTrees.Info(t.originX(), t.originZ(), t.fromServer(), false, t.axisX(), t.axisZ(), Integer.MIN_VALUE, top, GUESS_CROWN,
                Integer.MIN_VALUE, null, t.spore(), t.ley());
    }

    private static final double GUESS_CROWN = 60.0;
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap GUESSED = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    /** カメラを向く帯(横は 縁 0 → 真ん中 → 縁 0 の 3 点)。 */
    private static void ribbon(VertexConsumer c, Vec3[] nodes, float[] widths, float[] alphas, float[] mixes, Vec3 eye, float[] color, float sky, float[] fog) {
        float[] prevL = null;
        float[] prevM = null;
        float[] prevR = null;
        float prevA = 0.0F;
        float[] prevRgb = null;
        for (int k = 0; k < nodes.length; k++) {
            Vec3 n = nodes[k];
            Vec3 tangent = k + 1 < nodes.length ? nodes[k + 1].subtract(n) : n.subtract(nodes[k - 1]);
            Vec3 toEye = eye.subtract(n);
            Vec3 side = tangent.cross(toEye);
            double len = side.length();
            if (len < 1.0E-6) {
                prevL = null;
                continue;
            }
            side = side.scale(widths[k] / len);
            float[] l = rel(n.subtract(side), eye);
            float[] m = rel(n, eye);
            float[] r = rel(n.add(side), eye);
            float mix = mixes[k];
            float[] rgb = {
                    Mth.lerp(mix, color[0] * (0.25F + 0.75F * sky), fog[0]),
                    Mth.lerp(mix, color[1] * (0.25F + 0.75F * sky), fog[1]),
                    Mth.lerp(mix, color[2] * (0.25F + 0.75F * sky), fog[2])};
            float a = alphas[k];
            if (prevL != null && (a > 0.002F || prevA > 0.002F)) {
                quad(c, prevL, prevM, m, l, prevRgb, rgb, 0.0F, prevA, a, 0.0F);
                quad(c, prevM, prevR, r, m, prevRgb, rgb, prevA, 0.0F, 0.0F, a);
            }
            prevL = l;
            prevM = m;
            prevR = r;
            prevA = a;
            prevRgb = rgb;
        }
    }

    private static void quad(VertexConsumer c, float[] a, float[] b, float[] d, float[] e, float[] rgb0, float[] rgb1,
                             float aa, float ab, float ad, float ae) {
        c.addVertex(a[0], a[1], a[2]).setColor(rgb0[0], rgb0[1], rgb0[2], aa);
        c.addVertex(b[0], b[1], b[2]).setColor(rgb0[0], rgb0[1], rgb0[2], ab);
        c.addVertex(d[0], d[1], d[2]).setColor(rgb1[0], rgb1[1], rgb1[2], ad);
        c.addVertex(e[0], e[1], e[2]).setColor(rgb1[0], rgb1[1], rgb1[2], ae);
    }

    private static float[] rel(Vec3 v, Vec3 eye) {
        return new float[]{(float) (v.x - eye.x), (float) (v.y - eye.y), (float) (v.z - eye.z)};
    }

    private static double smooth(double x) {
        double t = Math.max(0.0, Math.min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }

    /** 色だけの頂点・ふつうの混ぜ方・両面・深さは読むが書かない。 */
    private static RenderType renderType() {
        if (devAdd) {
            return AddLight.renderType();
        }
        if (renderType == null) {
            renderType = RenderType.create(
                    OvergrownVegetation.MODID + "_spore_column",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1 << 16,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }
}
