package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.TextureStitchEvent;

import java.util.Map;

/**
 * <b>鉱石のきらめき</b>(2026-11-13 シェーダーの仕上げ E。ユーザー決定: 既定でオン)。
 *
 * <p>理由: 暗い洞窟で、手に持った明かりや点光源が近いと鉱石の画素だけが小さくきらめく。探す手がかりと美しさを兼ねる(人類側の視点の小さなご褒美)。
 *
 * <h2>やり方</h2>
 * <ul>
 *     <li><b>鉱石の粒の印</b>: ブロックのアトラスを焼くたびに、アトラスと同じ並びの絵({@code OvOreMask})を作り、鉱石の絵
 *     (名前が {@code _ore} で終わる {@code block/} の絵。ほかの MOD の鉱石も)の「粒」の画素にだけ印を付ける。粒 = 母岩の絵
 *     (石・深層岩・ネザーラックなど。いちばんよく一致するもの)と色が違う画素。母岩が分からない絵は、絵の中でいちばん多い色から離れた画素。</li>
 *     <li><b>きらめき</b>({@code ov_ore.glsl}): 地形のシェーダーが、点光源・手の明かりの届いている面({@code ov_cave.glsl} の光の量)で印の付いた画素を見つけると、
 *     ブロックの絵の 1 画素ごと(世界に固定)に決めた向きの「面」が目の方を向いたときだけ光らせる。歩く・見回す・時間(炎の揺れ)で入れ替わる。
 *     同時に光る粒は全体の {@code share}(8%)。光の色を白へ寄せた色で、霧の前に足す。</li>
 * </ul>
 * 光が届く所 = 地下モードの点光源(この MOD の光る植物・松明や溶岩などバニラの光源)と、手に持った明かり。地上の昼は届かないので光らない。
 * 掛けない場合: 見え方の設定「地下の光」を切った・design の {@code oreSparkle.enabled}・シェーダーパック使用中。数は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class OreSparkle {

    private OreSparkle() {
    }

    /** 母岩の候補(いちばんよく一致する絵を、その鉱石の母岩とみなす)。 */
    private static final String[] HOSTS = {"stone", "deepslate", "netherrack", "blackstone", "end_stone", "tuff", "granite", "diorite", "andesite",
            "sandstone", "basalt_side", "calcite", "dripstone_block"};
    /** 母岩と同じ画素がこの割合より多ければ、その母岩との違いで粒を決める。 */
    private static final float HOST_MATCH = 0.2F;

    private static DynamicTexture mask;
    private static int atlasWidth;
    private static int atlasHeight;
    private static int oreSprites;
    private static int grains;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Stitch {
        private Stitch() {
        }

        @SubscribeEvent
        public static void onStitched(TextureStitchEvent.Post event) {
            TextureAtlas atlas = event.getAtlas();
            if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
                return;
            }
            try {
                build(atlas);
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.error("鉱石の粒の印を作れなかったので、鉱石のきらめきは出さない", e);
                close();
            }
        }
    }

    private static void close() {
        if (mask != null) {
            mask.close();
            mask = null;
        }
    }

    private static void build(TextureAtlas atlas) {
        close();
        Map<ResourceLocation, TextureAtlasSprite> all = new java.util.HashMap<>(); // 1.20.1: getTextures() の代わり
        for (ResourceLocation loc : atlas.getTextureLocations()) {
            all.put(loc, atlas.getSprite(loc));
        }
        // アトラスの大きさ: 絵の幅 ÷ (u1 − u0)。
        TextureAtlasSprite any = null;
        for (TextureAtlasSprite s : all.values()) {
            if (s.contents().width() >= 16) {
                any = s;
                break;
            }
        }
        if (any == null) {
            return;
        }
        atlasWidth = Math.round(any.contents().width() / (any.getU1() - any.getU0()));
        atlasHeight = Math.round(any.contents().height() / (any.getV1() - any.getV0()));
        NativeImage image = new NativeImage(NativeImage.Format.LUMINANCE, atlasWidth, atlasHeight, true);
        oreSprites = 0;
        grains = 0;
        try {
            for (Map.Entry<ResourceLocation, TextureAtlasSprite> e : all.entrySet()) {
                String path = e.getKey().getPath();
                if (!path.startsWith("block/") || !path.endsWith("_ore")) {
                    continue;
                }
                oreSprites++;
                grains += mark(image, e.getValue(), all);
            }
        } catch (RuntimeException ex) {
            image.close(); // 途中で落ちたらネイティブのメモリを返す(11-18 見直し ④9。DynamicTexture に渡した後は向こうが閉じる)
            throw ex;
        }
        mask = new DynamicTexture(image);
        mask.upload();
        OvergrownVegetation.LOGGER.info("鉱石のきらめき: アトラス {}x{}・鉱石の絵 {} 枚・粒の画素 {}", atlasWidth, atlasHeight, oreSprites, grains);
    }

    /** 1 枚の鉱石の絵の粒に印を付ける。付けた数。 */
    private static int mark(NativeImage image, TextureAtlasSprite sprite, Map<ResourceLocation, TextureAtlasSprite> all) {
        SpriteContents c = sprite.contents();
        NativeImage ore = c.getOriginalImage();
        int w = c.width();
        int h = c.height();
        // いちばんよく一致する母岩。
        NativeImage host = null;
        int best = 0;
        for (String name : HOSTS) {
            TextureAtlasSprite hs = all.get(ResourceLocation.withDefaultNamespace("block/" + name));
            if (hs == null || hs.contents().width() != w || hs.contents().height() < h) {
                continue;
            }
            NativeImage hi = hs.contents().getOriginalImage();
            int same = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (near(ore.getPixelRGBA(x, y), hi.getPixelRGBA(x, y), 6)) {
                        same++;
                    }
                }
            }
            if (same > best) {
                best = same;
                host = hi;
            }
        }
        boolean useHost = host != null && best >= HOST_MATCH * w * h;
        int common = useHost ? 0 : commonColor(ore, w, h);
        int marked = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = ore.getPixelRGBA(x, y);
                if (((p >>> 24) & 0xFF) < 128) {
                    continue;
                }
                boolean grain = useHost ? !near(p, host.getPixelRGBA(x, y), 24) : !near(p, common, 56);
                if (grain) {
                    int ax = sprite.getX() + x;
                    int ay = sprite.getY() + y;
                    if (ax >= 0 && ay >= 0 && ax < image.getWidth() && ay < image.getHeight()) {
                        image.setPixelLuminance(ax, ay, (byte) 0xFF);
                        marked++;
                    }
                }
            }
        }
        return marked;
    }

    /** 2 つの色(ABGR)の各成分の差がどれも {@code tolerance} 以内か。 */
    private static boolean near(int a, int b, int tolerance) {
        return Math.abs((a & 0xFF) - (b & 0xFF)) <= tolerance && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tolerance
                && Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tolerance;
    }

    /** 絵の中でいちばん多い色(各成分を 32 段に丸めて数える)。 */
    private static int commonColor(NativeImage ore, int w, int h) {
        java.util.HashMap<Integer, int[]> count = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = ore.getPixelRGBA(x, y);
                int key = (p & 0xE0) | (p & 0xE000) | (p & 0xE00000);
                int[] v = count.computeIfAbsent(key, k -> new int[]{0, p});
                v[0]++;
            }
        }
        int best = 0;
        int color = 0;
        for (int[] v : count.values()) {
            if (v[0] > best) {
                best = v[0];
                color = v[1];
            }
        }
        return color;
    }

    /** 毎フレーム(空を描いた後 = 地形の前): 地形のシェーダーへ印の絵と値を入れる(Sodium はバニラのシェーダーから写す)。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        ShaderInstance shader = GameRenderer.getRendertypeSolidShader();
        if (shader == null) {
            return;
        }
        AtmosphereEye.OreSparkleParams p = DesignData.atmosphere().oreSparkle();
        boolean on = mask != null && p.enabled() && p.strength() > 0.0F && VisualQuality.caveLight() && !ShaderPacks.active();
        if (!on) {
            shader.safeGetUniform("OvOre").set(0.0F, 0.0F, 1024.0F, 1024.0F);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float time = mc.level == null ? 0.0F : (float) ((mc.level.getGameTime() % 24000L + event.getPartialTick()) / 20.0);
        shader.setSampler("OvOreMask", mask.getId());
        shader.safeGetUniform("OvOre").set(p.strength(), time, (float) atlasWidth, (float) atlasHeight);
        shader.safeGetUniform("OvOreB").set((float) (cam.x - Math.floor(cam.x / 64.0) * 64.0), (float) (cam.y - Math.floor(cam.y / 64.0) * 64.0),
                (float) (cam.z - Math.floor(cam.z / 64.0) * 64.0), p.share());
    }

    /** 確かめ用の一行。 */
    public static String describe() {
        return String.format("鉱石のきらめき 印 %s アトラス %dx%d 鉱石の絵 %d 枚 粒 %d", mask != null, atlasWidth, atlasHeight, oreSprites, grains);
    }
}
