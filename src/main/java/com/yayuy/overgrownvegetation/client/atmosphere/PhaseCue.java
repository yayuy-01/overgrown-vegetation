package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.sound.PhaseCueSound;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.PhaseCuePayload;
import com.yayuy.overgrownvegetation.network.PhyllosensGazePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * <b>フェーズが上がったときの合図</b>(クライアント側。2026-11-16。ユーザー決定の中身は Vault「フェーズの仕組み」の「フェーズが上がったときの現象」 || !com.yayuy.overgrownvegetation.client.VisualQuality.effects( || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()))。
 *
 * <p>理由: フェーズが上がったことを、数字ではなく現象で知らせる(汚れを見た目だけで見せるのと同じ考え)。
 * サーバーの {@code phase/PhaseCues} が {@link PhaseCuePayload} を送ったら、次の流れを 1 回だけ流す(時刻は合図の始まりからの秒。仮決め):
 * <ol>
 *     <li><b>地鳴り</b>(0〜6 秒): 低い音({@link PhaseCueSound#rumble})と、画面の小さな揺れ(向きを {@code shake} 度まで。0〜1 秒で立ち上がり、3.5〜5.5 秒で消える)。
 *     退避した地割れの揺れ(parked の CameraShakeMixin)とは別物で、F1 の地割れの見え方は変えない。</li>
 *     <li><b>空が黄緑ににじむ</b>(1.5〜9 秒): 画面の奥(空と遠くの景色)へ黄緑を重ねる({@code phase_tint.fsh})。2 秒で出て 2 秒保ち 3.5 秒で消える。
 *     空・雲・遠くの霧のどれにも同じに掛かるよう、描き終えた画面の奥行きで決める。地下では出さない。</li>
 *     <li><b>ざわめき</b>(3 秒から約 6.5 秒): 汚染の森の方角から({@link PhaseCueSound#rustle})。森が無い世界・別のディメンションでは鳴らない。</li>
 * </ol>
 * フェーズごとの印のうち、ここで作るのは <b>F5「見られている目が一瞬開く」</b>(5 秒から約 1.8 秒 + 閉じる余韻。{@link #gazeMark})と
 * <b>F6「拠点の灯りが一瞬揺らいで暗くなる」</b>(5〜6.6 秒。ライトマップのブロックの光を空の光だけの色へ寄せる。
 * {@code LightTexturePhaseCueMixin})。ほかの印(F2 芽吹き・F4 弱い嵐はサーバー側 phase/PhaseOmens、F3 オーロラ・F7 胞子の柱は元になる仕組みがまだ無いので未着手
 * ({@link #markFor} に置き場だけある)。
 *
 * <p>数は design の節 {@code phaseCue}(仮決め)。確かめは {@code /ov fx phasecue <フェーズ>}(フェーズは変えずに合図だけ流す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class PhaseCue {

    private PhaseCue() {
    }

    private static final float SHAKE_END = 5.5F;
    private static final float TINT_START = 1.5F;
    private static final float TINT_END = 9.0F;
    private static final float RUSTLE_AT = 3.0F;
    private static final float DIP_START = 5.0F;
    private static final float DIP_END = 6.6F;
    private static final float TOTAL = 9.5F;

    private static boolean active;
    private static int ticks;
    private static int phase;
    private static boolean hasForest;
    private static float dirX;
    private static float dirZ;
    private static boolean rustled;
    private static boolean broken;
    private static int played;
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean haveProjection;
    private static TextureTarget target;

    public static void accept(PhaseCuePayload p) {
        active = true;
        ticks = 0;
        phase = p.phase();
        hasForest = p.hasForest();
        dirX = p.dirX();
        dirZ = p.dirZ();
        rustled = false;
        played++;
        // F7 の胞子の柱(サーバーがその夜に起こす)のために、森の方角を覚えておく(11-16。client/worldevent/WorldEventCues)。F3 の短いオーロラもサーバーが起こす。
        com.yayuy.overgrownvegetation.client.worldevent.WorldEventCues.onPhaseCue(phase, hasForest, dirX, dirZ);
        AtmosphereEye.PhaseCueParams d = DesignData.atmosphere().phaseCue();
        if (!p.quiet()) {
            PhaseCueSound.rumble(d.rumbleVolume());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            active = false;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        float t = ticks / 20.0F;
        if (!rustled && t >= RUSTLE_AT) {
            rustled = true;
            LocalPlayer player = mc.player;
            if (hasForest && player != null) {
                // 向きだけが要る(距離では弱めない)。聞く人から 24 ブロック先に置く。
                PhaseCueSound.rustle(DesignData.atmosphere().phaseCue().rustleVolume(),
                        player.getX() + dirX * 24.0, player.getEyeY() + 6.0, player.getZ() + dirZ * 24.0);
            }
        }
        if (phase == 5) {
            gazeMark(ticks - Math.round(DIP_START * 20.0F));
        }
        if (t >= TOTAL) {
            active = false;
        }
    }

    /** F5 の印の「塔」の番号(本物の監視塔のエンティティ id と重ならない負の数)と、目を出している長さ(tick)。 */
    private static final int GAZE_TOWER = -5005;
    private static final int GAZE_TICKS = 36;

    /**
     * F5 の印: 監視塔フィロセンスに「見られている」ときの画面の縁の目({@code client/PhyllosensGazeEdge}・{@code PhyllosensGazeEyes}。ユーザーの絵)を、
     * 一瞬だけ出す(11-16 ユーザー: 見られているときのエフェクトはもうあるので、フェーズが上がるときに出す)。画面の見た目だけで、
     * 「見られている」効果(1 分の印)は付けない。本物の塔の知らせと同じ口へ、偽の塔 1 本ぶんの知らせを流す:
     * 目が出そろった状態から始め(見え始めから 6 秒たった扱い)、次の tick に印(一斉に瞬いてこちらを向く)、約 1.8 秒見られ続けて、切れる
     * (切れた後は、元の効果のとおり残った目がゆっくり閉じる)。見た目の設定「見られているとき」の強さ・F1 で隠す決まりは元の効果のまま効く。
     */
    private static void gazeMark(int age) {
        if (age < 0 || age > GAZE_TICKS) {
            return;
        }
        byte event;
        if (age == 0) {
            event = PhyllosensGazePayload.START;
        } else if (age == 1) {
            event = PhyllosensGazePayload.MARK;
        } else if (age == GAZE_TICKS) {
            event = PhyllosensGazePayload.LOST;
        } else if (age % 5 == 0) {
            event = PhyllosensGazePayload.SEEN;
        } else {
            return;
        }
        com.yayuy.overgrownvegetation.client.PhyllosensGazeEdge.receive(new PhyllosensGazePayload(event, GAZE_TOWER, 1.0F, 120 + age, age >= 1 ? age - 1 : -1));
        if (age == 0) {
            // 目は見られ続けて 6 秒かけて出そろう作りなので、一瞬の印では出そろった所から始める。
            com.yayuy.overgrownvegetation.client.PhyllosensGazeEyes.showAllNow();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
    }

    private static float seconds(float partialTick) {
        return (ticks + partialTick) / 20.0F;
    }

    private static float ramp(float t, float from, float to) {
        float x = Mth.clamp((t - from) / (to - from), 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** 揺れの強さ(0〜1)。 */
    static float shake(float partialTick) {
        if (!active) {
            return 0.0F;
        }
        float t = seconds(partialTick);
        return ramp(t, 0.0F, 1.0F) * (1.0F - ramp(t, 3.5F, SHAKE_END));
    }

    /** 空のにじみの強さ(0〜1)。 */
    static float tint(float partialTick) {
        if (!active) {
            return 0.0F;
        }
        float t = seconds(partialTick);
        return ramp(t, TINT_START, TINT_START + 2.0F) * (1.0F - ramp(t, TINT_END - 3.5F, TINT_END));
    }

    /**
     * F6 の印: ブロックの光をどれだけ落とすか(0 = そのまま、1 = 空の光だけの明るさ)。フェーズ 6 の合図の間だけ。
     * 揺らいでから(細かい強弱 3 回ほど)一度深く落ち、すぐ戻る。
     */
    public static float lightDip() {
        if (!active || phase != 6 || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()) {
            return 0.0F;
        }
        float t = seconds(0.0F);
        if (t < DIP_START || t > DIP_END) {
            return 0.0F;
        }
        float u = (t - DIP_START) / (DIP_END - DIP_START);
        float flutter = u < 0.45F ? 0.35F * (0.5F + 0.5F * Mth.sin(u * 42.0F)) : 0.0F;
        float deep = ramp(u, 0.40F, 0.55F) * (1.0F - ramp(u, 0.70F, 1.0F));
        return Mth.clamp(Math.max(flutter, deep), 0.0F, 1.0F) * DesignData.atmosphere().phaseCue().lightDip();
    }

    /** フェーズごとの印の置き場(今は F6 だけ。ほかは元の仕組みができてからつなぐ)。 */
    static String markFor(int phase) {
        return switch (phase) {
            case 1 -> "地割れ(サーバー側。前からある)";
            case 5 -> "見られている目が一瞬開く";
            case 6 -> "拠点の灯りが揺らいで暗くなる";
            case 2, 4 -> "サーバー側(phase/PhaseOmens)";
            case 3 -> "その夜に短いオーロラ(サーバーの合図の枠 → client/worldevent/WorldEventSky)";
            case 7 -> "一夜だけ森の方角に胞子の柱(サーバーの合図の枠 → client/worldevent/SporeRise)";
            default -> "なし";
        };
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        float s = shake((float) event.getPartialTick());
        if (s <= 0.001F || Minecraft.getInstance().isPaused() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()) {
            return;
        }
        float amp = s * DesignData.atmosphere().phaseCue().shake();
        // 実時間の正弦の和(フレームの速さによらない)。地鳴りなので低めの速さで、縦を主に。
        double t = (System.nanoTime() % 100_000_000_000L) / 1.0e9;
        event.setPitch(event.getPitch() + amp * (float) (Math.sin(t * 31.0) * 0.6 + Math.sin(t * 47.0) * 0.4));
        event.setYaw(event.getYaw() + amp * 0.6F * (float) (Math.sin(t * 23.0) * 0.6 + Math.sin(t * 41.0) * 0.4));
        event.setRoll(event.getRoll() + amp * 0.5F * (float) Math.sin(t * 37.0));
    }

    /**
     * 世界を描き終えた所(AFTER_LEVEL。手を描く前)。手を描く直前に画面の奥行きが消されるので、その前に掛ける(手には掛からない)。
     * 光のにじみ・目の慣れはこの後なので、にじんだ空の上に掛かる。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            PROJECTION.set(event.getProjectionMatrix());
            haveProjection = true;
            renderPost();
        }
    }

    private static void renderPost() {
        if (!active || broken || !haveProjection) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ShaderPacks.active() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()) {
            return;
        }
        AtmosphereEye.PhaseCueParams p = DesignData.atmosphere().phaseCue();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float amount = tint(partial) * p.skyTint() * (1.0F - ForestPresence.underground());
        ShaderInstance shader = ModShaders.phaseTint();
        ShaderInstance blit = ModShaders.phaseTintBlit();
        if (amount <= 0.002F || shader == null || blit == null) {
            return;
        }
        try {
            // 夜は黄緑を暗く(真っ黒な空に昼の明るさの色を載せない)。雨の日も少し暗く。
            float night = SunLight.night(mc.level, partial);
            float bright = Mth.lerp(night, 1.0F, p.nightBrightness()) * (1.0F - 0.4F * mc.level.getRainLevel(partial));
            RenderTarget main = mc.getMainRenderTarget();
            int w = Math.max(1, main.width / 2);
            int h = Math.max(1, main.height / 2);
            if (target == null) {
                target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
            } else if (target.width != w || target.height != h) {
                target.resize(w, h, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
            }
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            // 1: 奥行きから、色と掛かり具合(アルファ)を半分の大きさの器へ(画面を描き先にしたまま、その奥行きは読めない)。
            RenderSystem.disableBlend();
            target.bindWrite(true);
            shader.setSampler("DepthTex", main.getDepthTextureId());
            shader.safeGetUniform("TintColor").set(p.red() * bright, p.green() * bright, p.blue() * bright, amount);
            shader.safeGetUniform("TintA").set(PROJECTION.m22(), PROJECTION.m32(), p.near(), p.far());
            quad(shader);
            // 2: 画面へふつうのアルファで重ねる。
            main.bindWrite(true);
            blit.setSampler("TintTex", target.getColorTextureId());
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            quad(blit);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("フェーズの合図の空のにじみで例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static void quad(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, -1.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** 確かめ用の 1 行({@code clouds status} などから)。 */
    public static String describe() {
        if (!active) {
            return "フェーズの合図: 止まっている(これまで " + played + " 回)";
        }
        return String.format("フェーズの合図: F%d・%.1f 秒・揺れ %.2f・空 %.2f・灯り %.2f・森の方角 %s・印 %s",
                phase, seconds(0.0F), shake(0.0F), tint(0.0F), lightDip(),
                hasForest ? String.format("(%.2f, %.2f)", dirX, dirZ) : "なし", markFor(phase));
    }
}
