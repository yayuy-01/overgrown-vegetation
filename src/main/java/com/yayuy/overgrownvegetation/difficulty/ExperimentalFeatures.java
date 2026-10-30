package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import com.yayuy.overgrownvegetation.network.ExperimentalFeaturesPayload;
import com.yayuy.overgrownvegetation.purify.PurifyRegistry;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 実験的要素(2026-10-09、ユーザー指定)。「設定画面に『実験的要素』の項目を用意し、オンにしたときだけ出るように。オフなら同盟軍の基地が
 * 自然にできず、関連するアイテムや NPC も基地が出ないので触れない。火炎放射器や銃などが未完成なので、アップデートの際にまだ触らせたくない」。
 *
 * <p>値はワールドごと({@link DifficultySetting#EXPERIMENTAL}。Overgrown Vegetation の設定の画面。{@code /ov difficulty} は 10-10 に削除)、
 * <b>既定はオフ</b>。<b>GameTest のサーバーだけは値が無ければオン</b>(同盟軍・浄化のテストがそのまま通るように。オフの確かめは
 * {@code ExperimentalGameTests} が値を書いて同じ tick で戻す)。
 *
 * <p><b>オフのワールドで止めるもの</b>(今の実験的要素 = 同盟軍と、10-10 ユーザー指定で足したパワードアーマー・因子・装飾品):
 * <ul>
 *     <li>世界生成の基地 —— 新しく生まれない({@code AlliedBaseStructure#findGenerationPoint})。{@code /locate} で基地だけを探すとすぐ
 *     「見つからない」({@code mixin/ChunkGeneratorLocateExperimentalMixin}。バニラのままだと半径 100 の輪を最後まで回ってサーバーが約 108 秒止まり、
 *     しかも調べた候補を「無い」と覚えるので、オンにした後の {@code /locate} まで外れた)。</li>
 *     <li>研究ノード —— 開くレシピの成果物が全部 {@link #isExperimentalItem 実験的要素のアイテム}のノード(舗装・妨害灯・防壁・
 *     基地の備品・浄化)はツリーに送らず、開放も受け付けない({@link ResearchLogic})。</li>
 *     <li>レシピ —— 成果物が実験的要素のアイテムなら、研究を開放していても作れない({@link #locksRecipe})。</li>
 *     <li>クリエイティブタブ —— 実験的要素のアイテムを出さない(下の {@link Tabs})。</li>
 *     <li>火炎放射器 —— 持っていても使えない({@code purify/FlamethrowerItem})。</li>
 * </ul>
 * <b>オフでも止めないもの</b>: <b>火による浄化</b>(汚染された地面・覆われたブロックが火で元のブロック・燻る灰に変わる、焼けた荒地、木が燃え落ちる)。
 * 10-09 に Claude の判断でオフの間は止めたが、10-10 にユーザー指定「炎の要素は隔離しないように」で外した。同じ日に、ハイヴノードのまわり(水平 512)では
 * 火が燃え広がらないようにした({@code purify/HiveFireGuard})。
 * <b>オフにしても消さないもの</b>: すでに生まれた基地・兵士・持っているアイテム(着ているパワードアーマー・付けた装飾品の効き目も止めない)。
 *
 * <p><b>同盟軍のアイテムを足したら {@link #items()} にも足す</b>(パワードアーマーまわりも同じ。研究ノード「パワードアーマー作成テーブル」「因子の抽出」「装飾品・拡張パーツ」は
 * 開くレシピが全部ここに入るので、オフのワールドではツリーから消える。着ている・持っている物の効き目は止めない)(足し忘れるとオフのワールドでも作れる・タブに出る)。
 */
public final class ExperimentalFeatures {

    /** 世界生成の作業場(と {@code /locate})が読む写し。SavedData は本体のスレッドでしか読めないので、ここだけ写しを使う。 */
    private static volatile boolean worldgen;
    /** 最後にプレイヤーへ知らせた値(変わったときだけ知らせ直す)。 */
    private static boolean announced;
    /** クライアントが知っている値(サーバーから {@link ExperimentalFeaturesPayload} で届く)。 */
    private static volatile boolean client;
    @Nullable
    private static volatile Set<Item> items;

    // ------------------------------------------------------------------ 読む口

    /** このワールドで実験的要素がオンか(本体のスレッドから。値が無ければオフ、GameTest のサーバーだけはオン)。 */
    public static boolean enabled(MinecraftServer server) {
        OptionalDouble v = WorldDifficultyData.get(server).override(DifficultySetting.EXPERIMENTAL);
        return v.isPresent() ? v.getAsDouble() >= 0.5D : server instanceof GameTestServer;
    }

    /** サーバーならそのワールドの値、クライアントならサーバーから届いた値。 */
    public static boolean enabled(Level level) {
        if (level.isClientSide()) {
            return client;
        }
        MinecraftServer server = level.getServer();
        return server != null && enabled(server);
    }

    /** 世界生成の作業場から読む値(オーバーワールドの読み込みと {@link #refresh} が書く)。 */
    public static boolean worldgenEnabled() {
        return worldgen;
    }

    /** 実験的要素のアイテムか(同盟軍の建材・管理端末・持ち物・スポーンエッグ・火炎放射器・パワードアーマーまわり)。 */
    public static boolean isExperimentalItem(Item item) {
        return items().contains(item);
    }

    /** レシピの成果物が実験的要素のアイテムか(レシピが無ければ false)。 */
    public static boolean isExperimentalRecipe(Level level, ResourceLocation recipeId) {
        return level.getRecipeManager().byKey(recipeId)
                .map(holder -> isExperimentalItem(holder.value().getResultItem(level.registryAccess()).getItem()))
                .orElse(false);
    }

    /** オフのワールドで封印するレシピか(成果物が実験的要素のアイテム。研究を開放していても封印する)。 */
    public static boolean locksRecipe(Level level, ResourceLocation recipeId) {
        return !enabled(level) && isExperimentalRecipe(level, recipeId);
    }

    /** 研究ノードが実験的要素だけを開くか(開くレシピが 1 つ以上あり、全部の成果物が実験的要素のアイテム)。 */
    public static boolean isExperimentalNode(Level level, ResearchTable.Node node) {
        if (node.unlocks().isEmpty()) {
            return false;
        }
        for (String raw : node.unlocks()) {
            ResourceLocation id = raw.contains(":")
                    ? ResourceLocation.tryParse(raw)
                    : ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, raw);
            if (id == null || !isExperimentalRecipe(level, id)) {
                return false;
            }
        }
        return true;
    }

    /** オフのワールドで研究ツリーから隠すノードか。 */
    public static boolean hidesNode(MinecraftServer server, ResearchTable.Node node) {
        return !enabled(server) && isExperimentalNode(server.overworld(), node);
    }

    // ------------------------------------------------------------------ 書く口

    /** このワールドの値を書き、写しとプレイヤーへの知らせを合わせる(テスト用。画面とコマンドは値を書いた後に {@link #refresh})。 */
    public static void set(MinecraftServer server, boolean on) {
        WorldDifficultyData.get(server).set(DifficultySetting.EXPERIMENTAL, on ? 1.0D : 0.0D);
        refresh(server);
    }

    /**
     * 値を書いた後に呼ぶ(設定の画面の「完了」・テスト)。世界生成の写しを合わせ、変わっていれば
     * 全員に知らせ(クリエイティブタブを作り直させる)、研究ツリーを送り直す(隠すノードが変わる)。
     */
    public static void refresh(MinecraftServer server) {
        boolean now = enabled(server);
        worldgen = now;
        if (now == announced) {
            return;
        }
        announced = now;
        OvergrownVegetation.LOGGER.info("実験的要素を{}にした", now ? "オン" : "オフ");
        PacketDistributor.sendToAllPlayers(new ExperimentalFeaturesPayload(now));
        ResearchLogic.syncAll(server);
    }

    /** クライアント: サーバーから届いた値を覚える。変わったら true(クリエイティブタブを作り直す)。 */
    public static boolean setClient(boolean on) {
        boolean changed = client != on;
        client = on;
        return changed;
    }

    // ------------------------------------------------------------------ 同盟軍のアイテム

    /** 実験的要素のアイテム(登録が済んでから最初に聞かれたときに作る)。 */
    private static Set<Item> items() {
        Set<Item> set = items;
        if (set == null) {
            set = new HashSet<>();
            for (var block : BaseBlocks.all()) {
                set.add(block.get().asItem());
            }
            set.add(BaseBlocks.BASE_TERMINAL.get().asItem());
            set.add(AllyItems.HK416.get());
            set.add(AllyItems.REPAIR_MATERIAL.get());
            set.add(AllyItems.MILITARY_SCRIP.get());
            for (SoldierKind kind : SoldierKind.values()) {
                set.add(AllyEntities.spawnEgg(kind));
            }
            // 火炎放射器だけ(燻る灰は 10-10 に外した —— 火による浄化はオフのワールドでも起きるので、燻る灰はどのワールドにもできる)
            set.add(PurifyRegistry.FLAMETHROWER.get());
            // パワードアーマー・因子・装飾品(2026-10-10 ユーザー指定「実験的要素にパワードアーマーや因子、装飾品を入れてほしい」)。
            // 作成テーブル・抽出設備・本体 4 部位・設計図・カートリッジ・因子 5 種・装飾品 4 種と拡張パーツ 2 種
            set.add(ModItems.POWERED_ARMOR_TABLE_ITEM.get());
            set.add(ModItems.FACTOR_EXTRACTOR_ITEM.get());
            set.add(ModItems.POWERED_ARMOR_HELMET.get());
            set.add(ModItems.POWERED_ARMOR_CHESTPLATE.get());
            set.add(ModItems.POWERED_ARMOR_LEGGINGS.get());
            set.add(ModItems.POWERED_ARMOR_BOOTS.get());
            set.add(ModItems.POWERED_ARMOR_BLUEPRINT.get());
            set.add(ModItems.CARTRIDGE.get());
            set.add(ModItems.FACTOR_IMPACT.get());
            set.add(ModItems.FACTOR_PIERCE.get());
            set.add(ModItems.FACTOR_BLAST.get());
            set.add(ModItems.FACTOR_TOXIN.get());
            set.add(ModItems.FACTOR_SPORE.get());
            set.add(ModItems.NIGHT_VISION_ACCESSORY.get());
            set.add(ModItems.ENEMY_SENSE_ACCESSORY.get());
            set.add(ModItems.SWIFT_ACCESSORY.get());
            set.add(ModItems.CUSHION_ACCESSORY.get());
            set.add(ModItems.MINING_EXTENSION.get());
            set.add(ModItems.ANCHOR_EXTENSION.get());
            set = Set.copyOf(set);
            items = set;
        }
        return set;
    }

    /** {@link #items()} の写し(テスト用)。 */
    public static Set<Item> experimentalItems() {
        return items();
    }

    // ------------------------------------------------------------------ サーバーの出入り

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {

        /**
         * オーバーワールドの読み込み(スポーンのチャンクを作るより前 —— {@code MinecraftServer#createLevels} の中)で写しを作る。
         * ワールド作成画面で決めた値は、このときにはもう {@code data/} に書かれている。
         */
        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
                boolean now = enabled(level.getServer());
                worldgen = now;
                announced = now;
            }
        }

        @SubscribeEvent
        public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                PacketDistributor.sendToPlayer(player, new ExperimentalFeaturesPayload(enabled(player.server)));
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            worldgen = false;
            announced = false;
        }

        private Events() {
        }
    }

    /**
     * クリエイティブタブ: オフなら実験的要素のアイテムを外す(足すのは各クラスの {@code BuildCreativeModeTabContentsEvent} のまま。
     * いちばん後に走って全タブから外す)。クライアントはサーバーから届いた値を見る。値が変わるとタブの作り直しは
     * {@code client/difficulty/ClientExperimentalFeatures} が頼む。GameTest(専用サーバーでタブを組む)はサーバーの値を見る。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Tabs {

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onBuildTab(BuildCreativeModeTabContentsEvent event) {
            if (shownInTabs()) {
                return;
            }
            for (ItemStack stack : List.copyOf(event.getParentEntries())) {
                if (isExperimentalItem(stack.getItem())) {
                    event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
                }
            }
            for (ItemStack stack : List.copyOf(event.getSearchEntries())) {
                if (isExperimentalItem(stack.getItem())) {
                    event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
                }
            }
        }

        /** 実験的要素のアイテムを今タブに出してよいか(CreativeTabOrder が、連れてきてよいかを見るのにも使う)。 */
        public static boolean shownInTabs() {
            if (FMLEnvironment.dist.isClient()) {
                return client;
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server != null && enabled(server);
        }

        private Tabs() {
        }
    }

    private ExperimentalFeatures() {
    }
}
