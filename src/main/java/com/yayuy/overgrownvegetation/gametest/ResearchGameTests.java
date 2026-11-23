package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModResearch;
import com.yayuy.overgrownvegetation.research.Research;
import com.yayuy.overgrownvegetation.research.ResearchBenchBlockEntity;
import com.yayuy.overgrownvegetation.research.ResearchData;
import com.yayuy.overgrownvegetation.research.ResearchTree;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 研究台と研究ツリー(2026-11-17 決定 20・22・23): 設計の中身がそろっているか(前提・標本・作り方の id)/ 前提・標本・器具が無いと始まらない /
 * 始めると標本を取り、やめると返す / 力が無いと進まず、離れていた間は経った分だけ・ためた力の分だけ進む / 開くと作り方の鍵が外れる。
 * 研究の進みは世界で 1 つなので、状態を触るテストは別々の組(batch)にして順に走らせる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ResearchGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterResearchGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(ResearchGameTests.class);
        }

        private Registration() {
        }
    }

    private ResearchGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";

    private static FakePlayer player(ServerLevel level) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_research_test".getBytes()), "ov_research_test"));
    }

    private static void reset(MinecraftServer server) {
        Research.cancel(server, null);
        ResearchData.get(server).clearAll(); // 開いた節をすべて閉じる(開発用の命令と同じ)
    }

    private static BlockPos placeBench(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 2, 3), ModResearch.BENCH.get().defaultBlockState());
        return helper.absolutePos(new BlockPos(3, 2, 3));
    }

    /** 設計の中身: 前提の節があり、段 1・段 2 の標本と絵の物がこの版にあり、開く作り方がレシピにある(tier12-gear の id と合っている)。 */
    @GameTest(template = TEMPLATE, batch = "research_data", timeoutTicks = 20)
    public static void treeDataIsConsistent(GameTestHelper helper) {
        ResearchTree tree = ResearchTree.get();
        if (tree.nodes().size() < 6) { // 11-21: 段 1〜3 の装備の節を消して 6 節(研究台・繊維・拡大鏡・枯らし薬・菌集鉱・共鳴器)。研究ツリーは作り直す
            helper.fail("節が足りない: " + tree.nodes().size());
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        for (ResearchTree.Node n : tree.nodes().values()) {
            for (String r : n.requires()) {
                if (tree.node(r) == null) {
                    helper.fail(n.id() + " の前提が無い: " + r);
                    return;
                }
            }
            if (n.tier() <= 2 && !n.specimensExist()) {
                helper.fail(n.id() + " の標本がこの版に無い");
                return;
            }
            if (!BuiltInRegistries.ITEM.containsKey(n.icon())) {
                helper.fail(n.id() + " の絵の物が無い: " + n.icon());
                return;
            }
            for (ResourceLocation id : n.unlocks()) {
                if (server.getRecipeManager().byKey(id).isEmpty()) {
                    helper.fail(n.id() + " が開く作り方が無い: " + id);
                    return;
                }
            }
            if (n.tier() >= 1 && (n.fePerTick() <= 0 || n.ticks() <= 0)) {
                helper.fail(n.id() + " の地脈の力か時間が 0");
                return;
            }
        }
        helper.succeed();
    }

    /** 前提が無いと始まらない・標本が足りないと始まらない・始めると取られる・やめると返る(決定 23)。 */
    @GameTest(template = TEMPLATE, batch = "research_start", timeoutTicks = 20)
    public static void startTakesSpecimensAndCancelReturnsThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        reset(server);
        BlockPos bench = placeBench(helper);
        FakePlayer p = player(level);
        p.getInventory().clearContent();
        ResearchTree.Node fiber = ResearchTree.get().node("t1_fiber");
        ResearchTree.Specimen sp = fiber.specimens().get(0);
        if (Research.whyNot(level, bench, p, "t2_lens") == null) {
            helper.fail("前提が無いのに始められた");
            return;
        }
        if (Research.whyNot(level, bench, p, "t1_fiber") == null) {
            helper.fail("標本が無いのに始められた");
            return;
        }
        p.getInventory().add(new ItemStack(sp.asItem(), sp.count() + 3));
        if (Research.start(p, bench, "t1_fiber") != null) {
            helper.fail("標本があるのに始められない");
            return;
        }
        if (Research.count(p, sp.asItem()) != 3 || ResearchData.get(server).active() == null) {
            helper.fail("標本が取られていない: " + Research.count(p, sp.asItem()));
            return;
        }
        if (Research.start(p, bench, "t2_lens") == null) {
            helper.fail("ほかの研究が走っているのに始められた");
            return;
        }
        Research.cancel(server, p);
        if (Research.count(p, sp.asItem()) != sp.count() + 3 || ResearchData.get(server).active() != null) {
            helper.fail("やめても標本が返らない: " + Research.count(p, sp.asItem()));
            return;
        }
        reset(server);
        helper.succeed();
    }

    /** 力が無いと進まない・離れていた間は経った分だけ、ためた力で払える分まで進む・終わると開いて作り方の鍵が外れる(決定 20・23)。 */
    @GameTest(template = TEMPLATE, batch = "research_progress", timeoutTicks = 40)
    public static void progressNeedsPowerAndCatchesUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        reset(server);
        BlockPos bench = placeBench(helper);
        if (!(level.getBlockEntity(bench) instanceof ResearchBenchBlockEntity be)) {
            helper.fail("研究台のブロックエンティティが無い");
            return;
        }
        FakePlayer p = player(level);
        p.getInventory().clearContent();
        Research.open(server, "t1_fiber", false); // 11-21: 山刀の節を消したので、作り方を開く節は拡大鏡の節(前提 = 繊維の節)で確かめる
        ResearchTree.Node knife = ResearchTree.get().node("t2_lens");
        ResourceLocation recipe = knife.unlocks().get(0);
        if (!Research.recipeLocked(server, recipe)) {
            helper.fail("開く前なのに作り方の鍵が無い: " + recipe);
            return;
        }
        for (ResearchTree.Specimen sp : knife.specimens()) p.getInventory().add(new ItemStack(sp.asItem(), sp.count()));
        if (Research.start(p, bench, "t2_lens") != null) {
            helper.fail("始められない");
            return;
        }
        be.setEnergy(0);
        be.pretendAway(50);
        if (be.tickOnce() != 0) {
            helper.fail("力が無いのに進んだ");
            return;
        }
        // 100 tick ぶんの力だけためて、500 tick 離れていた → 100 だけ進む
        be.setEnergy(knife.fePerTick() * 100);
        be.pretendAway(500);
        int step = be.tickOnce();
        if (step != 100 || be.energy() != 0) {
            helper.fail("ためた力の分だけ進んでいない: " + step + "・残り " + be.energy());
            return;
        }
        // 十分な力で長く離れていた → 終わる
        be.setEnergy(Integer.MAX_VALUE / 2);
        be.pretendAway(knife.ticks() * 2L);
        be.tickOnce();
        ResearchTree tree = ResearchTree.get();
        if (!ResearchData.get(server).isDone(tree, "t2_lens") || ResearchData.get(server).active() != null) {
            helper.fail("離れていた分で終わっていない");
            return;
        }
        if (Research.recipeLocked(server, recipe)) {
            helper.fail("開いたのに作り方の鍵が外れない");
            return;
        }
        reset(server);
        helper.succeed();
    }

    /** 段 2 の門は器具の節で、器具の節は器具なしで調べられ、段 2 の研究は器具(拡大鏡と蒸留器)が研究台のそばに無いと始まらない(決定 23・11-17 夜)。 */
    @GameTest(template = TEMPLATE, batch = "research_instruments", timeoutTicks = 20)
    public static void tierTwoNeedsInstrument(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        reset(server);
        BlockPos bench = placeBench(helper);
        FakePlayer p = player(level);
        p.getInventory().clearContent();
        Research.open(server, "t1_fiber", false);
        // 器具の節(段 2 の門)は器具なしで調べられる(1 つ前の段の器具で調べる)。鎧の節を飛ばしても段 2 へ進める(11-17 夜ユーザー)
        ResearchTree.Node lens = ResearchTree.get().node("t2_lens");
        for (ResearchTree.Specimen sp : lens.specimens()) p.getInventory().add(new ItemStack(sp.asItem(), sp.count()));
        if (Research.whyNot(level, bench, p, "t2_lens") != null) {
            helper.fail("器具の節を器具なしで始められない: " + Research.whyNot(level, bench, p, "t2_lens").getString());
            return;
        }
        Research.open(server, "t2_lens", false);
        if (Research.state(server, "t2_ore") != Research.State.AVAILABLE) {
            helper.fail("器具の節を開いても段 2 の入口が開けない(鎧の節を飛ばせない)");
            return;
        }
        ResearchTree.Node ore = ResearchTree.get().node("t2_ore");
        for (ResearchTree.Specimen sp : ore.specimens()) p.getInventory().add(new ItemStack(sp.asItem(), sp.count()));
        if (Research.instrumentsPresent(level, bench, 2) || Research.start(p, bench, "t2_ore") == null) {
            helper.fail("器具が無いのに段 2 を始められた");
            return;
        }
        helper.setBlock(new BlockPos(5, 2, 3), ModResearch.LENS.get().defaultBlockState());
        if (!Research.instrumentsPresent(level, bench, 2) || Research.instrumentsPresent(level, bench, 3)) {
            helper.fail("器具の見分けが違う");
            return;
        }
        if (Research.start(p, bench, "t2_ore") != null) {
            helper.fail("器具があるのに始められない");
            return;
        }
        reset(server);
        helper.succeed();
    }
}
