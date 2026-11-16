package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereScary;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>林冠の影</b>(「美しいが怖い」D。2026-11-16。ユーザー決定: 昼の森で、林冠の上を大きな影が通り過ぎ、木漏れ日が一斉に消えて戻る。
 * 描画だけ・<b>正体は不明のまま</b>・形は横に広い(翼のよう)・過繁茂の森だけ・無音)。
 *
 * <p>理由(設定): 空に何か大きな物がいる気配だけを見せる(見上げても何も見えない)。「見ている先は美しいが、先へ進みたくない」の材料の 1 つ。
 *
 * <h2>作り</h2>
 * 雲の影の地図({@link CloudShadows})へ、走る影を 1 つ足す(胞子の風の前触れ {@code SporeGale.omenAt} と同じ口)。影の通る間だけ、
 * 開けた地面・梢の日なたが陰り、カメラの所の日差し({@link CloudShadows#sunVisibility})が落ちて、木漏れ日・光の筋が一斉に消えて戻る。
 * 地図は 1 画素 8 ブロックなので、16 ブロックより細かい輪郭は出ない(ぼやけた大きな影)。
 * <b>それだけでは森の霞の中で画面が 1% しか暗くならなかった</b>ので(11-17 の計測)、影がカメラの真上にある間は近くの景色をまるごと陰らせる
 * ({@link #onRenderLevel}。{@code dim})。これが見た目の主役で、雲の影の地図が無い日(雲を描かない設定)でも効く。
 * 雨・濃霧・地下・夜は起こさない(影ができない)。
 *
 * <h2>いつ起きるか</h2>
 * 世界の日付と雲の種から決める(通信なし。同じワールドの全員に同じ時刻・同じ向きで起きる): 平均 {@code everyDays} 日に 1 回、その日の
 * 午前〜昼過ぎのどこかで 1 回。そのとき過繁茂の森の中にいる人だけに見える(影はその人の真上あたりを通る)。
 * 数は design の節 {@code scary.shadow}(すべて仮決め)。確かめは {@code /ov fx shadow}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class PassingShadow {

    /** 影が生まれる所(カメラの真上を通る点の手前。ブロック)と、通り過ぎて消す所。 */
    private static final double LEAD = 300.0;
    private static final double END = 420.0;
    /** 影の縁のぼけの幅(ブロック)。秒速 25 なら約 0.7 秒かけて暗くなる(急に切り替わると雲の影でなく画面の点滅に見えた。11-17)。 */
    private static final float EDGE = 18.0F;

    private record Pass(double x0, double z0, double dirX, double dirZ, double born, double lead, float speedPerTick, float span, float chord, float strength) {
        double along(double t) {
            return -lead + (t - born) * speedPerTick;
        }
    }

    private static Pass pass;
    private static long lastDayTime = -1L;
    private static int passes;

    private PassingShadow() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            pass = null;
            lastDayTime = -1L;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        double t = Atmosphere.time(0.0F);
        Pass p = pass;
        if (p != null && (p.along(t) > END || t < p.born())) {
            pass = null;
        }
        long dayTime = level.getDayTime();
        long prev = lastDayTime;
        lastDayTime = dayTime;
        AtmosphereScary.Shadow s = DesignData.atmosphere().scary().shadow();
        if (prev < 0L || dayTime <= prev || dayTime - prev > 200L || pass != null || !s.enabled()) {
            return;
        }
        long day = Math.floorDiv(dayTime, 24000L);
        long h = mix(CloudField.seed() * 0x9E3779B97F4A7C15L + day);
        // その日に起きるか(平均 everyDays 日に 1 回)と、起きる時刻(日の出の後〜昼過ぎ: 2500〜9500)。
        if ((h >>> 11) * 0x1.0p-53 >= 1.0 / Math.max(1.0F, s.everyDays())) {
            return;
        }
        long at = day * 24000L + 2500L + Math.floorMod(h >>> 3, 7000L);
        if (prev < at && dayTime >= at && visibleHere(level)) {
            double angle = Math.floorMod(h >>> 20, 6283L) / 1000.0;
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            start(cam.x, cam.z, angle, LEAD, s);
        }
    }

    /** 影が見える所にいるか: 過繁茂の森の中・空の下・雨と濃霧でない。 */
    private static boolean visibleHere(ClientLevel level) {
        return ForestPresence.overgrown() > 0.3F && ForestPresence.underground() < 0.5F && level.getRainLevel(1.0F) < 0.2F && DenseFog.level() < 0.2F;
    }

    private static void start(double camX, double camZ, double angle, double lead, AtmosphereScary.Shadow s) {
        // 真上から少し横へずらす(毎回同じ通り方にしない。翼の幅の 1/4 まで)。
        double side = (Math.floorMod((long) (angle * 7919.0), 1000L) / 1000.0 - 0.5) * s.span() * 0.5;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        pass = new Pass(camX - dz * side, camZ + dx * side, dx, dz, Atmosphere.time(0.0F), lead, s.speed() / 20.0F, s.span(), s.chord(), s.strength());
        passes++;
    }

    /** 確かめ用({@code /ov fx shadow}・devbridge): 条件を見ずに、今いる所の上を通す。lead = 手前何ブロックから来るか。 */
    public static String force(double lead) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "ワールドの中で";
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        start(cam.x, cam.z, (mc.level.getGameTime() % 6283L) / 1000.0, lead, DesignData.atmosphere().scary().shadow());
        return describe();
    }

    /** 影が出ているか(雲の影の地図を tick ごとに書き直す要るか)。 */
    static boolean active() {
        return pass != null;
    }

    /**
     * 影の濃さ(0〜1)。地面の点 (x, z)・時刻 t({@link Atmosphere#time})。形 = 後ろへ反った翼(幅 span・付け根の前後 chord、先へ細る)+ 細長い胴。
     * 縁はなだらか(地図の画素より細かい形は出ないので、8 ブロックかけてぼかす)。
     */
    static float at(double x, double z, double t) {
        Pass p = pass;
        if (p == null) {
            return 0.0F;
        }
        double rx = x - p.x0();
        double rz = z - p.z0();
        double u = rx * p.dirX() + rz * p.dirZ() - p.along(t);
        double v = Math.abs(-rx * p.dirZ() + rz * p.dirX());
        float half = p.span() * 0.5F;
        if (v > half + 20.0 || Math.abs(u) > p.chord() * 1.6 + 20.0) {
            return 0.0F;
        }
        // 翼: 付け根の前後 chord が、先へ行くほど細り(先で 22%)、後ろへ反る(先で chord の 0.55 倍ぶん後ろ)。
        float k = (float) Math.min(1.0, v / half);
        float c = p.chord() * (1.0F - 0.78F * (float) Math.pow(k, 1.4));
        double center = -0.55 * p.chord() * k * k;
        float wing = soft((float) (c * 0.5 - Math.abs(u - center)), EDGE) * soft((float) (half - v), EDGE);
        // 胴: 前後 chord × 1.5、幅 chord × 0.3 の細長い丸
        double bu = u / (p.chord() * 0.6);
        double bv = v / (p.chord() * 0.1 + 4.0);
        float body = soft((float) (1.0 - Math.sqrt(bu * bu + bv * bv)) * 14.0F, EDGE);
        // 生まれてから 2 秒で濃くなる(地図の端からぷつっと出ないように)。
        float grow = SunLight.smooth(Mth.clamp((float) ((t - p.born()) / 40.0), 0.0F, 1.0F));
        return p.strength() * Math.max(wing, body) * grow;
    }

    // ------------------------------------------------------------------ 影の下にいる間、景色を陰らせる

    private static boolean dimBroken;
    /** このフレームに景色へ掛けた陰りの割合(手にも同じだけ掛ける。{@link #renderHandDim})。 */
    private static float frameDim;

    /**
     * 影がカメラの真上にある間、<b>近くの景色をまるごと陰らせる</b>(11-17。雲の影の地図だけでは、過繁茂の森の霞の中で画面が 1% しか暗く
     * ならなかった: 霞の明るさは雲の影で変わらないので)。描き終えた画面へ、黒を {@code dim} × 影の濃さ の割合で重ねる。空と遠く(160 ブロックより先)は
     * 陰らせない(影の外は日が差したまま): 四角をその距離の奥行きに置き、「手前に物がある画素だけ」に描く。手は、手を描いた後に同じ割合で陰らせる
     * ({@link #renderHandDim}。景色だけ陰らせると、明るい手が浮いた)。画面の表示(ホットバーなど)には掛からない。
     * 影の濃さは影の形そのもの({@link #at})から出すので、雲の影の地図が無い日(雲を描かない設定)でも陰る。地下では掛けない。
     */
    @SubscribeEvent
    public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) {
            frameDim = 0.0F;
            return;
        }
        Pass p = pass;
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL || p == null || dimBroken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Atmosphere.enabled(level)) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vec3 sun = SunLight.sunDirection(level, partial);
        // 昼だけ(太陽が低いと影は遠くへ伸びて、ここには落ちない)・雨の日は弱く・地下は無し。
        float day = SunLight.smooth(Mth.clamp(((float) sun.y - 0.12F) / 0.19F, 0.0F, 1.0F)) * (1.0F - level.getRainLevel(partial));
        float shade = at(cam.x, cam.z, Atmosphere.time(partial)) * day * (1.0F - ForestPresence.underground());
        float alpha = shade * DesignData.atmosphere().scary().shadow().dim();
        if (alpha <= 0.004F) {
            return;
        }
        // 160 ブロック先の奥行き(−1〜1 の座標)。投影の行列から出す。
        org.joml.Vector4f clip = new org.joml.Vector4f(0.0F, 0.0F, -160.0F, 1.0F).mul(event.getProjectionMatrix());
        if (dimQuad(Mth.clamp(clip.z() / clip.w(), -1.0F, 0.99999F), alpha)) {
            frameDim = alpha;
        }
    }

    /**
     * 手を描いた後に呼ぶ(呼び手は {@code GameRendererEyeAdaptMixin}。renderLevel の直後・GUI の前)。このとき奥行きの器には手だけが残っている
     * (手を描く前に消されるので)ので、「何か描かれた画素だけ」= 手にだけ、景色と同じ割合の陰りを重ねる。
     */
    public static void renderHandDim() {
        float alpha = frameDim;
        frameDim = 0.0F;
        if (alpha > 0.004F && !dimBroken) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
            dimQuad(0.99999F, alpha);
        }
    }

    /** 画面全体の黒い四角を、奥行き z(−1〜1)に置いて、それより手前に物がある画素だけに重ねる。描けたら true。 */
    private static boolean dimQuad(float z, float alpha) {
        net.minecraft.client.renderer.ShaderInstance shader = net.minecraft.client.renderer.GameRenderer.getPositionColorShader();
        if (shader == null) {
            return false;
        }
        try {
            com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance()
                    .begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
            b.addVertex(-1.0F, -1.0F, z).setColor(0.0F, 0.0F, 0.0F, alpha);
            b.addVertex(1.0F, -1.0F, z).setColor(0.0F, 0.0F, 0.0F, alpha);
            b.addVertex(1.0F, 1.0F, z).setColor(0.0F, 0.0F, 0.0F, alpha);
            b.addVertex(-1.0F, 1.0F, z).setColor(0.0F, 0.0F, 0.0F, alpha);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_GREATER);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            org.joml.Matrix4f identity = new org.joml.Matrix4f();
            shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, identity, identity, Minecraft.getInstance().getWindow());
            shader.apply();
            com.mojang.blaze3d.vertex.BufferUploader.draw(b.buildOrThrow());
            shader.clear();
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
            return true;
        } catch (RuntimeException e) {
            dimBroken = true;
            OvergrownVegetation.LOGGER.error("林冠の影の陰りで例外が出たので、このセッションでは掛けない", e);
            return false;
        }
    }

    /** 縁からの内側の距離 d(ブロック)を、幅 w でなだらかに 0 → 1。 */
    private static float soft(float d, float w) {
        return SunLight.smooth(Mth.clamp(d / w + 0.5F, 0.0F, 1.0F));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        Pass p = pass;
        return String.format("林冠の影 %s(これまで %d 回・カメラの所の日差し %.2f)", p == null ? "なし"
                : String.format("通過中 真ん中まで %.0f ブロック", -p.along(Atmosphere.time(0.0F))), passes, CloudShadows.sunVisibility());
    }
}
