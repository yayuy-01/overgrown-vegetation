package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.LazySectionCopy;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 区画を組み立てる前のチャンクの写し({@code RenderChunk})を、作る時に全部写さず、要る高さだけ後から写す(2026-10-20)。
 * 理由と流れは {@link LazySectionCopy}。描画のスレッドだけで写す(バニラと同じ)。
 *
 * <p>一覧はバニラどおり作る時に全部の長さで作り(写さない区画は null = 空気)、後からその場で {@code set} する。一覧を差し替える作りだと、
 * 先に組み立てを始めた別のスレッドが中身の届いていない新しい一覧を見て、区画が空(穴)になりうる。その場で埋めるなら、
 * どのスレッドも自分の区画のまわり(組み立てを頼む前に埋めた所)しか読まないので、ほかの番号への書き込みとぶつからない。
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderChunk")
public abstract class RenderChunkLazyCopyMixin implements LazySectionCopy.Copier {

    @Shadow
    @Final
    private List<PalettedContainer<BlockState>> sections;

    @Shadow
    @Final
    LevelChunk wrapped;

    /** 写しを遅らせたか。遅らせていなければバニラのまま全部写してある。 */
    @Unique
    private boolean overgrown_vegetation$deferred;
    /** 写した区画(区画の番号ごと)。 */
    @Unique
    private boolean[] overgrown_vegetation$copied;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;copy()Lnet/minecraft/world/level/chunk/PalettedContainer;"), require = 0)
    private PalettedContainer<BlockState> overgrown_vegetation$deferCopy(PalettedContainer<BlockState> states) {
        if (LazySectionCopy.deferOnCreate()) {
            this.overgrown_vegetation$deferred = true;
            return null;
        }
        return states.copy();
    }

    @Override
    public void overgrown_vegetation$ensureSections(int minSectionY, int maxSectionY) {
        List<PalettedContainer<BlockState>> list = this.sections;
        if (!this.overgrown_vegetation$deferred || list == null) {
            return;
        }
        LevelChunkSection[] src = this.wrapped.getSections();
        int n = Math.min(src.length, list.size());
        boolean[] copied = this.overgrown_vegetation$copied;
        if (copied == null) {
            copied = new boolean[n];
            this.overgrown_vegetation$copied = copied;
        }
        int lo = Math.max(0, this.wrapped.getSectionIndexFromSectionY(minSectionY));
        int hi = Math.min(Math.min(n, copied.length) - 1, this.wrapped.getSectionIndexFromSectionY(maxSectionY));
        for (int i = lo; i <= hi; i++) {
            if (!copied[i]) {
                LevelChunkSection section = src[i];
                list.set(i, section.hasOnlyAir() ? null : section.getStates().copy());
                copied[i] = true;
            }
        }
    }
}
