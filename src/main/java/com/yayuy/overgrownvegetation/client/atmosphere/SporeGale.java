package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEvents;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Random;

/**
 * <b>G4 胞子の風</b>(過繁茂の密林の環境ギミック。2026-11-01 ユーザー選択、2026-11-08「雲と一緒に」で雲の前触れと光線を足した)。
 *
 * <p>設定: 胞子は世界に 1 つの卓越風で運ばれる。それを目で見える「出来事」にする。前触れ(雲の影が走る)は、プレイヤーが空を見て風を読めるように。
 *
 * <ul>
 *     <li><b>どの突風が胞子の風か</b>: 突風の前線({@link GustField})のうち {@code gale.chance} の割合。カメラが過繁茂の近く
 *     ({@link ForestPresence#overgrown})で、本当の雨が降っていない間だけ。胞子の風は少し強く吹く({@code strengthBonus})。
 *     葉と蔓は前線の位置ごとの強さで揺れる(シェーダーへ渡す突風 {@link GustField#fillGusts} のまま)ので、風上から順に波のように揺れる。
 *     風の音({@code ForestWindSound})も前線の強さで強まる(新しい音は入れない)。</li>
 *     <li><b>黄緑の胞子の波</b>: 前線と一緒に進む細かい点と、ぼけた胞子の雲。前線のまわり(前は短く後ろは長い)に濃く、カメラの横 ±{@code boxHalf}・
 *     上下 {@code heightBelow}〜{@code heightAbove} の世界に固定した繰り返しの箱に置く(カメラが動いても粒はその場)。粒は前線より少し遅れていく
 *     ({@code lag})ので、波の中でも流れて見える。描くのは CPU({@link LifeSprites}。数百の点)。</li>
 *     <li><b>光線の中で光る</b>: 薄明光線({@link CrepuscularRays#glowAt})の帯の中の粒だけ、加算の光で光らせる。森の中は薄明光線が 1 割に弱まる
 *     (林冠の光の筋の中の胞子は、もとから光る胞子 {@code ShaftMotes} が突風で一斉に流れる)。</li>
 *     <li><b>霧が一瞬濃くなる</b>: 前線がカメラを通る間、霞の濃さ({@link #fogK})を足す(読み手は {@link MistView})。</li>
 *     <li><b>前触れ</b>: 胞子の風が生まれると、前線が来る {@code omenLeadSeconds} 秒前にカメラを通るように、風上から雲の影の帯が {@code omenSpeed} で走ってくる
 *     ({@link #omenAt})。雲の影の地図({@link CloudShadows})に足すだけ —— 雲の位置はサーバーと揃えるので動かさない。雲の影の地図が無いとき
 *     (雲の無い日・夜・雲を描かない設定)は前触れも出ない。</li>
 * </ul>
 * 雰囲気の演出がオフなら呼ばれない({@link Atmosphere})。重さの段で粒の数を減らし、「低」では光線の中で光らせない。数値は {@code atmosphere.json gale}(すべて仮決め)。
 */
public final class SporeGale {

    private static final Random RANDOM = new Random();

    /** 粒の置き場(0〜1 の乱数。along = 前線からの前後、side = 横、h = 高さ、ph = 揺れの位相)。 */
    private static float[] moteAlong = new float[0];
    private static float[] moteSide = new float[0];
    private static float[] moteH = new float[0];
    private static float[] motePh = new float[0];
    private static float[] puffAlong = new float[0];
    private static float[] puffSide = new float[0];
    private static float[] puffH = new float[0];
    private static float[] puffPh = new float[0];

    /** 今の胞子の風の前線(無ければ null)。 */
    private static GustField.Gust current;
    /** 前触れの雲の影の帯。 */
    private static Omen omen;
    private static float fog;
    /** カメラの所の前線の度合い(0〜1。開発用のログ)。 */
    private static float here;
    /** 直前のフレームに描いた点と胞子の雲の数(開発用のログ)。 */
    private static int drawnMotes;
    private static int drawnPuffs;

    /** 前触れの影の帯(地面の上の位置で決める)。 */
    private record Omen(double camX, double camZ, double dirX, double dirZ, double startAlong, double speedPerTick, double born, double halfWidth,
                        float strength, float width) {
        /** 時刻 t の帯の真ん中(born のカメラから向きに沿った距離)。 */
        double along(double t) {
            return this.startAlong + this.speedPerTick * (t - this.born);
        }
    }

