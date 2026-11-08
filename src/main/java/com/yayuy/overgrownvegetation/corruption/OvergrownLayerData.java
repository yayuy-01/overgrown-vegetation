package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
 *     <li>見た目に苔を被せ({@code client/CoverRenderer})</li>
 *     <li>右クリックを受け付けなくする・扉やボタンを時々動かなくする({@link CoveredInteractions})</li>
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
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownLayerData {

    /**
     * チャンクごとの「今 tick で新しく覆われたマス」。tick の終わりにまとめて送る。
     *
     * <p><b>2026-11-08 に直した</b>(ユーザー 11-07「洞窟の壁で覆いが素の見た目に見える件は直す」。菌糸の染み下り・壁を這う汚染が flush より後に覆い、
     * 洞窟の壁が素の砂利・石のまま見えていた)。キーを次元 → チャンクにして、flush は自分の次元だけ送る。以下は直す前の記録:
     *
     * <p><b>既知の食い違い(2026-09-24 軽量化の監査 1351 / 512(a)、ユーザー確認待ちで未修正)</b>:
     * キーがチャンク座標だけなので、ある次元の {@link #flush} より後(同じ LevelTickEvent.Post に
     * ぶら下がる汚染の処理。クラス間の順番は登録順任せ)に覆った分は、次に tick した<b>別の次元</b>の
     * flush で、同じ座標のチャンクを見ているプレイヤーへ送られる。本来の次元のプレイヤーには
     * チャンクを送り直すまで苔が見えない。次元をキーに足せば直る
     * ({@code Map<ResourceKey<Level>, Map<ChunkPos, LongOpenHashSet>>} にして flush は自分の次元だけ送る)が、
     * 苔の見え方がプレイヤーに分かる形で変わる(軽くはならない)ので、軽量化の作業では元のままにした。
     * 直すならユーザーの了承を取り、DEV_NOTES に「見た目が変わる修正」として書く。
     */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, Map<ChunkPos, LongOpenHashSet>> PENDING = new HashMap<>();

    /**
     * ワールドを閉じたら送り残しを捨てる(キーはチャンク座標だけなので、残すと次に開いたワールドで
     * 最初の送信に混ざり、同じ座標のチャンクで覆われていないマスに苔が見えることがある。起動し直したときと同じ状態に戻す)。
     * {@link #PENDING} はサーバーのスレッドしか触らない(クライアントが触るのはチャンクに付いた記録だけ)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        REMOVED.clear();
    }

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
     * {@code OvergrownHuskBlock} へ差し替えて止める必要がある。例外はスポナー・試練のスポナー(2026-10-09、ユーザー指定):
     * 覆っても止めず、湧く Mob を寄生体に替える({@link SpawnerCorruption})。
     */
    public static boolean canCover(BlockState state) {
        if (state.isAir() || state.hasBlockEntity() && !SpawnerCorruption.isSpawner(state)
                || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (state.is(ModTags.CORRUPTED) || state.is(ModTags.CORRUPTION_IMMUNE)) {
            return false;
        }
        if (state.getBlock().defaultDestroyTime() < 0.0F) {
            return false;
        }
        if (MeltedIce.isIce(state)) {
            // 氷は覆わずに溶かす(2026-10-16)。前に覆った氷の記録はここで資格を失い、苔が消えて次に汚染が来たとき溶ける。
            return false;
        }
        // 建築タブのブロック(板材・原木・階段など対応表にあるものも)は 2026-10-30 から元のまま記録で覆う(CorruptionRegistry#coversInPlace)
        return CorruptionRegistry.hasNoCorruptedForm(state) || CorruptionRegistry.coversInPlace(state);
    }

    // --- 書く(サーバー) ---

    /** 直前の {@link #cover} で新しく覆ったマス(サーバーのスレッドだけが触る)。{@link #takeNewlyCovered} が読んで消す。 */
    private static long lastNewlyCovered = Long.MIN_VALUE;

    /**
     * 直前の {@link #cover} が {@code pos} を<b>新しく</b>覆ったか(読んだら忘れる)。汚染の呼び出し元が、置き換えの無かった
     * ({@code CorruptionRegistry#noChange})マスで「今覆ったのか、前から覆われていたのか」を安く聞くため(2026-10-10)。
     * 覆ったマスは {@code #corrupted} に入らないので何度でも訪ね直される —— 前から覆われていたマスで植物を生やし直すと生い茂る。
     */
    public static boolean takeNewlyCovered(BlockPos pos) {
        boolean hit = lastNewlyCovered == pos.asLong();
        lastNewlyCovered = Long.MIN_VALUE;
        return hit;
    }

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
        PENDING.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(chunk.getPos(), key -> new LongOpenHashSet()).add(pos.asLong());
        lastNewlyCovered = pos.asLong();
        relightIfLit(level, pos);
        SpawnerCorruption.onCovered(level, pos); // スポナーなら湧く Mob を寄生体へ(2026-10-09)
        CoverCreepData.onCovered(level, pos); // 這い上がる苔の記録は覆われたら要らない(2026-11-01)
        CoveredSandToGrass.onCovered(level, pos); // 砂・砂利は時間がたつと草ブロックへ(2026-11-01 ユーザー指定)
        return true;
    }

    /**
     * 覆った・剥がした光源の明かりを計算し直させる(覆った光源は 4 割の明るさ、{@code covered/CoveredLight}、2026-10-05)。
     * ブロックは置き換えないので、言わないと明かりは前のまま残る。結果はいつもの明かりの更新でクライアントへ届く。
     */
    private static void relightIfLit(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).getLightEmission() > 0) {
            level.getChunkSource().getLightEngine().checkBlock(pos);
        }
    }

    /**
     * そのマスの覆いを剥がす(同盟軍の工兵の整備、順 2 の着手順 4、2026-10-04)。記録から消し、そのチャンクを見ているプレイヤーへ
     * 剥がしたマスを「消す」で送る(tick の終わりにまとめて。2026-10-10 までは記録をまるごと送り直していた)。読み込まれていないチャンクは触らない。
     *
     * @return 覆われていて剥がしたら {@code true}
     */
    public static boolean uncover(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return false;
        }
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || !set.remove(pos.asLong())) {
            return false;
        }
        chunk.setUnsaved(true);
        Map<ChunkPos, LongOpenHashSet> pendingOfLevel = PENDING.get(level.dimension());
        LongOpenHashSet pending = pendingOfLevel == null ? null : pendingOfLevel.get(chunk.getPos());
        if (pending != null) {
            pending.remove(pos.asLong());
        }
        // 剥がしたマスは tick の終わりにチャンクごとまとめて「消す」で送る(2026-10-10 から。前は記録をまるごと送り直し、
        // クライアントが列のセクションを全部積み直していた —— 火事・工兵の整備のたびに起きていた)
        REMOVED.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(chunk.getPos(), k -> new LongOpenHashSet()).add(pos.asLong());
        relightIfLit(level, pos);
        SpawnerCorruption.onUncovered(level, pos); // 汚染したスポナーなら元の Mob へ戻す(2026-10-09)
        CoveredSandToGrass.onUncovered(level, pos); // 剥がした砂・砂利は草ブロックにならない
        CoverCreepData.onUncovered(level, pos); // 剥がしたマスはしばらく這い上がる苔が付かない(2026-11-03 ユーザー指定)
        return true;
    }

    /** 覆いを剥がしたチャンク(次元ごと)。tick の終わりに記録をまるごと送り直す。 */
    /** 剥がしたマス(次元 → チャンク → マス)。tick の終わりの {@link #flush} で、足した分より先に送る(同じ tick に剥がして覆い直したマスは覆われたまま届く)。 */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, Map<ChunkPos, LongOpenHashSet>> REMOVED = new HashMap<>();

    /** この tick で覆ったぶんを、そのチャンクを見ているプレイヤーへ送る。 */
    public static void flush(ServerLevel level) {
        Map<ChunkPos, LongOpenHashSet> removed = REMOVED.remove(level.dimension());
        if (removed != null) {
            for (Map.Entry<ChunkPos, LongOpenHashSet> entry : removed.entrySet()) {
                ChunkPos cp = entry.getKey();
                if (level.getChunkSource().getChunkNow(cp.x, cp.z) == null) {
                    continue; // もう読み込まれていない(見ているプレイヤーもいない。見始めたらまるごと届く)
                }
                PacketDistributor.sendToPlayersTrackingChunk(level, cp,
                        new OvergrownLayerPayload(cp, entry.getValue().toLongArray(), false, true));
            }
        }
        Map<ChunkPos, LongOpenHashSet> pending = PENDING.remove(level.dimension()); // 自分の次元の分だけ(2026-11-08)
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (Map.Entry<ChunkPos, LongOpenHashSet> entry : pending.entrySet()) {
            if (level.getChunkSource().getChunkNow(entry.getKey().x, entry.getKey().z) == null) {
                continue; // もう読み込まれていない(見始めたらまるごと届く)
            }
            OvergrownLayerPayload payload =
                    new OvergrownLayerPayload(entry.getKey(), entry.getValue().toLongArray(), false);
            PacketDistributor.sendToPlayersTrackingChunk(level, entry.getKey(), payload);
        }
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

    // --- 汚染済みの判定(2026-10-30 夜、覆いの作り直しの続き) ---

    /**
     * そのマスは<b>汚染済み</b>か —— {@code #corrupted} のブロックか、記録で覆われたマス。2026-10-30 に建築タブのブロックも
     * 置き換えずに覆うようになった(covered_ を退避)ので、タグだけ見ると汚染された建物を汚染済みに数えない
     * (メモリ covered-cells-not-corrupted-tag)。数え・「もう汚染済みなら飛ばす」はこちらを使う。サーバーの本スレッドから。
     */
    public static boolean isCorruptedCell(BlockGetter level, BlockPos pos, BlockState state) {
        return state.is(ModTags.CORRUPTED) || !state.isAir() && isCovered(level, pos);
    }

    /** チャンクを既に持っているとき用({@link #isCorruptedCell(BlockGetter, BlockPos, BlockState)} と同じ答え)。 */
    public static boolean isCorruptedCell(LevelChunk chunk, BlockPos pos, BlockState state) {
        return state.is(ModTags.CORRUPTED) || !state.isAir() && isCovered(chunk, pos, state);
    }

    /**
     * そのチャンクの区画(区画の y = {@code sectionY}、ブロックの y >> 4)に覆いの記録が 1 つでもあるか。パレットで区画を丸ごと飛ばす近道
     * ({@code BuildCorrosion})が、覆ったマスを見落とさないように。資格の見直しはしない(多めに「ある」に倒す)。
     */
    public static boolean hasAnyInSection(LevelChunk chunk, int sectionY) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (long packed : set) {
            if (BlockPos.getY(packed) >> 4 == sectionY) {
                return true;
            }
        }
        return false;
    }

    /**
     * 区画の中の覆いの記録の位置(区画の中の添え字 {@code (y << 8) | (z << 4) | x})を {@code out} へ流す。資格は {@code state} で見直す。
     * {@code BuildCorrosion} が 4096 マスの種類表へ「汚染済み」を書き足すため。
     */
    public static void forEachInSection(LevelChunk chunk, int sectionY, java.util.function.IntConsumer out) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || set.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long packed : set) {
            p.set(packed);
            if (p.getY() >> 4 == sectionY && canCover(chunk.getBlockState(p))) {
                out.accept(((p.getY() & 15) << 8) | ((p.getZ() & 15) << 4) | (p.getX() & 15));
            }
        }
    }
}
