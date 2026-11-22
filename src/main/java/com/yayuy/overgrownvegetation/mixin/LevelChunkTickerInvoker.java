package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link LevelChunk} が持つブロックエンティティのティッカー登録(private)を外から呼ぶための口。
 *
 * <p>使うのは蔦の副アンカー({@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity})だけ。
 * {@code getTicker} はブロックの状態しか見られないので、主アンカーと副アンカーを見分けられず、
 * 何もしない副アンカー(蔦のブロックエンティティの 9 割)まで毎 tick ティッカーが回っていた。
 * 副アンカーは最初の 1 tick で自分のティッカーを外す({@link #overgrown_vegetation$removeBlockEntityTicker})。
 * 外したあとで主アンカーになった(= NBT を書き換えられた)ときだけ付け直す
 * ({@link #overgrown_vegetation$updateBlockEntityTicker})。
 *
 * <p>どちらもバニラ自身が使っている入口で、{@code LevelChunk} 自身に宣言されているメソッド
 * (親クラスのものではない)。
 * <ul>
 *     <li>{@code removeBlockEntityTicker}: 包みのティッカーを {@code NULL_TICKER} に差し替えるだけ。
 *         {@code Level#tickBlockEntities} が次の周回でリストから取り除く。自分の tick の最中に呼んでも安全
 *         (走っているリストには触らない)。</li>
 *     <li>{@code updateBlockEntityTicker}: {@code getTicker} を引き直して登録する(同じ座標にまだ包みがあれば
 *         差し替えるだけ)。</li>
 * </ul>
 */
@Mixin(LevelChunk.class)
public interface LevelChunkTickerInvoker {

    @Invoker("removeBlockEntityTicker")
    void overgrown_vegetation$removeBlockEntityTicker(BlockPos pos);

    /** 元は {@code private <T extends BlockEntity> void updateBlockEntityTicker(T)}(消去後は {@code (BlockEntity)V})。 */
    @Invoker("updateBlockEntityTicker")
    void overgrown_vegetation$updateBlockEntityTicker(BlockEntity blockEntity);
}
