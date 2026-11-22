package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.LeyVeinMapPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>岩肌の地脈の筋</b>(地脈の回廊。2026-11-14 シェーダーの仕上げ F の 2)。
 *
 * <p>ユーザー決定(11-14): 回廊の光は「岩肌にうっすら水色の地脈の筋が見える」・世界全体の洞窟・色は汚染地域が地脈の根と同じ色、通常世界は水色で、
 * 境目は滑らかに変わる。理由: 地脈の近くの洞窟だけ淡く照らされる回廊(地下モードの見え方 U1 の (b))。地脈を探す手がかりにもなる。
 *
 * <h2>やり方</h2>
 * <ul>
 *     <li>地脈の線の場({@code corruption/LeyLines})はワールドのシードで決まり、クライアントはシードを持たない。サーバー({@code ley/LeyVeinSync})が
 *     プレイヤーのまわり 192 ブロック四方の「線までの距離」と「汚染地域か」を 4 ブロックの升で送る({@link LeyVeinMapPayload})。</li>
 *     <li>ここはそれを絵にして(r = 距離、g = 汚染。汚染は升 2 つ分ぼかして境目をなだらかに)地形のシェーダーへ渡す。</li>
 *     <li>シェーダー({@code ov_ley.glsl}): 地下とみなした不透明の面のうち灰色の画素(岩)に、世界に固定した雑音の等高線に沿う細い筋を足す。
 *     線に近いほど筋が太く多く、{@code reach} ブロック離れると消える。筋にはゆっくりした脈動。</li>
 * </ul>
 * 掛けない場合: 見え方の設定「地下の光」を切った・design の {@code leyVeins.enabled}・シェーダーパック使用中・升がまだ届いていない。数と色の値は仮決め
 * (汚染地域の色は地脈の根の光 #ACE300)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class LeyVeins {

    private LeyVeins() {
    }

    private static DynamicTexture texture;
    private static int textureSize;
    private static ClientLevel level;
    private static int minX;
    private static int minZ;
    private static int size;
    private static int nearCells;
    /** 届いた升の写し(線までの距離・汚染。地脈の大潮の光 client/worldevent/LeyTide が読む)。 */
    private static byte[] rawDistance = new byte[0];
    private static byte[] rawPolluted = new byte[0];

    /** そのブロックの列から地脈の線までの距離(ブロック。0〜63.75)。升の外・届いていなければ負。 */
    public static float distanceAt(double x, double z) {
        int i = (int) Math.floor((x - minX) / LeyVeinMapPayload.CELL);
        int j = (int) Math.floor((z - minZ) / LeyVeinMapPayload.CELL);
        if (size <= 0 || i < 0 || j < 0 || i >= size || j >= size || rawDistance.length < size * size || level != Minecraft.getInstance().level) {
            return -1.0F;
        }
        return (rawDistance[j * size + i] & 0xFF) / 4.0F;
    }

    /** その列が汚染地域(過繁茂・繁茂)か。升の外は false。 */
    public static boolean pollutedAt(double x, double z) {
        int i = (int) Math.floor((x - minX) / LeyVeinMapPayload.CELL);
        int j = (int) Math.floor((z - minZ) / LeyVeinMapPayload.CELL);
        return size > 0 && i >= 0 && j >= 0 && i < size && j < size && rawPolluted.length >= size * size && (rawPolluted[j * size + i] & 0xFF) > 127;
    }

    /** ログアウトで、前のワールドを手放す(見直し 第 2 回 ④3。絵の器は次のワールドで使い回す)。 */
    static void forget() {
        level = null;
        size = 0;
    }

    /** サーバーから升が届いた(描画のスレッドで)。 */
    public static void accept(LeyVeinMapPayload p) {
        level = Minecraft.getInstance().level;
        size = p.size();
        minX = p.minX();
        minZ = p.minZ();
        if (size <= 0 || p.distance().length < size * size || p.polluted().length < size * size) {
            size = 0;
            return;
        }
        rawDistance = p.distance();
        rawPolluted = p.polluted();
        try {
            if (texture == null || textureSize != size) {
                if (texture != null) {
                    texture.close();
                }
                texture = new DynamicTexture(size, size, false);
                texture.setFilter(true, false);
                textureSize = size;
            }
            NativeImage image = texture.getPixels();
            if (image == null) {
                size = 0;
                return;
            }
            // 汚染の升を 2 回ぼかす(境目が 4 ブロックの段にならず、なだらかに色が変わる)。
            float[] polluted = new float[size * size];
            for (int i = 0; i < polluted.length; i++) {
                polluted[i] = (p.polluted()[i] & 0xFF) / 255.0F;
            }
            for (int pass = 0; pass < 2; pass++) {
                float[] next = new float[polluted.length];
                for (int j = 0; j < size; j++) {
                    for (int i = 0; i < size; i++) {
                        float sum = 0.0F;
                        for (int dj = -1; dj <= 1; dj++) {
                            for (int di = -1; di <= 1; di++) {
                                sum += polluted[Math.max(0, Math.min(size - 1, j + dj)) * size + Math.max(0, Math.min(size - 1, i + di))];
                            }
                        }
                        next[j * size + i] = sum / 9.0F;
                    }
                }
                polluted = next;
            }
            nearCells = 0;
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int d = p.distance()[j * size + i] & 0xFF;
                    if (d < 96) {
                        nearCells++;
                    }
                    int g = Math.round(polluted[j * size + i] * 255.0F);
                    // ABGR
                    image.setPixelRGBA(i, j, 0xFF000000 | (g << 8) | d);
                }
            }
            texture.upload();
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("地脈の筋の升を絵にできなかった", e);
            size = 0;
        }
    }

    /** 毎フレーム(空を描いた後 = 地形の前): 地形のシェーダーへ升の絵と値を入れる(Sodium はバニラのシェーダーから写す)。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        ShaderInstance shader = GameRenderer.getRendertypeSolidShader();
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AtmosphereEye.LeyVeinsParams p = DesignData.atmosphere().leyVeins();
        boolean on = texture != null && size > 0 && mc.level != null && mc.level == level && p.enabled() && p.strength() > 0.0F
                && VisualQuality.caveLight() && !ShaderPacks.active();
        if (!on) {
            shader.safeGetUniform("OvLey").set(0.0F, 0.0F, 192.0F, 0.0F);
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float blocks = size * LeyVeinMapPayload.CELL;
        float time = (float) ((mc.level.getGameTime() % 24000L + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0);
        shader.setSampler("OvLeyMap", texture.getId());
        // 地脈の大潮(ワールドイベント。11-16)の間は、筋が明るくなる。
        float tide = com.yayuy.overgrownvegetation.client.worldevent.LeyTide.veinBoost();
        shader.safeGetUniform("OvLey").set((float) (minX - cam.x), (float) (minZ - cam.z), blocks, p.strength() * tide);
        shader.safeGetUniform("OvLeyB").set((float) (cam.x - Math.floor(cam.x / 256.0) * 256.0), (float) (cam.y - Math.floor(cam.y / 256.0) * 256.0),
                (float) (cam.z - Math.floor(cam.z / 256.0) * 256.0), time);
        shader.safeGetUniform("OvLeyC").set(p.color()[0], p.color()[1], p.color()[2], p.reach() * (1.0F + 0.5F * (tide - 1.0F)));
        shader.safeGetUniform("OvLeyD").set(p.pollutedColor()[0], p.pollutedColor()[1], p.pollutedColor()[2], 0.0F);
    }

    /** 確かめ用の一行。 */
    public static String describe() {
        return String.format("地脈の筋 升 %d(左上 %d %d・線から 24 ブロック以内の升 %d)", size, minX, minZ, nearCells);
    }
}
