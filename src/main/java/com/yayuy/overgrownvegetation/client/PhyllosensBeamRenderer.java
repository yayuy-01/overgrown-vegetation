package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/**
 * フィロセンス({@link PhyllosensEntity})のビームの描画。
 *
 * <p>形とテクスチャは攻城戦ボスのビーム({@link SiegeBeamRenderer})と共通({@link BeamGeometry})だが、
 * <b>ずっと細い</b>({@code phyllosensBeamDrawRadius}、既定 0.28 — ティタニカのものは 2 以上)。
 *
 * <p>段階ごとの見え方:
 * <ul>
 *     <li><b>溜め</b>({@code BEAM_CHARGE}) — まだ光線は出さず、花の先に光の粒が集まるだけ
 *     (小さな円盤を脈動させる)。3 秒あるので「撃たれる」と分かるのが大事。</li>
 *     <li><b>狙点固定</b>({@code BEAM_LOCKED}) — 0.5 秒。花から狙点まで<b>細い予告線</b>を引く。
 *     ここから先は狙点が動かないので、この線が「もう避けられる」合図になる。</li>
 *     <li><b>照射</b>({@code BEAM_FIRE}) — 本体。頭で一気に開いて、そのあと細くなって消える。
 *     着弾点に閃光。</li>
 * </ul>
 *
 * <p>エンティティのレンダラー({@link PhyllosensRenderer})ではなくレベルの描画段階で描くのは、
 * ボスのビームと同じ理由 — 光線は本体の姿勢とは無関係にワールド座標で引きたいのと、
 * 半透明をまとめて 1 回で流したいため。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PhyllosensBeamRenderer {

    /** 芯: 明るい緑。 */
    private static final float[] CORE = {0.78F, 1.0F, 0.66F, 0.95F};
    /** 殻: 暗い緑。 */
    private static final float[] SHELL = {0.18F, 0.58F, 0.20F, 0.72F};
    /** 縁: 深い緑。 */
    private static final float[] RIM = {0.04F, 0.18F, 0.06F, 0.36F};
    /** 着弾・溜めの閃光。 */
    private static final float[] FLASH = {0.45F, 0.95F, 0.40F, 0.55F};

    /** 細いビームなのでテクスチャのタイルも短くする(間延びしないように)。 */
    private static final float TILE_LENGTH = 10.0F;
    /** 光線を見せている長さ(tick)。{@code PhyllosensBeamGoal.FIRE_TICKS}(14)より短く、
     * 姿勢が戻りきる前に光線だけ先に消える。 */
    private static final float FIRE_TICKS = 8.0F;

    /** 描画のために覚えておく「照射が始まった時刻」。エンティティごと。 */
    private static final java.util.Map<Integer, Long> FIRE_START = new java.util.HashMap<>();

    /**
     * 撃っているフィロセンスの置き場。<b>毎フレーム作り直さず使い回す</b> ——
     * ここは 1 フレームに 1 回、描画スレッドからしか触らないので、
     * 呼び出しの頭で {@code clear()} してから詰め直せば取り違えは起きない。
     */
    private static final java.util.List<PhyllosensEntity> ACTIVE = new java.util.ArrayList<>();

    /**
     * クライアントのレベルにいるフィロセンス全員(撃っていないものも)。
     *
     * <p>2026-09-24 軽量化(監査 1439): 以前は撃っている個体を探すため、毎フレーム
     * {@code entitiesForRendering()} の全エンティティを回していた。ClientLevel#addEntity が出す
     * {@link EntityJoinLevelEvent} で足し、取り除くときの {@link EntityLeaveLevelEvent} で外す。
     * 入る順番は {@code entitiesForRendering()} と同じ(追加順)なので、描く順番も変わらない。
     * 次元の移動や切断では古い ClientLevel の個体に Leave が来ないので、描画側で今のレベルの個体か
     * 確かめて外し、ログアウトでも空にする。触るのはクライアントのメインスレッドだけ。
     * 遠距離でもアニメを続ける例外(意図した仕様)とは無関係 —— 対象の個体も描く内容も同じ。
     */
    private static final java.util.Set<PhyllosensEntity> PRESENT =
            new it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet<>();

    /** 取り消された追加を拾わないよう最後に聞く(取り消されたら届かない)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof PhyllosensEntity phyllosens) {
            PRESENT.add(phyllosens);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof PhyllosensEntity phyllosens) {
            PRESENT.remove(phyllosens);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PRESENT.clear();
        ACTIVE.clear();
    }

    /** 次元の移動・切断で ClientLevel が捨てられる(シングルではサーバー側の Unload も来るので、クライアントのものだけ)。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PRESENT.clear();
            ACTIVE.clear();
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        java.util.List<PhyllosensEntity> active = ACTIVE;
        active.clear();
        for (java.util.Iterator<PhyllosensEntity> it = PRESENT.iterator(); it.hasNext(); ) {
            PhyllosensEntity phyllosens = it.next();
            if (phyllosens.level() != minecraft.level) {
                it.remove(); // 前の ClientLevel(次元の移動・切断)の取り残し。今のレベルには居ない
                continue;
            }
            if (phyllosens.getBeamPhase() != PhyllosensEntity.BEAM_NONE) {
                active.add(phyllosens);
            }
        }
        if (active.isEmpty()) {
            if (!FIRE_START.isEmpty()) {
                FIRE_START.clear();
            }
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        float time = minecraft.level.getGameTime() + partialTick;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        MultiBufferSource.BufferSource bufferSource = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        RenderType renderType = RenderType.entityTranslucent(BeamGeometry.TEXTURE);
        VertexConsumer buffer = bufferSource.getBuffer(renderType);
        Matrix4f matrix = poseStack.last().pose();

        for (PhyllosensEntity mob : active) {
            draw(buffer, matrix, mob, camera, minecraft.level.getGameTime(), partialTick, time);
        }

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        bufferSource.endBatch(renderType);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        poseStack.popPose();
    }

    private static void draw(VertexConsumer buffer, Matrix4f matrix, PhyllosensEntity mob, Vec3 camera,
                             long gameTime, float partialTick, float time) {
        Vec3 start = mob.beamOrigin().subtract(camera);
        Vec3 end = mob.getBeamTarget().subtract(camera);
        float radius = (float) MobStats.beam("phyllosens").drawRadius();

        switch (mob.getBeamPhase()) {
            case PhyllosensEntity.BEAM_CHARGE -> {
                FIRE_START.remove(mob.getId());
                // 光が集まるだけ。脈動させて「溜まっている」ことを見せる。
                float pulse = 0.6F + 0.4F * Mth.sin(time * 0.35F);
                BeamGeometry.disc(buffer, matrix, start, radius * (1.6F + 1.4F * pulse), FLASH, 0.30F + 0.25F * pulse, time);
            }
            case PhyllosensEntity.BEAM_LOCKED -> {
                FIRE_START.remove(mob.getId());
                // 予告線。細く・薄く、しかしはっきり狙点まで届く。
                BeamGeometry.cone(buffer, matrix, start, end, radius * 0.16F, radius * 0.16F,
                        CORE, 0.55F, time, 1.4F, time * 0.05F, TILE_LENGTH);
                BeamGeometry.disc(buffer, matrix, end, radius * 1.6F, FLASH, 0.30F, time);
            }
            case PhyllosensEntity.BEAM_FIRE -> {
                long began = FIRE_START.computeIfAbsent(mob.getId(), id -> gameTime);
                float age = (gameTime - began) + partialTick;
                float progress = Mth.clamp(age / FIRE_TICKS, 0.0F, 1.0F);
                // 頭で一気に開いて、あとは細くなって消える。
                float open = Mth.clamp(age * 3.0F, 0.0F, 1.0F);
                float fade = 1.0F - progress;
                float r = radius * open * (0.35F + 0.65F * fade);
                beam(buffer, matrix, start, end, r * 0.7F, r, time);
                float flash = 1.0F - Mth.clamp(progress * 2.0F, 0.0F, 1.0F);
                BeamGeometry.disc(buffer, matrix, end.add(0.0D, 0.05D, 0.0D),
                        radius * (2.0F + 4.0F * flash), FLASH, 0.30F + 0.45F * flash, time);
            }
            default -> {
            }
        }
    }

    /** 光線本体: 明るい芯 → 暗い緑の殻 → 深い緑の縁。ボスのものと同じ 3 重だが細い。 */
    private static void beam(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end, float ra, float rb, float time) {
        if (rb <= 0.0F) {
            return;
        }
        BeamGeometry.cone(buffer, matrix, start, end, ra * 0.42F, rb * 0.42F, CORE, CORE[3], time, 1.0F, time * 0.030F, TILE_LENGTH);
        BeamGeometry.cone(buffer, matrix, start, end, ra, rb, SHELL, SHELL[3], time, 0.7F, -time * 0.018F, TILE_LENGTH);
        BeamGeometry.cone(buffer, matrix, start, end, ra * 1.12F, rb * 1.12F, RIM, RIM[3], time, 0.45F, time * 0.010F, TILE_LENGTH);
    }

    private PhyllosensBeamRenderer() {
    }
}
