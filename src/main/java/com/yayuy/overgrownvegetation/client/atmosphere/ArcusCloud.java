package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>アーク雲と突風</b>(雷雨の強化の残り。2026-11-16。ユーザー決定: 雷雨の前に棚のような低い雲が押し寄せ、冷たい突風 → 大雨)。
 *
 * <p>現実のアーク雲(棚雲)は、雷雨の冷たい下降気流の先端にできる、低く横に長い楔形の雲。前の縁はなめらかで段になり、下面は暗く荒れている。
 * 頭上を越えると同時に突風が来て、そのあと大雨になる。ここでは {@code preStorm}(雷が始まる前の 0 → 1、約 1200 tick)の間に:
 * <ul>
 *     <li>雲の来る側(雲を流す上空の風の風上)の {@code arcusFrom} ブロック先から、棚の雲が同じ速さで近づき、{@code preStorm} が
 *     {@code arcusOverhead} のとき前の縁が頭上を越える。越えた後も同じ速さで風下へ進み、雷雨が始まって遠ざかると消える。</li>
 *     <li>形: 横に {@code arcusLength}・奥行き {@code arcusDepth}。前の縁は丸い鼻先(底 {@code arcusBase} から {@code arcusThick} の厚み)で、
 *     後ろへ向かって上の面が雲の底(y 300)まで段々に上がり、下面は暗くでこぼこ。雲と同じ箱の見た目(升 24 × 4 × 12)。</li>
 *     <li>前の縁が頭上を越える少し前に、強い突風の前線を 1 つ吹かせる({@link GustField#forceGust}。葉・落ち葉・風の音が一緒に動く)。</li>
 * </ul>
 * 雲の網({@code VoxelClouds})には手を入れず、自前の形 1 つ(作るのは 1 回。描画は 1 回の呼び出し)。不透明で奥行きを書く(地形・近くの雲と正しく隠し合う)。
 * 描画距離より遠いので、頂点シェーダーで遠い頂点を視線に沿って手前へ寄せる({@code arcus.vsh})。出入りは網目の抜きで(透明度は使わない)。
 * 濃霧・森の霞の中では霧の色へ沈める。地下・シェーダーパック使用中は描かない。数は節 {@code stormExtras} の arcus*(仮決め)。
 */
final class ArcusCloud {

    private ArcusCloud() {
    }

    private static final int CELL_X = 24;
    private static final int CELL_Z = 12;
    private static final int CELL_Y = 4;

    private static VertexBuffer vbo;
    private static float builtLength, builtDepth, builtBase, builtThick;
    /** 前の縁からカメラまでの距離(風の向きに測る。正 = まだ風上にある・負 = 頭上を越えた)。NaN = 出ていない。 */
    private static double lead = Double.NaN;
    private static double prevLead = Double.NaN;
    private static float present;
    private static float lastPre;
    private static boolean gusted;
    private static float dirX = 1.0F, dirZ;
    /** 確かめ用: 前の縁の位置を固定する(NaN = 自動)。 */
    static double devLead = Double.NaN;

    static void clear() {
        lead = Double.NaN;
        prevLead = Double.NaN;
        present = 0.0F;
        gusted = false;
        lastPre = 0.0F;
    }

    /** この雷雨ではもう棚を通した(か、近づく所を見逃した)。天気が落ち着くまで、新しい棚を生まない。 */
    private static boolean passing;

    /** 毎 tick。{@code pre} = 雷雨の前触れ 0〜1(寄せた値)、{@code thunder} = 雷雨の強さ。 */
    static void tick(ClientLevel level, LocalPlayer player, float pre, float thunder) {
        WeatherLookEye.Params p = WeatherLook.storm();
        prevLead = lead;
        long gameTime = level.getGameTime();
        double vx = CloudField.offsetX(gameTime + 20.0) - CloudField.offsetX(gameTime);
        double vz = CloudField.offsetZ(gameTime + 20.0) - CloudField.offsetZ(gameTime);
        double vl = Math.hypot(vx, vz);
        if (Double.isNaN(lead) && vl > 1.0e-6) {
            // 向きは出始めに決めて、通り過ぎるまで変えない。
            dirX = (float) (vx / vl);
            dirZ = (float) (vz / vl);
        }
        float from = p.f("arcusFrom");
        float overhead = p.f("arcusOverhead");
        // 1 tick に進む距離: 前触れの長さ(約 1200 tick)で arcusFrom を進む速さ。
        double speed = from / (1200.0 * overhead);
        if (!Double.isNaN(devLead)) {
            lead = devLead;
            present = 1.0F;
        } else {
            // 前触れが上がっている間か: 届いた生の値が、寄せた値を下回っていない(下がり始めると生の値が先に落ちる)。
            float raw = WeatherClient.received() ? WeatherClient.preStorm() : 0.0F;
            boolean rising = pre > 0.02F && raw >= pre - 0.01F && thunder < 0.5F;
            double target = from * (1.0 - pre / overhead);
            if (Double.isNaN(lead)) {
                // 生まれるのは、近づく所から見せられるときだけ(前の縁がまだ風上に十分ある)。雷雨の途中で入った・/weather thunder で
                // いきなり始まった・雷雨が終わって前触れが引いていく間は、生まない(頭上に半分透けた棚が出たり、終わりに置き土産が出たりした)。
                if (rising && !passing && target > from * 0.4) {
                    lead = target;
                    present = 0.0F;
                }
            } else if (rising && !passing) {
                // 近づいてくる間: 位置は前触れの値から(5 秒ごとの段は WeatherLook が寄せている)。後ろへは戻さない。
                lead = Math.min(lead, lead + (target - lead) * 0.2);
                present = Math.min(1.0F, present + 0.01F);
            } else {
                // 雷雨が始まった・前触れが引いた: 同じ速さで風下へ進み続け、遠ざかったら(前触れが取り消されたらその場で)消える。
                passing = true;
                lead -= speed;
                if (lead < -(p.f("arcusDepth") + 500.0F) || pre <= 0.02F && thunder <= 0.02F) {
                    present -= 0.01F;
                    if (present <= 0.0F) {
                        clear();
                    }
                } else {
                    present = Math.min(1.0F, present + 0.01F);
                }
            }
            if (Double.isNaN(lead) && pre <= 0.02F && thunder <= 0.02F) {
                // 天気が落ち着いたら、次の雷雨でまた出せるように。
                passing = false;
            } else if (Double.isNaN(lead) && (thunder >= 0.5F || !rising)) {
                // 棚が無いまま雷雨に入った・前触れが引いている: この雷雨では出さない。
                passing = true;
            }
        }
        lastPre = pre;
        if (!Double.isNaN(lead) && !gusted && lead < 140.0 && p.f("arcusGust") > 0.5F && Double.isNaN(devLead)) {
            gusted = true;
            GustField.INSTANCE.forceGust(player.getX(), player.getZ());
        }
    }

    /** 形を作る(値が変わったときだけ作り直す)。 */
    private static void build(WeatherLookEye.Params p) {
        float length = p.f("arcusLength"), depth = p.f("arcusDepth"), base = p.f("arcusBase"), thick = p.f("arcusThick");
        if (vbo != null && length == builtLength && depth == builtDepth && base == builtBase && thick == builtThick) {
            return;
        }
        builtLength = length;
        builtDepth = depth;
        builtBase = base;
        builtThick = thick;
        int nx = Math.max(2, Math.round(length / CELL_X));
        int nz = Math.max(2, Math.round(depth / CELL_Z));
        int[] bottom = new int[nx * nz];
        int[] top = new int[nx * nz];
        float joinY = CloudField.LOW_BASE + 8.0F;
        for (int i = 0; i < nx; i++) {
            // 両端へ向かって細くなる(端で消える)。途中にもゆるい強弱。
            float u = (i + 0.5F) / nx;
            float ends = SunLight.smooth(Mth.clamp(Math.min(u, 1.0F - u) / 0.16F, 0.0F, 1.0F));
            float swell = 0.85F + 0.15F * Mth.sin(u * 23.0F) * Mth.sin(u * 7.3F + 1.1F);
            // 前の縁は横にゆるく波打つ(升で 0〜3)。
            int front = Math.round((1.0F - ends) * nz * 0.45F + 1.5F + 1.5F * Mth.sin(u * 31.0F) + 1.2F * Mth.sin(u * 11.0F + 2.0F));
            for (int j = 0; j < nz; j++) {
                int at = i * nz + j;
                bottom[at] = 1;
                top[at] = 0;
                if (j < front) {
                    continue;
                }
                float t = (j - front + 0.5F) / Math.max(1, nz - front);
                float nose = base + thick * 0.55F;
                // 上の面: 鼻先から後ろへ、雲の底まで段々に上がる。下の面: 鼻先で丸く下がって底へ、後ろはでこぼこ。
                float topY = Mth.lerp(SunLight.smooth(Mth.clamp(t * 1.1F, 0.0F, 1.0F)), nose + thick * 0.12F, joinY) * 1.0F;
                topY = nose + (topY - nose) * (0.35F + 0.65F * ends * swell) + thick * 0.2F * (float) Math.sqrt(Mth.clamp(t * 6.0F, 0.0F, 1.0F));
                float round = (float) Math.sqrt(Mth.clamp(t * 5.0F, 0.0F, 1.0F));
                float rough = hash(i, j) * 14.0F * Mth.clamp(t * 3.0F - 0.4F, 0.0F, 1.0F) + 10.0F * Math.max(0.0F, Mth.sin(u * 61.0F + j * 0.9F)) * t;
                float botY = nose - (nose - base) * round * ends + rough + (1.0F - ends) * thick * 0.4F + t * t * 26.0F;
                // 段: 上の面は 12 ブロックの段に寄せる(なめらかな段々の縁)。
                int ty = Math.round(topY / 12.0F) * 12;
                int by = Math.round(botY / CELL_Y) * CELL_Y;
                if (ty - by >= CELL_Y) {
                    bottom[at] = by;
                    top[at] = ty;
                }
            }
        }
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int quads = 0;
        float x0 = -nx * CELL_X * 0.5F;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int at = i * nz + j;
                if (top[at] <= bottom[at]) {
                    continue;
                }
                float xa = x0 + i * CELL_X, xb = xa + CELL_X;
                float za = j * CELL_Z, zb = za + CELL_Z;
                float g = (j + 0.5F) / nz;
                float tone = hash(i * 7 + 3, j * 5 + 1);
                int ya = bottom[at], yb = top[at];
                // 上(明るい)・下(暗い)。
                quads += face(b, xa, yb, za, xa, yb, zb, xb, yb, zb, xb, yb, za, 1.0F, g, tone);
                quads += face(b, xa, ya, za, xb, ya, za, xb, ya, zb, xa, ya, zb, 0.0F, g, tone);
                // 横: となりより出ている分だけ。前(−z)は明るめ、後ろは暗め、左右は中間。
                quads += side(b, bottom, top, nx, nz, i, j - 1, ya, yb, xb, za, xa, za, 0.78F, g, tone);
                quads += side(b, bottom, top, nx, nz, i, j + 1, ya, yb, xa, zb, xb, zb, 0.3F, g, tone);
                quads += side(b, bottom, top, nx, nz, i - 1, j, ya, yb, xa, za, xa, zb, 0.55F, g, tone);
                quads += side(b, bottom, top, nx, nz, i + 1, j, ya, yb, xb, zb, xb, za, 0.55F, g, tone);
            }
        }
        MeshData mesh = b.build();
        if (vbo == null) {
            vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        if (mesh != null) {
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
        }
        builtQuads = quads;
    }

    private static int builtQuads;

    private static float hash(int i, int j) {
        int h = i * 374761393 + j * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    private static int face(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3,
                            float z3, float lit, float g, float tone) {
        b.addVertex(x0, y0, z0).setColor(lit, g, tone, 1.0F);
        b.addVertex(x1, y1, z1).setColor(lit, g, tone, 1.0F);
        b.addVertex(x2, y2, z2).setColor(lit, g, tone, 1.0F);
        b.addVertex(x3, y3, z3).setColor(lit, g, tone, 1.0F);
        return 1;
    }

    /** 升 (i, j) のとなり (ni, nj) との間の横の面。(xa, za) → (xb, zb) が面の下の辺。 */
    private static int side(BufferBuilder b, int[] bottom, int[] top, int nx, int nz, int ni, int nj, int ya, int yb, float xa, float za, float xb,
                            float zb, float lit, float g, float tone) {
        int nb = 1, nt = 0;
        if (ni >= 0 && nj >= 0 && ni < nx && nj < nz) {
            nb = bottom[ni * nz + nj];
            nt = top[ni * nz + nj];
        }
        int n = 0;
        if (nt <= nb) {
            return face(b, xa, ya, za, xb, ya, zb, xb, yb, zb, xa, yb, za, lit, g, tone);
        }
        if (yb > nt) {
            n += face(b, xa, Math.max(ya, nt), za, xb, Math.max(ya, nt), zb, xb, yb, zb, xa, yb, za, lit, g, tone);
        }
        if (ya < nb) {
            n += face(b, xa, ya, za, xb, ya, zb, xb, Math.min(yb, nb), zb, xa, Math.min(yb, nb), za, lit * 0.6F, g, tone);
        }
        return n;
    }

    /** AFTER_CUTOUT_BLOCKS({@link Atmosphere} から。水の中・盲目の間は呼ばれない)。 */
    static void render(RenderLevelStageEvent event) {
        if (Double.isNaN(lead) || present <= 0.0F || WeatherLook.off("arcus") || ShaderPacks.active() || ForestPresence.underground() > 0.5F) {
            return;
        }
        ShaderInstance shader = WeatherShaders.arcus();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (shader == null || level == null) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        build(p);
        if (vbo == null || builtQuads <= 0) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        double l = Double.isNaN(prevLead) ? lead : Mth.lerp(partial, prevLead, lead);
        // 枠の原点 = 前の縁の線の真ん中(カメラの真横を通る)。前の縁は風下(+dir)の側、後ろは風上。
        // 前の縁がカメラより風上 l ブロックにある → 原点 = カメラ − dir × l。
        float ox = (float) (-dirX * l);
        float oz = (float) (-dirZ * l);
        float night = SunLight.night(level, partial);
        float light = Mth.lerp(night, 1.0F, 0.1F) * (1.0F - 0.2F * level.getRainLevel(partial));
        float[] dark = p.rgb("arcusColor");
        float[] lit = p.rgb("arcusLitColor");
        float[] fog = RenderSystem.getShaderFogColor();
        float hide = Mth.clamp(Math.max(DenseFog.amount(), ForestPresence.fogForest() * 0.9F), 0.0F, 1.0F);
        float depthFar = mc.gameRenderer.getDepthFar();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        shader.safeGetUniform("ArcOrigin").set(ox, (float) -cam.y, oz, 0.0F);
        // 長さの軸 = 風の向きに直交、後ろの軸 = 風上(−dir)。
        shader.safeGetUniform("ArcAxes").set(-dirZ, dirX, -dirX, -dirZ);
        shader.safeGetUniform("ArcRemap").set(depthFar * 0.55F, depthFar * 0.96F, 0.0F, 0.0F);
        shader.safeGetUniform("ArcLit").set(lit[0] * light, lit[1] * light, lit[2] * light, 1.0F);
        shader.safeGetUniform("ArcDark").set(dark[0] * light, dark[1] * light, dark[2] * light, 1.0F);
        shader.safeGetUniform("ArcFog").set(fog[0], fog[1], fog[2], 0.0F);
        float from = p.f("arcusFrom");
        shader.safeGetUniform("ArcFade").set(hide, p.f("arcusAlpha") * SunLight.smooth(present), from * 0.55F, from * 1.25F);
        vbo.bind();
        vbo.drawWithShader(event.getModelViewMatrix(), event.getProjectionMatrix(), shader);
        VertexBuffer.unbind();
        RenderSystem.enableCull();
    }

    /** 確かめ用: 風下の向き(視点の yaw)。雲はこの反対から来る。 */
    static double downwindYaw() {
        return Math.toDegrees(Math.atan2(-dirX, dirZ));
    }

    static String describe() {
        return String.format("アーク雲 前の縁まで %.0f・出ている %.2f・面 %d・突風 %s", lead, present, builtQuads, gusted);
    }
}