    private SporeGale() {
    }

    private static AtmosphereEvents.GaleParams params() {
        return DesignData.atmosphere().gale();
    }

    static void clear() {
        current = null;
        omen = null;
        fog = 0.0F;
        here = 0.0F;
        GustField.INSTANCE.setSporeGale(0.0, 0.0);
    }

    /** 毎 tick({@link Atmosphere} から。突風の場を進めた後)。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        AtmosphereEvents.GaleParams p = params();
        boolean allowed = p.enabled() && ForestPresence.overgrown() > 0.15F && CloudSky.rawRain(level, 1.0F) < 0.2F;
        GustField.INSTANCE.setSporeGale(allowed ? p.chance() : 0.0, p.strengthBonus());
        GustField.Gust newest = null;
        for (GustField.Gust g : GustField.INSTANCE.gusts()) {
            if (g.spore) {
                newest = g;
            }
        }
        double t = GustField.INSTANCE.now();
        if (newest != current) {
            current = newest;
            if (newest != null && t - newest.born < 40.0) {
                startOmen(newest, player.getX(), player.getZ(), p);
            }
        }
        if (omen != null && omen.along(t) > 260.0) {
            omen = null;
        }
        float target = 0.0F;
        here = 0.0F;
        if (current != null && p.enabled()) {
            here = (float) Math.min(1.0, current.at(player.getX(), player.getZ(), t) / Math.max(0.05, current.strength));
            target = here * p.fogDensity() * (1.0F - ForestPresence.underground());
        }
        // 来るときは速く(1 秒)、去るときはゆっくり(4 秒)。
        fog += (target - fog) * (target > fog ? 0.05F : 0.0125F);
    }

    private static void startOmen(GustField.Gust g, double camX, double camZ, AtmosphereEvents.GaleParams p) {
        double t = GustField.INSTANCE.now();
        // 前線がカメラに着くまでの tick(前線の真ん中から見たカメラの前後)。
        double ahead = (camX - g.centerX(t)) * g.dirX + (camZ - g.centerZ(t)) * g.dirZ;
        double arrive = Math.max(0.0, ahead / Math.max(0.01, g.speed));
        double speed = p.omenSpeed() / 20.0;
        // 帯の真ん中がカメラ(along 0)を通るのは arrive − lead の時。
        double pass = Math.max(20.0, arrive - p.omenLeadSeconds() * 20.0);
        omen = new Omen(camX, camZ, g.dirX, g.dirZ, -speed * pass, speed, t, g.halfWidth * 2.2, p.omenStrength(), p.omenWidth());
    }

    /** 霞に足す 1 ブロックあたりの濃さ(読み手は {@link MistView#renderFog})。 */
    static float fogK() {
        return fog;
    }

    /**
     * 前触れの雲の影の濃さ(0〜1)。地面の点 (x, z)・時刻 t({@link Atmosphere#time})。帯の前後は {@code omenWidth} のなだらかな山で、横は前線の倍ほどの幅に
     * 斑(ちぎれ雲の影)を付ける。斑は帯と一緒に流れる。読み手は {@link CloudShadows}(影の地図に足す)。
     */
    static float omenAt(double x, double z, double t) {
        Omen o = omen;
        if (o == null) {
            return 0.0F;
        }
        double rx = x - o.camX();
        double rz = z - o.camZ();
        double along = rx * o.dirX() + rz * o.dirZ() - o.along(t);
        double side = -rx * o.dirZ() + rz * o.dirX();
        double half = o.width() * 0.5;
        double a = along / half;
        if (a * a > 9.0 || Math.abs(side) > o.halfWidth() * 1.3) {
            return 0.0F;
        }
        float band = (float) Math.exp(-a * a);
        float edge = 1.0F - SunLight.smooth(Mth.clamp((float) ((Math.abs(side) / o.halfWidth() - 0.75) / 0.55), 0.0F, 1.0F));
        float patch = SunLight.smooth(Mth.clamp((float) (valueNoise(along / 26.0, side / 30.0) - 0.25) / 0.45F, 0.0F, 1.0F));
        // 生まれてから 2 秒で濃くなる(地図の端からぷつっと出ないように)。
        float grow = SunLight.smooth(Mth.clamp((float) ((t - o.born()) / 40.0), 0.0F, 1.0F));
        return o.strength() * band * edge * (0.55F + 0.45F * patch) * grow;
    }

