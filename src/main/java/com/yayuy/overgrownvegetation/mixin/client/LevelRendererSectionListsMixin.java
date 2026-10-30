package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.FogCullBacklog;
import com.yayuy.overgrownvegetation.client.SectionFrustumCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 毎フレーム見える区画の一覧を何度も回す所を、要る区画だけの短い一覧に替える(2026-10-20)。描く物・順番はバニラと同じ。
 *
 * <p>見える区画の一覧({@code visibleSections})には空気だけの区画も入っていて、ジャングルの描画距離 16 では中身のある区画の数倍ある。
 * バニラはこれを 1 フレームに、組み立てを頼む所で 1 回(区画ごとに {@code SectionPos} を作ってから汚れを見る)・地形の層ごとに 1 回ずつ(5 層)・
 * ブロックエンティティで 1 回回し、そのたびに区画 → 組み立て済みの中身 → 層の集合とばらばらの所を読む。
 * <ul>
 *     <li>組み立てを頼む所({@code compileSections})には、汚れた区画だけの一覧を渡す。バニラも汚れていない区画は何もしないので同じ。</li>
 *     <li>組み立てと送り込みが済んだ後({@code compileSections} の終わり)に、層かブロックエンティティを 1 つでも持つ区画だけの一覧を作り、
 *     地形の層({@code renderSectionLayer}。半透明の並べ直しも)とブロックエンティティの描画に渡す。空の区画はバニラでも描かれず、
 *     並べ直しも「半透明を持たない」で何もせず抜ける(待っている並べ直しは組み立て直しのときに取り消される)。</li>
 * </ul>
 * 一覧はそのフレームの {@code compileSections} の後だけ使う({@code renderLevel} の始めで捨てる)。ほかの所から呼ばれたときはバニラの一覧のまま。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSectionListsMixin {

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrownvegetation$dirty = new ObjectArrayList<>();
    @Unique
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrownvegetation$drawn = new ObjectArrayList<>();
    @Unique
    private boolean overgrownvegetation$dirtyValid;
    @Unique
    private boolean overgrownvegetation$drawnValid;

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void overgrownvegetation$forgetLists(CallbackInfo ci) {
        this.overgrownvegetation$dirtyValid = false;
        this.overgrownvegetation$drawnValid = false;
    }

    @Inject(method = "compileSections", at = @At("HEAD"), require = 0)
    private void overgrownvegetation$collectDirty(Camera camera, CallbackInfo ci) {
        this.overgrownvegetation$dirty.clear();
        this.overgrownvegetation$dirtyValid = SectionFrustumCache.shortLists;
        if (!this.overgrownvegetation$dirtyValid) {
            return;
        }
        for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
            if (section.isDirty()) {
                this.overgrownvegetation$dirty.add(section);
            }
        }
        // 霧で描かずにいる区画も、変わったものを数個ずつ(見える区画の後ろ = 後回し)
        FogCullBacklog.addDirty(this.overgrownvegetation$dirty, this.sectionRenderDispatcher == null ? 0 : this.sectionRenderDispatcher.getToBatchCount());
    }

    @Redirect(method = "compileSections", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"), require = 0)
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrownvegetation$dirtyOnly(LevelRenderer self) {
        return this.overgrownvegetation$dirtyValid ? this.overgrownvegetation$dirty : this.visibleSections;
    }

    @Inject(method = "compileSections", at = @At("TAIL"), require = 0)
    private void overgrownvegetation$collectDrawn(Camera camera, CallbackInfo ci) {
        this.overgrownvegetation$dirtyValid = false;
        this.overgrownvegetation$drawn.clear();
        this.overgrownvegetation$drawnValid = SectionFrustumCache.shortLists;
        if (!this.overgrownvegetation$drawnValid) {
            return;
        }
        // 描く物を持つかは区画自身に書いてある(RenderSectionDrawableMixin。中身をたどらない)
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = this.visibleSections;
        for (int i = 0, n = visible.size(); i < n; i++) {
            SectionRenderDispatcher.RenderSection section = visible.get(i);
            if (((SectionFrustumCache.Drawable) section).overgrown_vegetation$drawable()) {
                this.overgrownvegetation$drawn.add(section);
            }
        }
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"), require = 0)
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrownvegetation$drawnLayers(LevelRenderer self) {
        return this.overgrownvegetation$drawnValid ? this.overgrownvegetation$drawn : this.visibleSections;
    }

    @Redirect(method = "renderLevel", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections:Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"), require = 0)
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrownvegetation$drawnBlockEntities(LevelRenderer self) {
        return this.overgrownvegetation$drawnValid ? this.overgrownvegetation$drawn : this.visibleSections;
    }
}
