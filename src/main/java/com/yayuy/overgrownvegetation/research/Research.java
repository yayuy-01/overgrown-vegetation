package com.yayuy.overgrownvegetation.research;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.ResearchStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>研究台と研究ツリー</b>の決まり(2026-11-17 強くなる道 決定 20・22・23)。
 *
 * <ul>
 *     <li>節の状態: 開いた / 開ける(前提がすべて開いた)/ まだ。</li>
 *     <li>研究を始める: 節が「開ける」・ほかの研究が走っていない・その段の器具が研究台のそばにある・標本が持ち物にそろっている。
 *     標本は持ち物から取り、やめたときのために覚えておく(選んで押すだけ。決定 22)。</li>
 *     <li>進み: 研究台が地脈の力を {@code fe_per_tick} ずつ食いながら {@code ticks} tick で開く({@link ResearchBenchBlockEntity})。</li>
 *     <li>やめる: 標本を返す。使った地脈の力と時間は戻らない(決定 23)。</li>
 *     <li>開いた節の {@code unlocks} の作り方は作業台で作れるようになる({@code mixin.CraftingMenuResearchLockMixin})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Research {

    private Research() {
    }

    public enum State { DONE, AVAILABLE, LOCKED }

    public static State state(ResearchTree tree, java.util.function.Predicate<String> isDone, String id) {
        if (isDone.test(id)) return State.DONE;
        ResearchTree.Node n = tree.node(id);
        if (n == null) return State.LOCKED;
        return n.requires().stream().allMatch(isDone) ? State.AVAILABLE : State.LOCKED;
    }

    public static State state(MinecraftServer server, String id) {
        ResearchTree tree = ResearchTree.get();
        ResearchData data = ResearchData.get(server);
        return state(tree, n -> data.isDone(tree, n), id);
    }

    // ---- 作り方の鍵 ----

    /** この作り方が研究で閉じているか(開く節があり、まだ開いていない)。 */
    public static boolean recipeLocked(MinecraftServer server, ResourceLocation recipe) {
        ResearchTree tree = ResearchTree.get();
        String node = tree.lockedBy(recipe);
        return node != null && !ResearchData.get(server).isDone(tree, node);
    }

    // ---- 器具 ----

    /** その段の器具がすべて研究台から {@code instrument_radius} 以内にあるか(決定 23)。 */
    public static boolean instrumentsPresent(Level level, BlockPos bench, int tier) {
        ResearchTree tree = ResearchTree.get();
        List<ResourceLocation> need = tree.tier(tier).instruments();
        if (need.isEmpty()) return true;
        int r = (int) Math.ceil(tree.instrumentRadius());
        double r2 = tree.instrumentRadius() * tree.instrumentRadius();
        for (ResourceLocation id : need) {
            Block b = BuiltInRegistries.BLOCK.get(id);
            boolean found = false;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r && !found; dx++)
                for (int dy = -r; dy <= r && !found; dy++)
                    for (int dz = -r; dz <= r && !found; dz++) {
                        if (dx * dx + dy * dy + dz * dz > r2) continue;
                        p.setWithOffset(bench, dx, dy, dz);
                        if (level.getBlockState(p).is(b)) found = true;
                    }
            if (!found) return false;
        }
        return true;
    }

    /** 段 2・段 3 の器具がそろっているかの印(1 = 段 2、2 = 段 3)。画面に出す。 */
    public static int instrumentMask(Level level, BlockPos bench) {
        int m = 0;
        if (instrumentsPresent(level, bench, 2)) m |= 1;
        if (instrumentsPresent(level, bench, 3)) m |= 2;
        return m;
    }

    // ---- 標本 ----

    public static int count(Player player, Item item) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static List<ItemStack> take(Player player, Item item, int count) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && count > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int k = Math.min(count, s.getCount());
            out.add(s.split(k));
            count -= k;
        }
        inv.setChanged();
        return out;
    }

    /** 始められない理由(始められるなら null)。 */
    public static @Nullable Component whyNot(ServerLevel level, BlockPos bench, Player player, String nodeId) {
        ResearchTree tree = ResearchTree.get();
        ResearchData data = ResearchData.get(level.getServer());
        ResearchTree.Node n = tree.node(nodeId);
        if (n == null || n.isRoot()) return Component.translatable("research.overgrown_vegetation.why.unknown");
        if (data.active() != null) return Component.translatable("research.overgrown_vegetation.why.busy");
        State s = state(tree, id -> data.isDone(tree, id), nodeId);
        if (s == State.DONE) return Component.translatable("research.overgrown_vegetation.why.done");
        if (s == State.LOCKED) return Component.translatable("research.overgrown_vegetation.why.requires");
        if (!n.specimensExist()) return Component.translatable("research.overgrown_vegetation.why.no_specimen");
        if (!instrumentsPresent(level, bench, n.instrumentTier())) return Component.translatable("research.overgrown_vegetation.why.instruments");
        for (ResearchTree.Specimen sp : n.specimens()) {
            int have = count(player, sp.asItem());
            if (have < sp.count()) {
                return Component.translatable("research.overgrown_vegetation.why.short", sp.asItem().getDescription(), sp.count() - have);
            }
        }
        return null;
    }

    /** 研究を始める。始めたら null、始められなければ理由。 */
    public static @Nullable Component start(ServerPlayer player, BlockPos bench, String nodeId) {
        ServerLevel level = player.serverLevel();
        Component why = whyNot(level, bench, player, nodeId);
        if (why != null) return why;
        ResearchTree.Node n = ResearchTree.get().node(nodeId);
        List<ItemStack> paid = new ArrayList<>();
        for (ResearchTree.Specimen sp : n.specimens()) paid.addAll(take(player, sp.asItem(), sp.count()));
        ResearchData data = ResearchData.get(level.getServer());
        data.setActive(new ResearchData.Active(nodeId, GlobalPos.of(level.dimension(), bench), paid, 0));
        if (level.getBlockEntity(bench) instanceof ResearchBenchBlockEntity be) be.resetClock();
        broadcastState(level.getServer());
        return null;
    }

    /** 今の研究をやめ、標本を {@code player} に返す(null なら研究台の上に落とす)。決定 23。 */
    public static boolean cancel(MinecraftServer server, @Nullable ServerPlayer player) {
        ResearchData data = ResearchData.get(server);
        ResearchData.Active a = data.active();
        if (a == null) return false;
        data.setActive(null);
        ServerLevel benchLevel = server.getLevel(a.bench.dimension());
        for (ItemStack s : a.paid) {
            ItemStack c = s.copy();
            if (player != null) player.getInventory().add(c);
            if (c.isEmpty()) continue;
            if (player != null) {
                player.drop(c, false);
            } else if (benchLevel != null) {
                BlockPos p = a.bench.pos();
                Containers.dropItemStack(benchLevel, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, c);
            }
        }
        broadcastState(server);
        return true;
    }

    /** 今の研究が終わった: 節を開き、作り方を覚えさせ、みんなに知らせる。 */
    static void complete(MinecraftServer server) {
        ResearchData data = ResearchData.get(server);
        ResearchData.Active a = data.active();
        if (a == null) return;
        data.setActive(null);
        open(server, a.node, true);
    }

    /** 節を開く(開発用の命令からも)。 */
    public static void open(MinecraftServer server, String nodeId, boolean announce) {
        ResearchTree tree = ResearchTree.get();
        ResearchTree.Node n = tree.node(nodeId);
        if (n == null) return;
        ResearchData.get(server).markDone(nodeId);
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (ResourceLocation id : n.unlocks()) server.getRecipeManager().byKey(id).ifPresent(recipes::add);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!recipes.isEmpty()) p.awardRecipes(recipes);
            if (announce) {
                p.sendSystemMessage(Component.translatable("research.overgrown_vegetation.done", nodeName(nodeId)));
            }
        }
        broadcastState(server);
    }

    public static Component nodeName(String id) {
        return Component.translatable("research.overgrown_vegetation." + id + ".name");
    }

    // ---- 画面を開いている人へ送る ----

    private static final Map<UUID, GlobalPos> VIEWERS = new HashMap<>();
    private static final double VIEW_RANGE_SQ = 10 * 10;

    /** 研究台を開いた(右クリック)。 */
    public static void openScreen(ServerPlayer player, BlockPos bench) {
        VIEWERS.put(player.getUUID(), GlobalPos.of(player.level().dimension(), bench));
        send(player, bench, true);
    }

    public static void closeScreen(ServerPlayer player) {
        VIEWERS.remove(player.getUUID());
    }

    static void send(ServerPlayer player, BlockPos bench, boolean open) {
        ServerLevel level = player.serverLevel();
        ResearchTree tree = ResearchTree.get();
        ResearchData data = ResearchData.get(level.getServer());
        ResearchData.Active a = data.active();
        int energy = 0;
        if (level.getBlockEntity(bench) instanceof ResearchBenchBlockEntity be) energy = be.energy();
        boolean here = a != null && a.bench.equals(GlobalPos.of(level.dimension(), bench));
        PacketDistributor.sendToPlayer(player, new ResearchStatePayload(
                open, bench, open ? tree.json() : "", new ArrayList<>(data.done()),
                a == null ? "" : a.node, a == null ? 0 : a.progress, here,
                energy, tree.benchBuffer(), instrumentMask(level, bench)));
    }

    /** 画面を開いている人全員へ今の状態を送る(始めた・やめた・開いた とき)。 */
    public static void broadcastState(MinecraftServer server) {
        for (Map.Entry<UUID, GlobalPos> e : VIEWERS.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null && p.level().dimension().equals(e.getValue().dimension())) send(p, e.getValue().pos(), false);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 10 != 0 || VIEWERS.isEmpty()) return;
        Iterator<Map.Entry<UUID, GlobalPos>> it = VIEWERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, GlobalPos> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.level().dimension().equals(e.getValue().dimension())
                    || p.distanceToSqr(e.getValue().pos().getCenter()) > VIEW_RANGE_SQ
                    || !(p.level().getBlockEntity(e.getValue().pos()) instanceof ResearchBenchBlockEntity)) {
                it.remove();
                continue;
            }
            send(p, e.getValue().pos(), false);
        }
    }

    /** GameTest 用: 画面を開いている人を忘れる。 */
    static Collection<GlobalPos> viewers() {
        return VIEWERS.values();
    }
}
