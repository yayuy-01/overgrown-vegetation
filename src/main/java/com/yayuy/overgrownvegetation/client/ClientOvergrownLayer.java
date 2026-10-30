package com.yayuy.overgrownvegetation.client;

import com.google.common.collect.MapMaker;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.network.OvergrownLayerPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongIterators;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 届いた「覆われたマス」の記録をクライアントのチャンクへ取り込む(2026-09-22)。
 *
 * <p>取り込んだら<b>そのチャンクを描き直させる</b> —— 苔はチャンクのメッシュに焼き込まれるので、
 * 記録だけ更新してもすぐには見た目が変わらない。
 *
 * <h2>描画用の写しも持つ(2026-09-24、軽量化)</h2>
 * <p>{@link OvergrownLayerRenderer} はワーカースレッドで描くので、メインスレッドが書き換える
 * 記録そのものは読ませない。代わりに<b>セクションごとの写し</b>({@link #coveredCells})を
 * メインスレッドで作って手渡す。覆われたマスが 1 つも無いセクションには描画を登録しないので、
 * 1 マスでも覆われたチャンクの全セクションを 16x16x16 で調べて回ることも無くなった。
 * 写しは記録と同じ中身(取り込みはここを通るものしか無い)で、苔の出方は変わらない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ClientOvergrownLayer {

    /**
     * 記録 → そのセクションごとの写し。触るのはメインスレッドだけ
     * (取り込みも {@code AddSectionGeometryEvent} もメインスレッドで来る)。
     *
     * <p><b>キーは記録そのもので、同一性で比べる</b>({@code weakKeys})。
     * {@code LongOpenHashSet} の equals は中身で比べるので、普通の Map のキーにはできない。
     * 弱参照なので、チャンクが捨てられて記録が要らなくなれば写しも一緒に消え、
     * 別のチャンク・別のワールドの記録と取り違えることも無い。
     */
    private static final Map<LongOpenHashSet, Snapshot> SNAPSHOTS = new MapMaker().weakKeys().makeMap();

    private ClientOvergrownLayer() {
    }

    public static void accept(OvergrownLayerPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // まだ届いていないチャンクぶんは捨てる。チャンクが届くときに
        // ChunkWatchEvent.Sent でまるごと送り直されるので取りこぼさない。
        if (!level.hasChunk(payload.chunk().x, payload.chunk().z)) {
            return;
        }
        LevelChunk chunk = level.getChunk(payload.chunk().x, payload.chunk().z);
        LongOpenHashSet before = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (payload.remove()) {
            // 剥がしたマス(2026-10-10 から。前は記録をまるごと送り直され、列のセクションを全部積み直していた)。
            // 写しは捨てて次に描くときに記録から作り直し、積み直すのは剥がしたマスのあるセクションだけ。
            if (before == null) {
                return;
            }
            for (long packed : payload.positions()) {
                before.remove(packed);
            }
            SNAPSHOTS.remove(before);
            markSectionsDirty(level, payload);
            return;
        }
        int sizeBefore = before == null ? 0 : before.size();
        OvergrownLayerData.accept(chunk, payload.positions(), payload.replace());
        updateSnapshot(before, sizeBefore, payload);
        if (payload.replace()) {
            // 入れ替え(チャンクが届いたとき)。消えた記録がどの高さにあったかまでは追わず、
            // これまでどおり列のセクションを全部積み直す(チャンクが届くときはどのみち全部積み直される)。
            for (int section = level.getMinSection(); section < level.getMaxSection(); section++) {
                Minecraft.getInstance().levelRenderer.setSectionDirty(payload.chunk().x, section, payload.chunk().z);
            }
            return;
        }
        // 足しただけのとき(汚染が広がっている最中は数 tick ごとに来る)は、足したマスのあるセクションだけ積み直す。
        markSectionsDirty(level, payload);
    }

    /**
     * 届いたマスのあるセクションだけ積み直させる(足した・剥がしたとき)。
     * 苔はそのマスのセクションのメッシュにしか入らず(OvergrownLayerRenderer はセクションの中のマスだけを描く)、
     * ほかのセクションのメッシュは記録を見ていないので、積み直しても同じものができるだけ。
     * 範囲外の高さは積み直しを頼まない(ViewArea は高さを剰余で折り返すので、別のセクションを汚してしまう)。
     */
    private static void markSectionsDirty(ClientLevel level, OvergrownLayerPayload payload) {
        boolean[] dirty = new boolean[level.getSectionsCount()];
        for (long packed : payload.positions()) {
            int index = level.getSectionIndexFromSectionY(SectionPos.blockToSectionCoord(BlockPos.getY(packed)));
            if (index >= 0 && index < dirty.length) {
                dirty[index] = true;
            }
        }
        for (int section = level.getMinSection(); section < level.getMaxSection(); section++) {
            if (dirty[level.getSectionIndexFromSectionY(section)]) {
                Minecraft.getInstance().levelRenderer.setSectionDirty(payload.chunk().x, section, payload.chunk().z);
            }
        }
    }

    /**
     * そのセクションの覆われたマス(描画用の写し)。1 つも無ければ {@code null}。<b>メインスレッドから呼ぶ。</b>
     *
     * <p>中身は 4096 ビットの並び({@code long[64]})で、ビット {@code (dy << 8) | (dz << 4) | dx} が
     * セクションの中の位置。<b>小さいビットから読むと y → z → x の順</b>になる。
     * 返した配列は二度と書き換えないので、ワーカースレッドへそのまま渡してよい。
     */
    @Nullable
    public static long[] coveredCells(LevelChunk chunk, int sectionY) {
        LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
        if (set == null || set.isEmpty()) {
            return null;
        }
        Snapshot snapshot = SNAPSHOTS.get(set);
        // 件数が合わない = 写したあとに記録がよそで書き換わった。念のため記録から作り直す。
        if (snapshot == null || snapshot.size != set.size()) {
            snapshot = new Snapshot();
            snapshot.add(chunk.getPos(), set.iterator());
            snapshot.size = set.size();
            SNAPSHOTS.put(set, snapshot);
        }
        return snapshot.sections.get(sectionY);
    }

    /**
     * 取り込んだぶんを写しへ反映する。入れ替えなら捨てて、次に描くときに記録から作り直す。
     * 足しただけなら、足したマスのあるセクションだけ写し直す(記録全体をなめ直さない)。
     */
    private static void updateSnapshot(@Nullable LongOpenHashSet set, int sizeBefore, OvergrownLayerPayload payload) {
        if (set == null) {
            return; // 記録が今できたところ。写しはまだ無い(描くときに作る)。
        }
        Snapshot snapshot = SNAPSHOTS.get(set);
        if (snapshot == null) {
            return;
        }
        if (payload.replace() || snapshot.size != sizeBefore) {
            SNAPSHOTS.remove(set);
            return;
        }
        snapshot.add(payload.chunk(), LongIterators.wrap(payload.positions()));
        snapshot.size = set.size();
    }

    /** ワールドを抜けたら写しを捨てる(弱参照なのでいずれ消えるが、持っておく理由も無い)。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SNAPSHOTS.clear();
    }

    /**
     * 記録 1 つぶんの写し。セクションの Y → そのセクションの覆われたマス({@link #coveredCells} の形)。
     *
     * <p><b>一度手渡した配列は書き換えない</b> —— 描画はワーカースレッドで配列を読む。
     * マスを足すときは配列を写してから足し、差し替える。
     */
    private static final class Snapshot {

        final Int2ObjectOpenHashMap<long[]> sections = new Int2ObjectOpenHashMap<>();

        /** 写したときの記録の件数。 */
        int size;

        void add(ChunkPos chunk, LongIterator cells) {
            // この呼び出しで写した配列(まだ誰にも渡していないので、そのまま足してよい)。
            Int2ObjectOpenHashMap<long[]> copied = new Int2ObjectOpenHashMap<>();
            while (cells.hasNext()) {
                long packed = cells.nextLong();
                int x = BlockPos.getX(packed);
                int y = BlockPos.getY(packed);
                int z = BlockPos.getZ(packed);
                if (SectionPos.blockToSectionCoord(x) != chunk.x || SectionPos.blockToSectionCoord(z) != chunk.z) {
                    continue; // よそのチャンクのマス。このチャンクのどのセクションにも描かれない(以前も同じ)。
                }
                int sectionY = SectionPos.blockToSectionCoord(y);
                long[] bits = copied.get(sectionY);
                if (bits == null) {
                    long[] old = sections.get(sectionY);
                    bits = old == null ? new long[64] : old.clone();
                    copied.put(sectionY, bits);
                    sections.put(sectionY, bits);
                }
                int cell = (SectionPos.sectionRelative(y) << 8)
                        | (SectionPos.sectionRelative(z) << 4)
                        | SectionPos.sectionRelative(x);
                bits[cell >> 6] |= 1L << (cell & 63);
            }
        }
    }
}