    /** 前触れの帯が出ているか(影の地図を作り直す要るか)。 */
    static boolean omenActive() {
        return omen != null;
    }

    /** なめらかな値の雑音(0〜1)。 */
    private static float valueNoise(double u, double v) {
        int iu = Mth.floor(u);
        int iv = Mth.floor(v);
        float fu = (float) (u - iu);
        float fv = (float) (v - iv);
        fu = fu * fu * (3.0F - 2.0F * fu);
        fv = fv * fv * (3.0F - 2.0F * fv);
        return Mth.lerp(fv, Mth.lerp(fu, hash(iu, iv), hash(iu + 1, iv)), Mth.lerp(fu, hash(iu, iv + 1), hash(iu + 1, iv + 1)));
    }

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static void ensure(int motes, int puffs) {
        if (moteAlong.length != motes) {
            moteAlong = fill(motes);
            moteSide = fill(motes);
            moteH = fill(motes);
            motePh = fill(motes);
        }
        if (puffAlong.length != puffs) {
            puffAlong = fill(puffs);
            puffSide = fill(puffs);
            puffH = fill(puffs);
            puffPh = fill(puffs);
        }
    }

    private static float[] fill(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = RANDOM.nextFloat();
        }
        return a;
    }

    /** 毎フレームの粒の位置の計算に使う、前線の写し。 */
    private static final class Frame {
        double cx;
        double cz;
        double dx;
        double dz;
        double camSide;
        double camY;
        double time;
        double born;
        float grown;
        float len;
        float box;
        float below;
        float above;
        float lag;
        /** カメラが林冠の下にいる度合い(0 = 梢より上 〜 1 = 梢の 8 ブロック下)。 */
        float inside;
        double halfWidth;
        double lenAhead;
        double lenBehind;
    }

    private static final Frame F = new Frame();
    private static final double[] POS = new double[4];

    private static boolean frame(ClientLevel level, Vec3 cam, float partialTick, AtmosphereEvents.GaleParams p) {
        GustField.Gust g = current;
        if (g == null || !p.enabled()) {
            return false;
        }
        double t = Atmosphere.time(partialTick);
        F.time = t;
        F.cx = g.centerX(t);
        F.cz = g.centerZ(t);
        F.dx = g.dirX;
        F.dz = g.dirZ;
        F.camSide = -(cam.x - F.cx) * g.dirZ + (cam.z - F.cz) * g.dirX;
        // 箱の高さの基準: 林冠の下ではカメラ、林冠より上では梢の少し上(上から見たとき波が森の上をなでるように)。
        // 梢の高さ = カメラのまわり 3 × 3 点(12 ブロックおき)の一番高い所(真下が林冠の穴でも森の上をなでるように)。
        int top = Integer.MIN_VALUE;
        for (int gx = -1; gx <= 1; gx++) {
            for (int gz = -1; gz <= 1; gz++) {
                top = Math.max(top, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, Mth.floor(cam.x) + gx * 12, Mth.floor(cam.z) + gz * 12));
            }
        }
        F.camY = Math.min(cam.y, top + p.heightBelow());
        F.inside = SunLight.smooth(Mth.clamp((float) ((top - cam.y - 2.0) / 6.0), 0.0F, 1.0F));
        F.born = g.born;
        F.grown = (float) (g.grownStrength(t) / Math.max(0.05, g.strength));
        F.box = p.boxHalf();
        F.below = p.heightBelow();
        F.above = p.heightAbove();
        F.lag = p.lag() / 20.0F;
        F.halfWidth = g.halfWidth;
        F.lenAhead = g.lenAhead;
        F.lenBehind = g.lenBehind;
        // 粒の前後の広がり(前線の前の 2.5 倍・後ろの 2.5 倍)。
        F.len = (float) ((g.lenAhead + g.lenBehind) * 2.5);
        return F.grown > 0.01F;
    }

    /** i 番目の粒の位置(POS[0..2])と濃さ(POS[3])。 */
    private static void place(float along01, float side01, float h01, float ph, boolean puff) {
        double front = F.lenAhead * 2.5;
        // 前線に対して遅れていく(後ろの端まで行ったら前の端へ戻る)。
        double along = front - Math.floorMod((long) Math.floor((1.0 - along01) * F.len * 1000.0 + F.lag * (F.time - F.born) * 1000.0),
                (long) Math.max(1, Math.round(F.len * 1000.0))) / 1000.0;
        double box = F.box * 2.0;
        double side = F.camSide + (Math.floorMod((long) Math.floor((side01 * box - F.camSide) * 100.0), (long) Math.round(box * 100.0)) / 100.0 - F.box);
        double hRange = F.below + F.above;
        double y0 = F.camY - F.below;
        double y = y0 + Math.floorMod((long) Math.floor((h01 * hRange - y0) * 100.0), (long) Math.max(1, Math.round(hRange * 100.0))) / 100.0;
        // 渦(粒ごとの位相でゆっくり回る。胞子の雲は大きくうねる)。
        double w = F.time * 0.05 + ph * 6.283;
        double swirl = puff ? 1.6 : 0.7;
        along += Math.sin(w) * swirl;
        side += Math.cos(w * 0.8) * swirl;
        y += Math.sin(w * 1.3 + ph * 3.0) * swirl * 0.6 + 0.6 * Math.max(0.0, -along / Math.max(1.0, F.lenBehind)); // 前線の後ろで少し巻き上がる
        // 濃さ: 前線の形(前は短く後ろは長い、粒は広め)× 横の縁 × 上下の縁 × 生まれてからの強まり。
        double len = along > 0.0 ? F.lenAhead * 1.6 : F.lenBehind * 1.6;
        double a = along / len;
        double s = side / (F.halfWidth * 1.2);
        float profile = (float) Math.exp(-(a * a + s * s));
        float sideEdge = 1.0F - SunLight.smooth(Mth.clamp((float) ((Math.abs(side - F.camSide) / F.box - 0.7) / 0.3), 0.0F, 1.0F));
        float yt = (float) ((y - y0) / hRange);
        float yEdge = SunLight.smooth(Mth.clamp(yt / 0.12F, 0.0F, 1.0F)) * (1.0F - SunLight.smooth(Mth.clamp((yt - 0.85F) / 0.15F, 0.0F, 1.0F)));
        POS[0] = F.cx + F.dx * along - F.dz * side;
        POS[1] = y;
        POS[2] = F.cz + F.dz * along + F.dx * side;
        POS[3] = profile * sideEdge * yEdge * F.grown;
    }

    /** 粒の明るさ(昼の強さ・林冠の陰。夜は暗い)。 */
    private static float light(ClientLevel level, float partialTick) {
        float day = SunLight.daylight(level, SunLight.sunDirection(level, partialTick), partialTick);
        float moon = SunLight.moonLight(level, partialTick, SunLight.MOON_FLOOR);
        return Math.max(0.12F, Math.max(day, 0.35F * moon)) * (1.0F - 0.25F * ForestPresence.openCover());
    }

    /** ふつうの重ね方の回({@link Atmosphere#renderLife} の {@link LifeSprites} の中)。胞子の雲と点。 */
    static void render(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        AtmosphereEvents.GaleParams p = params();
        drawnMotes = 0;
        drawnPuffs = 0;
        if (!frame(level, cam, partialTick, p)) {
            return;
        }
        int tier = Math.min(2, VisualQuality.atmosphereTier());
        ensure(p.counts()[tier], p.puffCounts()[tier]);
        float light = light(level, partialTick);
        float under = 1.0F - ForestPresence.underground();
        float[] c = p.color();
        float r = c[0] * light;
        float g = c[1] * light;
        float b = c[2] * light;
        // 11-08 ユーザー決定 Q1: 森の中(林冠の下)では胞子の雲を半分ほどに(数 × insidePuffCount・濃さ × insidePuffAlpha)。
        // 前線の縁が見えて景色が残るように。林冠の上から見た波は変えない。
        int puffs = Math.round(puffAlong.length * Mth.lerp(F.inside, 1.0F, p.insidePuffCount()));
        float puffK = Mth.lerp(F.inside, 1.0F, p.insidePuffAlpha());
        float nearFrom = Mth.lerp(F.inside, 3.0F, 6.0F);
        for (int i = 0; i < puffs; i++) {
            place(puffAlong[i], puffSide[i], puffH[i], puffPh[i], true);
            // カメラのすぐ前の胞子の雲は薄く(画面が一色に塗りつぶされないように・重ね塗りを減らす)。
            double ex = POS[0] - cam.x;
            double ey = POS[1] - cam.y;
            double ez = POS[2] - cam.z;
            float near = SunLight.smooth(Mth.clamp((float) ((Math.sqrt(ex * ex + ey * ey + ez * ez) - nearFrom) / 10.0), 0.0F, 1.0F));
            float a = (float) POS[3] * p.puffAlpha() * puffK * under * near;
            if (a > 0.003F) {
                float radius = p.puffSize() * (0.7F + 0.6F * puffPh[i]);
                LifeSprites.blob(POS[0], POS[1], POS[2], radius, 0.7F, left, up, r, g, b, a);
                drawnPuffs++;
            }
        }
        for (int i = 0; i < moteAlong.length; i++) {
            place(moteAlong[i], moteSide[i], moteH[i], motePh[i], false);
            float a = (float) POS[3] * p.alpha() * under;
            if (a > 0.01F) {
                float shade = 0.8F + 0.4F * motePh[i];
                LifeSprites.dot(POS[0], POS[1], POS[2], p.size() * (0.7F + 0.6F * moteH[i]), left, up, r * shade, g * shade, b * shade, a);
                drawnMotes++;
            }
        }
    }

    /** 足し算の光の回(薄明光線の後)。光線の帯の中の粒だけ光らせる。重さの段「低」では描かない。 */
    static void renderGlow(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        AtmosphereEvents.GaleParams p = params();
        if (p.glow() <= 0.0F || VisualQuality.atmosphereTier() >= 2 || !CrepuscularRays.lit() || !frame(level, cam, partialTick, p)) {
            return;
        }
        ensure(p.counts()[Math.min(2, VisualQuality.atmosphereTier())], p.puffCounts()[Math.min(2, VisualQuality.atmosphereTier())]);
        float[] c = p.glowColor();
        float k = p.glow() * (1.0F - ForestPresence.underground());
        for (int i = 0; i < moteAlong.length; i++) {
            place(moteAlong[i], moteSide[i], moteH[i], motePh[i], false);
            if (POS[3] < 0.02) {
                continue;
            }
            float in = CrepuscularRays.glowAt(POS[0], POS[1], POS[2]);
            float a = (float) POS[3] * in * k;
            if (a > 0.01F) {
                float twinkle = 0.6F + 0.4F * Mth.sin((float) (F.time * 0.21 + motePh[i] * 40.0));
                LifeSprites.dot(POS[0], POS[1], POS[2], p.size() * 1.6F, left, up, c[0], c[1], c[2], Math.min(1.0F, a * twinkle));
            }
        }
        for (int i = 0; i < puffAlong.length; i++) {
            place(puffAlong[i], puffSide[i], puffH[i], puffPh[i], true);
            float in = CrepuscularRays.glowAt(POS[0], POS[1], POS[2]);
            float a = (float) POS[3] * in * k * p.puffAlpha() * 1.5F;
            if (a > 0.003F) {
                LifeSprites.blob(POS[0], POS[1], POS[2], p.puffSize() * (0.7F + 0.6F * puffPh[i]), 0.7F, left, up, c[0], c[1], c[2], a);
            }
        }
    }

    /** 今、胞子の風の前線が吹いているか。 */
    static boolean active() {
        return current != null;
    }

    /** 開発用・{@code /ov fx gale}: カメラの風上に胞子の風を 1 つ起こす(約 10 秒後に届く)。 */
    public static void force(double camX, double camZ) {
        GustField.INSTANCE.forceGust(camX, camZ, true);
    }

    public static String describe() {
        GustField.Gust g = current;
        return String.format("胞子の風 %s カメラの所 %.2f 霧 +%.4f 描いた点 %d 雲 %d 前触れ %s", g != null ? "吹いている" : "なし", here, fog, drawnMotes, drawnPuffs, omen != null ? "走っている" : "なし");
    }
}
