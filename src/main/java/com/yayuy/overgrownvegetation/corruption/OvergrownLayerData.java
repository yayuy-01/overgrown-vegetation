package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.network.OvergrownLayerPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>どのマスが植物に覆われたか</b>の記録(2026-09-22)。
 *
 * <h2>なぜ記録するのか</h2>
 * <p>汚染の対応表に変換先が無いブロックのうち、<b>中身を持たないもの</b>(金床・砥石・作業台・
 * コンクリート・ガラスなど)は<b>置き換えない</b>。元のブロックをその場に残したまま、
 * <ul>
 *     <li>見た目に苔を被せ({@code client/OvergrownLayerRenderer})</li>
 *     <li>右クリックを受け付けなくする({@link OvergrownLayerInteraction})</li>
 * </ul>
 * この 2 つをやるには「このマスは覆われた」をどこかに持っていなければならない。
 * それがこの記録。<b>チャンクに付く {@code LongOpenHashSet}</b> で、チャンクと一緒に保存され、
 * クライアントへも送られる({@link OvergrownLayerPayload})。
 *
 * <h2>なぜ BlockEntity ではないのか</h2>
 * <p>一時は覆ったマスを全部「覆われたブロック」({@code OvergrownHuskBlock})へ差し替えていたが、
 * <b>装飾ブロックだらけの拠点を飲み込むと BlockEntity が数千個</b>になる。
 * 中身を持たないブロックには覚えることが何も無いので、<b>座標 1 つ(8 バイト)</b>で足りる ——
 * BlockEntity 1 個ぶんの NBT の 1/10 以下。
 *
 * <p>代わりに<b>レッドストーンで動く分は止まらない</b>(覆われたドアがレバーで開く、など)。
 * 自分で動くもの = {@link net.minecraft.world.level.block.entity.BlockEntity} を持つものは
 * 従来どおり {@code OvergrownHuskBlock} へ差し替えて完全に止める。
 *
 * <h2>古い記録は読むときに捨てる</h2>
 * <p>プレイヤーがそのマスのブロックを壊して別のものを置くと、記録だけが残る。
 * 壊したときに消して回るのは経路が多くて漏れるので、<b>読むときに「いまそこにあるブロックは
 * まだ覆われる資格があるか」を見直す</b>({@link #isCovered})。資格が無ければ覆われていない扱いにし、
 * サーバー側ではついでに記録も捨てる。
 */
public final class OvergrownLayerData {

    /** チャンクごとの「今 tick で新しく覆われたマス」。tick の終わりにまとめて送る。 */
    private static final Map<ChunkPos, LongOpenHashSet> PENDING = new HashMap<>();

    private OvergrownLayerData() {
    }

    // --- 読む ---

    /**
     * そのマスは覆われているか。<b>記録にあるだけでは足りない</b> ——
     * いまそこにあるブロックが覆われる資格を持っているかも見る({@link #canCover})。
     *
     * <p>サーバー側では、資格を失った記録をここで捨てる(掃除のためだけに別の経路を作らない)。
     */
    public static boolean isCovered(BlockGetter level, BlockPos pos) {
        if (!(level instanceof Level realLevel)) {
            return false;
        }
        ChunkAccess chunk = realLevel.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return false;
        }
        return isCovered(levelChunk, pos, realLevel.getBlockState(pos));
    }

    /** チャンクを既に持っているとき用({@code 描画はこちらを使う})。 */
    public static boolean isCovered(LevelChunk chunk, BlockPos pos, BlockState state) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || set.isEmpty() || !set.contains(pos.asLong())) {
            return false;
        }
        if (canCover(state)) {
            return true;
        }
        // 記録は残っているが、そこにあるブロックはもう覆われる資格が無い(壊して置き直された)。
        if (chunk.getLevel() != null && !chunk.getLevel().isClientSide) {
            set.remove(pos.asLong());
            chunk.setUnsaved(true);
        }
        return false;
    }

    /**
     * そのブロックは「覆われたまま残す」対象か。
     *
     * <p>中身を持つブロックはここでは扱わない —— 自分で動くので
     * {@code OvergrownHuskBlock} へ差し替えて止める必要がある。
     */
    public static boolean canCover(BlockState state) {
        if (state.isAir() || state.hasBlockEntity() || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (state.is(ModTags.CORRUPTED) || state.is(ModTags.CORRUPTION_IMMUNE)) {
            return false;
        }
        if (state.getBlock().defaultDestroyTime() < 0.0F) {
            return false;
        }
        return CorruptionRegistry.hasNoCorruptedForm(state);
    }

    // --- 書く(サーバー) ---

    /**
     * そのマスを覆ったことにする。
     *
     * @return 新しく覆ったら {@code true}(既に覆われていれば {@code false})
     */
    public static boolean cover(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        LongOpenHashSet set = chunk.getData(ModAttachments.OVERGROWN_LAYER.get());
        if (!set.add(pos.asLong())) {
            return false;
        }
        chunk.setUnsaved(true);
        PENDING.computeIfAbsent(chunk.getPos(), key -> new LongOpenHashSet()).add(pos.asLong());
        return true;
    }

    /** この tick で覆ったぶんを、そのチャンクを見ているプレイヤーへ送る。 */
    public static void flush(ServerLevel level) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Map.Entry<ChunkPos, LongOpenHashSet> entry : PENDING.entrySet()) {
            OvergrownLayerPayload payload =
                    new OvergrownLayerPayload(entry.getKey(), entry.getValue().toLongArray(), false);
            PacketDistributor.sendToPlayersTrackingChunk(level, entry.getKey(), payload);
        }
        PENDING.clear();
    }

    /** そのチャンクを見始めたプレイヤーへ、記録をまるごと送る。 */
    public static void sendAll(ServerPlayer player, LevelChunk chunk) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || set.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new OvergrownLayerPayload(chunk.getPos(), set.toLongArray(), true));
    }

    // --- 書く(クライアント) ---

    /** 届いた記録を取り込む。{@code replace} なら入れ替え、そうでなければ足す。 */
    public static void accept(LevelChunk chunk, long[] positions, boolean replace) {
        LongOpenHashSet set = chunk.getData(ModAttachments.OVERGROWN_LAYER.get());
        if (replace) {
            set.clear();
        }
        for (long packed : positions) {
            set.add(packed);
        }
    }

    /** 記録の件数(コマンドの表示用)。 */
    public static int size(LevelChunk chunk) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        return set == null ? 0 : set.size();
    }
}
