package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * シャベルの右クリックで胞子の粉を払う(2026-10-23 胞子の場と風 第 2 段の追加。【決定】土ならしと同じ操作感・箒は作らない)。
 *
 * <p>粉のある上面(列のいちばん上のブロックの上面)をシャベルで右クリックすると、そのブロックを含む 4×4 ブロックの升に
 * 「払った」印を付け({@link ModAttachments#SWEPT_DUST})、チャンクの降り積もりを升の分(既定 1/16)だけ減らし、「胞子」を落とす。
 * 降り積もりが払う直前の値まで戻れば印は消える({@link #refresh})。粉の見た目(画面に粉を描く・払った升を描かない)は {@code client/SporeDustRenderer}(2026-10-30 に退避、10-31 に固い層で戻した)。
 * 「払った升でしばらく発芽しない」効果は無い(値を減らすだけ)。音・耐久はバニラの土ならしと同じ。
 * 払える升では土ならし(草 → 土の道)より先に払う(粉が無い・もう払った升では今までどおり土ならし)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeSweeping {

    /** 印の付いた読み込み中のチャンク(印を消すかどうかを場の更新ごとに見る)。 */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<Level>, it.unimi.dsi.fastutil.longs.LongOpenHashSet> MARKED = new java.util.HashMap<>();

    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet marked(Level level) {
        return MARKED.computeIfAbsent(level.dimension(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
    }

    private SporeSweeping() {
    }

    /** 印の付いたチャンクを読み込んだら見張りに入れる。 */
    @SubscribeEvent
    public static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && chunk.getData(ModAttachments.SWEPT_DUST) != 0) {
            marked(level).add(chunk.getPos().toLong());
        }
    }

    /**
     * 降り積もりが払う直前の値まで戻った・汚染された・粉が無くなったチャンクの印を消す(また粉が出る)。
     * 読み込まれていないチャンクは見張りから外す(読み込んだときに入れ直す)。場の更新ごと(SporeFieldServer・NetherSporeFieldServer が呼ぶ)。
     */
    static void refresh(ServerLevel level) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet set = marked(level);
        if (set.isEmpty()) return;
        it.unimi.dsi.fastutil.longs.LongIterator it = set.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = net.minecraft.world.level.ChunkPos.getX(key), cz = net.minecraft.world.level.ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                it.remove();
                continue;
            }
            SporeFieldServer.Cell c = SporeFieldServer.cell(level, cx, cz);
            if (c == null) continue; // 写しがまだ無い: 待つ
            // 「戻った」は払う直前の値を超えたとき(払った直後は写しがまだ古く同じ値なので、以上だとすぐ消えてしまう)
            if (dustLevel(c) <= 0 || c.dep() > chunk.getData(ModAttachments.SWEPT_DUST_REFILL)) {
                chunk.removeData(ModAttachments.SWEPT_DUST);
                chunk.removeData(ModAttachments.SWEPT_DUST_REFILL);
                chunk.setUnsaved(true);
                it.remove();
            }
        }
    }

    static void clear() {
        MARKED.clear();
    }

    /**
     * 粉の濃さ = 胞子計の「降り積もり %」(発芽のしきい値 = 100、0〜100 で頭打ち)。汚染済み・写しの無いチャンクは 0。
     * 2026-10-30 に粉の見た目の送信を退避したとき、ここへ移した(10-31 に戻した {@link SporeDustSync} もこれを使う)。
     */
    static int dustLevel(SporeFieldServer.Cell c) {
        if (c == null || c.stage() > 0) return 0;
        double threshold = DesignData.sporeField().field().germThreshold;
        return (int) Math.max(0, Math.min(100, Math.round(c.dep() / threshold * 100.0D)));
    }

    /** チャンクの中の升の番号(0〜15)。 */
    public static int square(BlockPos pos) {
        return ((pos.getZ() & 15) >> 2) * 4 + ((pos.getX() & 15) >> 2);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getFace() != Direction.UP) return;
        ItemStack stack = event.getItemStack();
        if (!stack.canPerformAction(ItemAbilities.SHOVEL_FLATTEN)) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (level.dimension() == Level.NETHER) {
            // ネザー(2026-10-23 F): 空洞の床ならどこでも(高さ地図は天井を指す)
            if (!com.yayuy.overgrownvegetation.corruption.NetherSurface.isOpen(level.getBlockState(pos.above()))) return;
        } else if (level.dimension() != Level.OVERWORLD
                || level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1 != pos.getY()) {
            return; // オーバーワールドは列のいちばん上だけ
        }
        if (level.isClientSide()) {
            // 画面側: 粉が見えている升なら土ならしの予測をさせずに腕を振るだけ(本当の処理はサーバー。2026-10-31 に粉の見た目と一緒に戻した)
            if (com.yayuy.overgrownvegetation.client.ClientSporeDust.hasDustAt(pos)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (!(level instanceof ServerLevel server) || !SporeFieldServer.enabled()) return;
        if (sweep(server, pos, event.getEntity(), stack, event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * その上面に粉を載せるか(画面の {@code client/ClientSporeDust} と同じ決まり): 台帳の過繁茂のチャンク・過繁茂か極相のバイオーム・
     * 汚染済み(#corrupted)か覆われた上面には載せない(2026-10-24)。
     */
    public static boolean dustSurface(Level level, LevelChunk chunk, BlockPos pos) {
        if (level instanceof ServerLevel sl && level.dimension() == Level.OVERWORLD
                && com.yayuy.overgrownvegetation.phase.PhaseData.get(sl).isOvergrownChunk(chunk.getPos())) return false;
        if (com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(pos.above()))) return false;
        net.minecraft.world.level.block.state.BlockState s = chunk.getBlockState(pos);
        return !s.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED)
                && !com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(chunk, pos, s);
    }

    /**
     * 払う。払えた(粉があり、その升がまだ払われていない)なら true。player が null ならテスト(耐久・音は無し)。
     */
    public static boolean sweep(ServerLevel level, BlockPos pos, Player player, ItemStack stack,
                                net.minecraft.world.entity.EquipmentSlot slot) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        SporeFieldServer.Cell c = SporeFieldServer.cell(level, cx, cz);
        if (dustLevel(c) <= 0) return false;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;
        if (!dustSurface(level, chunk, pos)) return false; // 汚染済みの地面・過繁茂のチャンクには粉が無い(2026-10-24)
        int bit = 1 << square(pos);
        int mask = chunk.getData(ModAttachments.SWEPT_DUST);
        if ((mask & bit) != 0) return false;
        SporeFieldTable t = DesignData.sporeField();
        chunk.setData(ModAttachments.SWEPT_DUST, mask | bit);
        marked(level).add(chunk.getPos().toLong());
        chunk.setData(ModAttachments.SWEPT_DUST_REFILL, (float) c.dep()); // ここまで戻ったら印を消す
        chunk.setUnsaved(true);
        if (level.dimension() == Level.NETHER) {
            int scx = cx, scz = cz;
            double share = t.sweepShare();
            NetherSporeFieldServer.INPUTS.add(f -> f.sweep(scx, scz, share));
        } else {
            SporeFieldServer.sweep(pos, t.sweepShare());
        }

        int drops = drops(c.dep() / t.field().germThreshold, t, level.random);
        if (drops > 0) Block.popResourceFromFace(level, pos, Direction.UP, new ItemStack(ModItems.SPORE.get(), drops));
        if (player != null) {
            level.playSound(null, pos, SoundEvents.SHOVEL_FLATTEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            stack.hurtAndBreak(1, player, slot);
        }
        return true;
    }

    /** 落とす「胞子」の数。濃い升(しきい値以上)は dropMin〜dropMax、薄い升は 比 × thinChance の確率で 1。 */
    static int drops(double ratio, SporeFieldTable t, RandomSource random) {
        if (ratio >= 1.0D) return t.sweepDropMin() + random.nextInt(t.sweepDropMax() - t.sweepDropMin() + 1);
        return random.nextDouble() < ratio * t.sweepThinChance() ? 1 : 0;
    }
}
