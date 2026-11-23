package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 【1.20.1 だけの物・G5 の助け手】NeoForge の {@code net.neoforged.neoforge.client.event.AddSectionGeometryEvent} と同じ形の物。
 * Forge 1.20.1 には地形のセクションの組み立てに描き足す口が無いので、チャンクの組み立てへの mixin(G7 か F が足す)から呼んでもらう。
 * {@link CoverRenderer}・{@link SporeDustRenderer} は本流のまま {@code @SubscribeEvent} で受ける(Forge のゲームのバスに投げる)。
 *
 * <h2>mixin との約束</h2>
 * <ol>
 *   <li>本スレッド: {@code ChunkRenderDispatcher.RenderChunk#createCompileTask(RenderRegionCache)} で
 *       {@link #collect(Level, BlockPos)}(level = {@code ChunkRenderDispatcher.this.level}、origin = {@code RenderChunk.this.origin} の写し)を呼び、
 *       返った一覧を作った {@code RebuildTask} に持たせる(空の一覧なら何も持たせない)。</li>
 *   <li>組み立てのスレッド: {@code RebuildTask#compile(float, float, float, ChunkBufferBuilderPack)} の、ブロックの繰り返しの後・
 *       半透明の並べ替え({@code set.contains(RenderType.translucent())})の前で、一覧の 1 つずつに
 *       {@code render(new SectionRenderingContext(type -> { BufferBuilder b = pack.builder(type); if (set.add(type)) RenderChunk.this.beginLayer(b); return b; }, region, posestack))} を呼ぶ。
 *       region は compile の中の {@code renderchunkregion}(null なら呼ばない)、posestack は compile の中の物(何も積んでいない = セクションの原点)。</li>
 * </ol>
 * Embeddium(Sodium の Forge 版)が入っていると、この口は呼ばれない(決定 9: 1.20.1 の初版は差し込みなし)。
 */
public class AddSectionGeometryEvent extends Event {

    private final Level level;
    private final BlockPos sectionOrigin;
    private final List<AdditionalSectionRenderer> renderers = new ArrayList<>();

    /** Forge のイベントバスが受け手の一覧を作るとき用(互換の層の tick のイベントと同じ)。 */
    public AddSectionGeometryEvent() {
        this(null, BlockPos.ZERO);
    }

    public AddSectionGeometryEvent(Level level, BlockPos sectionOrigin) {
        this.level = level;
        this.sectionOrigin = sectionOrigin;
    }

    /** mixin の入口(本スレッド)。登録された描き足しを返す。無ければ空の一覧(作り物を増やさない)。 */
    public static List<AdditionalSectionRenderer> collect(Level level, BlockPos sectionOrigin) {
        AddSectionGeometryEvent event = new AddSectionGeometryEvent(level, sectionOrigin.immutable());
        MinecraftForge.EVENT_BUS.post(event);
        return event.renderers.isEmpty() ? List.of() : List.copyOf(event.renderers);
    }

    public Level getLevel() {
        return this.level;
    }

    public BlockPos getSectionOrigin() {
        return this.sectionOrigin;
    }

    public void addRenderer(AdditionalSectionRenderer renderer) {
        this.renderers.add(renderer);
    }

    @FunctionalInterface
    public interface AdditionalSectionRenderer {
        void render(SectionRenderingContext context);
    }

    /** 組み立てのスレッドで渡す物(NeoForge と同じ 3 つの口)。 */
    public static final class SectionRenderingContext {
        private final Function<RenderType, VertexConsumer> getOrCreateChunkBuffer;
        private final BlockAndTintGetter region;
        private final PoseStack poseStack;

        public SectionRenderingContext(Function<RenderType, VertexConsumer> getOrCreateChunkBuffer, BlockAndTintGetter region, PoseStack poseStack) {
            this.getOrCreateChunkBuffer = getOrCreateChunkBuffer;
            this.region = region;
            this.poseStack = poseStack;
        }

        public VertexConsumer getOrCreateChunkBuffer(RenderType type) {
            return this.getOrCreateChunkBuffer.apply(type);
        }

        public PoseStack getPoseStack() {
            return this.poseStack;
        }

        public BlockAndTintGetter getRegion() {
            return this.region;
        }
    }
}
