package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.Feature;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalRecipes;
import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import com.yayuy.overgrownvegetation.gear.attach.MouthClothRecipe;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import com.yayuy.overgrownvegetation.registry.ModResearch;
import com.yayuy.overgrownvegetation.research.Research;
import com.yayuy.overgrownvegetation.research.ResearchData;
import com.yayuy.overgrownvegetation.worldevent.WorldEventData;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import com.yayuy.overgrownvegetation.worldevent.WorldEventSchedule;
import com.yayuy.overgrownvegetation.worldevent.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 実験的要素を 1 つずつのスイッチにした(2026-11-23 夜 ユーザー決定)ことの確かめ。
 * <ul>
 *     <li>前の「実験的要素オン」の保存はネザーのオンとして読み、ほかの要素は値なし(既定オフ)。</li>
 *     <li>ワールドイベント: オフの間は日程が回らず、オンにした日から数え直す。コマンドで起こした物はオフでも最後まで回る。</li>
 *     <li>蠢く壁: オフなら自然には起きない。</li>
 *     <li>研究: オフなら研究の道具は作れず置けず、研究で開く作り方は最初から作れる。オンなら今まで通り。</li>
 *     <li>防具の付け物: オフなら台と付け物(口覆いの布以外)は作れず台は置けない。作業台の「兜 + 布」はいつでも作れる。</li>
 * </ul>
 * GameTest のサーバーは値が無ければどれもオン。値を書いたテストは最後に必ず消す。値を書くので、テストごとに batch を分ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ExperimentalFeaturesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onExperimentalFeaturesGameTests(RegisterGameTestsEvent event) {
            event.register(ExperimentalFeaturesGameTests.class);
        }

        private Registration() {
        }
    }

    private ExperimentalFeaturesGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";

    private static void check(boolean ok, String message) {
        if (!ok) throw new GameTestAssertException(message);
    }

    private static FakePlayer player(ServerLevel level) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_experimental_test".getBytes()), "ov_experimental_test"));
    }

    /** {@code item} を {@code rel} の上に置こうとする(置けたら true)。持ち物の数が減らないこと(置けなかったら手に戻る)も見る。 */
    private static boolean tryPlace(GameTestHelper helper, ItemStack item, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        FakePlayer p = player(level);
        p.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
        BlockPos ground = helper.absolutePos(rel);
        level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(ground.above(), Blocks.AIR.defaultBlockState());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        p.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
        boolean placed = !level.getBlockState(ground.above()).isAir();
        if (!placed) {
            check(p.getItemInHand(InteractionHand.MAIN_HAND).getCount() == item.getCount(), "置けなかったのに手の物が減った: " + item);
        }
        level.setBlockAndUpdate(ground.above(), Blocks.AIR.defaultBlockState());
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return placed;
    }

    /** タブに並べる口が並べた物(サーバーの thread では、このワールドの値を見る)。 */
    private static List<ItemStack> tab(java.util.function.Consumer<net.minecraft.world.item.CreativeModeTab.Output> accept) {
        List<ItemStack> out = new java.util.ArrayList<>();
        accept.accept((stack, visibility) -> out.add(stack));
        return out;
    }

    // ---------------------------------------------------------------- 保存

    /** 前の 1 つのスイッチ({@code experimental})の「オン」は、ネザーのオンとして読む。ほかの要素は値なし。 */
    @GameTest(template = TEMPLATE, batch = "experimental_load", timeoutTicks = 20)
    public static void oldExperimentalOnReadsAsNether(GameTestHelper helper) {
        CompoundTag values = new CompoundTag();
        values.putDouble("experimental", 1.0D);
        CompoundTag tag = new CompoundTag();
        tag.put("overrides", values);
        DifficultyValues v = DifficultyValues.load(tag);
        check(v.override(DifficultySetting.EXPERIMENTAL_NETHER).orElse(0) >= 0.5D, "前の「実験的要素オン」がネザーのオンにならない");
        for (Feature f : Feature.values()) {
            if (f != Feature.NETHER) check(v.override(f.setting()).isEmpty(), f + " に値が入った(既定オフのはず)");
            check(f.setting().defaultValue() == 0.0D, f + " の既定がオフでない");
            check(f.setting().section() == DifficultySetting.Section.EXPERIMENTAL, f + " が実験的要素の節に無い");
        }
        // 書いて読み戻しても同じ(新しい名前で保存される)
        DifficultyValues back = DifficultyValues.load(v.save());
        check(back.override(DifficultySetting.EXPERIMENTAL_NETHER).orElse(0) >= 0.5D, "保存し直すとネザーのオンが消えた");
        helper.succeed();
    }

    /** 1 つずつ切り替えられ、ほかの要素に響かない。送る値(ビット)も合う。 */
    @GameTest(template = TEMPLATE, batch = "experimental_each", timeoutTicks = 20)
    public static void switchesAreIndependent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            for (Feature f : Feature.values()) check(ExperimentalFeatures.enabled(server, f), "GameTest のサーバーで " + f + " が最初からオフ");
            for (Feature off : Feature.values()) {
                ExperimentalFeatures.set(server, off, false);
                for (Feature f : Feature.values()) {
                    check(ExperimentalFeatures.enabled(server, f) == (f != off), off + " だけをオフにしたのに " + f + " が " + ExperimentalFeatures.enabled(server, f));
                }
                check(ExperimentalFeatures.mask(server) == ((1 << Feature.values().length) - 1 & ~(1 << off.ordinal())), "送る値が合わない: " + off);
                ExperimentalFeatures.reset(server, off);
            }
            ExperimentalFeatures.setAll(server, false);
            check(ExperimentalFeatures.mask(server) == 0, "まとめてオフにしても残った");
        } finally {
            for (Feature f : Feature.values()) ExperimentalFeatures.reset(server, f);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- ワールドイベント

    /** オフの間は日程が回らない(くじの日も合図も溜まらない)。オンにした日から間を数え直す。コマンドで起こした物はオフでも最後まで回る。 */
    @GameTest(template = TEMPLATE, batch = "experimental_events", timeoutTicks = 20)
    public static void worldEventsOffDoNotDraw(GameTestHelper helper) {
        WorldEventSchedule s = new WorldEventSchedule();
        int day = WorldEventSchedule.DAY;
        // オフのまま 60 日、フェーズも上がる
        for (long t = 0; t < 60L * day; t += 100) {
            check(s.dormant(t, (int) Math.min(10, t / (6L * day))), "何も起きていないのに日程を回そうとした");
            check(s.active() == null && s.state() == WorldEventSchedule.State.IDLE, "オフなのに起きた・待っている: " + s.state());
        }
        check(s.omens().isEmpty(), "オフの間にフェーズの合図が溜まった");
        // オンに戻す: その日から間を数え、すぐには起きない(過ぎた日・溜まった合図を一度に出さない)
        WorldEventSchedule.Env env = new WorldEventSchedule.Env() {
            @Override
            public int phase() {
                return 9; // オフの間に最後に見たフェーズと同じ
            }

            @Override
            public boolean raining() {
                return false;
            }

            @Override
            public double fog(long t) {
                return 0.0D;
            }
        };
        long back = 60L * day;
        s.step(back, 12345L, com.yayuy.overgrownvegetation.data.DesignData.worldEvents(), env);
        check(s.nextDueDay() != WorldEventSchedule.NONE && s.nextDueDay() > WorldEventSchedule.day(back),
                "オンに戻した日に次のくじの日が決まらない・その日のうちに引く: " + s.nextDueDay());
        check(s.active() == null && s.omens().isEmpty(), "オンに戻した途端に起きた・合図が出た");
        // コマンドで起こした物はオフでも止めない
        s.forceStart(WorldEventKind.values()[0], 60L * day, 0.0D, 1000);
        check(!s.dormant(60L * day + 10, 10), "起こした物があるのに日程を止めた");
        check(s.active() == WorldEventKind.values()[0], "起こした物が消えた");
        helper.succeed();
    }

    /** サーバーの tick で: オフなら日程の印(次のくじの日)が付かず、/ov event と同じ口で起こした物は動く。 */
    @GameTest(template = TEMPLATE, batch = "experimental_events_server", timeoutTicks = 60)
    public static void worldEventsOffOnServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        WorldEventSchedule s = WorldEventData.get(server).schedule();
        ExperimentalFeatures.set(server, Feature.WORLD_EVENTS, false);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    if (s.state() == WorldEventSchedule.State.IDLE && s.active() == null) {
                        check(s.nextDueDay() == WorldEventSchedule.NONE, "オフなのに次のくじの日が決まっている: " + s.nextDueDay());
                    }
                    WorldEvents.startNow(server, WorldEventKind.values()[0], 0.0D, 200);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    WorldEventKind active = WorldEvents.active(server);
                    WorldEventSchedule.State st = s.state();
                    s.stop(server.overworld().getDayTime(), WorldEvents.seed(server), com.yayuy.overgrownvegetation.data.DesignData.worldEvents());
                    ExperimentalFeatures.reset(server, Feature.WORLD_EVENTS);
                    check(active == WorldEventKind.values()[0] && st == WorldEventSchedule.State.ACTIVE, "オフのときにコマンドの口で起こした物が動かない: " + active);
                })
                .thenIdle(3)
                .thenExecute(() -> check(s.nextDueDay() != WorldEventSchedule.NONE || s.state() != WorldEventSchedule.State.IDLE,
                        "オンに戻しても日程が始まらない"))
                .thenSucceed();
    }

    // ---------------------------------------------------------------- 蠢く壁

    /** オフなら自然には起きない(条件の口が閉じる)。 */
    @GameTest(template = TEMPLATE, batch = "experimental_wall", timeoutTicks = 20)
    public static void creepingWallOffDoesNotStart(GameTestHelper helper) {
        ServerLevel ow = helper.getLevel().getServer().overworld();
        MinecraftServer server = helper.getLevel().getServer();
        int minPhase = com.yayuy.overgrownvegetation.wall.WallTuning.naturalMinPhase;
        long later = ow.getGameTime() + 1_000_000L; // 前に起きた時刻からの休みを越える
        try {
            com.yayuy.overgrownvegetation.wall.WallTuning.naturalMinPhase = 0; // フェーズの条件を外して、スイッチだけを比べる
            check(com.yayuy.overgrownvegetation.wall.CreepingWallNatural.allowed(ow, later),
                    "オン(とフェーズ 0 から)でも世界の側の条件が閉じている(ピースフル・動いている壁がないか): " + ow.getDifficulty());
            ExperimentalFeatures.set(server, Feature.CREEPING_WALL, false);
            check(!com.yayuy.overgrownvegetation.wall.CreepingWallNatural.allowed(ow, later),
                    "オフなのに蠢く壁が自然に起きうる");
        } finally {
            com.yayuy.overgrownvegetation.wall.WallTuning.naturalMinPhase = minPhase;
            ExperimentalFeatures.reset(server, Feature.CREEPING_WALL);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 研究

    private static final ResourceLocation WITHER_DRAUGHT = OvergrownVegetation.id("wither_draught");
    private static final ResourceLocation RESEARCH_BENCH = OvergrownVegetation.id("research_bench");
    private static final ResourceLocation RESEARCH_LENS = OvergrownVegetation.id("research_lens");

    /** オフ: 研究の道具は作れず置けず、研究で開く作り方は開く。オン: 今まで通り(研究台は作れる・研究で開く物は閉じている)。 */
    @GameTest(template = TEMPLATE, batch = "experimental_research", timeoutTicks = 40)
    public static void researchOffUnlocksRecipesAndHidesTools(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Research.cancel(server, null);
        ResearchData.get(server).clearAll();
        try {
            // オン(GameTest の既定)
            check(!ExperimentalRecipes.locked(server, RESEARCH_BENCH), "オンなのに研究台が作れない");
            check(ExperimentalRecipes.locked(server, WITHER_DRAUGHT), "オンで研究前なのに枯らし薬が作れる");
            check(ExperimentalRecipes.locked(server, RESEARCH_LENS), "オンで研究前なのに拡大鏡が作れる");
            check(tryPlace(helper, new ItemStack(ModResearch.BENCH_ITEM.get()), new BlockPos(1, 1, 1)), "オンなのに研究台を置けない");
            // オフ
            ExperimentalFeatures.set(server, Feature.RESEARCH, false);
            check(!ExperimentalRecipes.locked(server, WITHER_DRAUGHT), "オフなのに研究で開く作り方(枯らし薬)が閉じている");
            check(ExperimentalRecipes.locked(server, RESEARCH_BENCH), "オフなのに研究台が作れる");
            check(ExperimentalRecipes.locked(server, RESEARCH_LENS), "オフなのに拡大鏡が作れる");
            check(ExperimentalRecipes.locked(server, OvergrownVegetation.id("ley_resonator")), "オフなのに共鳴器が作れる");
            check(!tryPlace(helper, new ItemStack(ModResearch.BENCH_ITEM.get()), new BlockPos(3, 1, 3)), "オフなのに研究台を置けた");
            check(!tryPlace(helper, new ItemStack(ModResearch.LENS_ITEM.get()), new BlockPos(5, 1, 5)), "オフなのに拡大鏡を置けた");
            check(tab(ModResearch::acceptItems).isEmpty(), "オフなのにタブに研究の道具が並ぶ");
            // 研究で開く作り方の実物がレシピにある(名前違いで黙って効かないのを防ぐ)
            for (ResourceLocation id : List.of(WITHER_DRAUGHT, RESEARCH_BENCH, RESEARCH_LENS, OvergrownVegetation.id("ley_resonator"))) {
                check(server.getRecipeManager().byKey(id).isPresent(), "作り方が無い: " + id);
            }
        } finally {
            ExperimentalFeatures.reset(server, Feature.RESEARCH);
            ResearchData.get(server).clearAll();
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 防具の付け物

    /** オフ: 台と付け物(口覆いの布以外)は作れず台は置けない。布はいつでも作れる。オン: 作れる・置ける。 */
    @GameTest(template = TEMPLATE, batch = "experimental_attachments", timeoutTicks = 40)
    public static void attachmentsOffLocksBenchAndAttachments(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<String> gated = List.of("attachment_bench", "storage_belt", "bark_plate", "conduit_sash");
        try {
            for (String id : gated) {
                check(server.getRecipeManager().byKey(OvergrownVegetation.id(id)).isPresent(), "作り方が無い: " + id);
                check(!ExperimentalRecipes.locked(server, OvergrownVegetation.id(id)), "オンなのに作れない: " + id);
            }
            check(tryPlace(helper, new ItemStack(ModArmorAttach.BENCH_ITEM.get()), new BlockPos(1, 1, 1)), "オンなのに付け物の台を置けない");
            check(tab(ModArmorAttach::acceptItems).size() == 5, "オンのタブに付け物が全部並ばない");
            ExperimentalFeatures.set(server, Feature.ARMOR_ATTACHMENTS, false);
            for (String id : gated) check(ExperimentalRecipes.locked(server, OvergrownVegetation.id(id)), "オフなのに作れる: " + id);
            check(!ExperimentalRecipes.locked(server, OvergrownVegetation.id("mouth_cloth")), "オフで口覆いの布が作れない");
            check(!ExperimentalRecipes.locked(server, OvergrownVegetation.id("mouth_cloth_helmet")), "オフで兜 + 布が作れない");
            check(!tryPlace(helper, new ItemStack(ModArmorAttach.BENCH_ITEM.get()), new BlockPos(3, 1, 3)), "オフなのに付け物の台を置けた");
            List<ItemStack> shown = tab(ModArmorAttach::acceptItems);
            check(shown.size() == 1 && shown.get(0).is(ModArmorAttach.MOUTH_CLOTH.get()), "オフのタブに口覆いの布だけが並ばない: " + shown);
        } finally {
            ExperimentalFeatures.reset(server, Feature.ARMOR_ATTACHMENTS);
        }
        helper.succeed();
    }

    /** 作業台の「兜 + 口覆いの布 → 布の付いた兜」。オフのワールドでも作れる。布の無い兜・ほかの物が混ざると作れない。 */
    /** 1.20.1: 1.21 の CraftingInput.of(幅, 高さ, 中身) の代わり(作業台の格子。器の画面は持たない仮の物)。 */
    private static CraftingContainer grid(int width, int height, List<ItemStack> items) {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(net.minecraft.world.entity.player.Player player) {
                return true;
            }
        };
        TransientCraftingContainer c = new TransientCraftingContainer(menu, width, height);
        for (int i = 0; i < items.size(); i++) c.setItem(i, items.get(i));
        return c;
    }

    @GameTest(template = TEMPLATE, batch = "experimental_cloth", timeoutTicks = 20)
    public static void helmetPlusClothInCraftingTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ItemStack cloth = new ItemStack(ModArmorAttach.MOUTH_CLOTH.get());
        try {
            ExperimentalFeatures.set(server, Feature.ARMOR_ATTACHMENTS, false);
            CraftingContainer in = grid(3, 3, List.of(
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                    ItemStack.EMPTY, new ItemStack(Items.IRON_HELMET), ItemStack.EMPTY,
                    ItemStack.EMPTY, cloth.copy(), ItemStack.EMPTY));
            Optional<CraftingRecipe> found = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, level);
            check(found.isPresent() && found.get() instanceof MouthClothRecipe, "兜 + 布の作り方が見つからない: " + found);
            check(!ExperimentalRecipes.locked(server, found.get().getId()), "オフで兜 + 布が閉じている");
            ItemStack out = found.get().assemble(in, level.registryAccess());
            check(out.is(Items.IRON_HELMET) && ArmorAttachments.hasMouthCloth(out), "布の付いた兜にならない: " + out);
            // 外し方: 布の付いた兜を 1 つだけ置く → 布の無い兜、布は格子に残る
            CraftingContainer alone = grid(1, 1, List.of(out.copy()));
            Optional<CraftingRecipe> off = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, alone, level);
            check(off.isPresent() && off.get() instanceof MouthClothRecipe, "布の付いた兜 1 つで外す作り方が見つからない: " + off);
            ItemStack bare = off.get().assemble(alone, level.registryAccess());
            check(bare.is(Items.IRON_HELMET) && !ArmorAttachments.hasMouthCloth(bare), "外しても布が残っている: " + bare);
            check(off.get().getRemainingItems(alone).get(0).is(ModArmorAttach.MOUTH_CLOTH.get()), "外した布が格子に残らない");
            check(server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                    grid(1, 1, List.of(new ItemStack(Items.IRON_HELMET))), level).isEmpty(), "布の無い兜 1 つで何かができた");
            // オンでも同じ作り方が使える(台でも付けられる)
            ExperimentalFeatures.reset(server, Feature.ARMOR_ATTACHMENTS);
            check(!ExperimentalRecipes.locked(server, found.get().getId()), "オンで兜 + 布が閉じている");
            // 2 つ目は付かない(同じ物は 1 つ)
            CraftingContainer again = grid(2, 1, List.of(out, cloth.copy()));
            check(server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, again, level).isEmpty(), "布の付いた兜にもう 1 枚付いた");
            // 胸当て・ほかの物が混ざるとだめ
            check(server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                    grid(2, 1, List.of(new ItemStack(Items.IRON_CHESTPLATE), cloth.copy())), level).isEmpty(), "胸当てに布が付いた");
            check(server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                    grid(3, 1, List.of(new ItemStack(Items.IRON_HELMET), cloth.copy(), new ItemStack(Items.STICK))), level).isEmpty(),
                    "ほかの物が混ざっても作れた");
        } finally {
            ExperimentalFeatures.reset(server, Feature.ARMOR_ATTACHMENTS);
        }
        helper.succeed();
    }
}
