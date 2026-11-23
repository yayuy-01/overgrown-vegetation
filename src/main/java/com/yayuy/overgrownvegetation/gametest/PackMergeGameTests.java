package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.GestatrixEntity;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.spawn.PackMerger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 司令塔がたまった Mob を群れにまとめる(2026-11-17 深夜 ユーザー決定)。{@link PackMerger} を直に 1 回動かして見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PackMergeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PackMergeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";

    private PackMergeGameTests() {
    }

    private static EntityType<?> type(String path) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, path));
    }

    @SuppressWarnings("unchecked")
    private static Mob spawn(GameTestHelper helper, String path, double x, double z) {
        return (Mob) helper.spawn((EntityType<Mob>) type(path), new Vec3(x, 2.0D, z));
    }

    /** 動いている個体にする(11-18 A: まとめるのは群れが動くときだけ)。相手に偽のプレイヤーを渡す。 */
    private static void aim(GameTestHelper helper, List<? extends Mob> ms) {
        net.minecraft.world.entity.player.Player p = GtCompat.mockPlayer(helper, net.minecraft.world.level.GameType.SURVIVAL);
        p.setPos(Vec3.atCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 30))));
        for (Mob m : ms) m.setTarget(p);
    }

    private static void run(GameTestHelper helper) {
        Vec3 c = Vec3.atCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3)));
        Double before = PackMerger.cellOverride;
        PackMerger.cellOverride = 4096.0D; // テスト場が升の境をまたいでも 1 つの升にする
        try {
            PackMerger.run(helper.getLevel(), c.x, c.y, c.z, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        } finally {
            PackMerger.cellOverride = before;
        }
    }

    /** 動いている 6 体が先導役から 12 以内にたまると 1 つの群れになり、先導役は前衛(アルボラ)。グレガリアは自前の群れから抜けて入る。先導役が倒れたら前衛の生き残りが継ぐ。 */
    @GameTest(template = TEMPLATE, batch = "pack_merge", timeoutTicks = 40)
    public static void formsPackWithFrontLeader(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Mob> ms = new ArrayList<>();
        ms.add(spawn(helper, "strobila", 1.5, 1.5));
        ms.add(spawn(helper, "vesicaria", 2.5, 1.5));
        ms.add(spawn(helper, "gregaria", 3.5, 1.5));
        ms.add(spawn(helper, "arbora", 4.5, 1.5));
        ms.add(spawn(helper, "strobila", 1.5, 4.5));
        ms.add(spawn(helper, "arbora", 4.5, 4.5));
        helper.runAfterDelay(3, () -> {
            aim(helper, ms);
            run(helper);
            CrawlerPack p = CrawlerPack.externalOf(ms.get(0));
            helper.assertTrue(p != null && p.size() == 6, "6 体が 1 つの群れにならない: " + (p == null ? "群れなし" : p.size()));
            for (Mob m : ms) helper.assertTrue(CrawlerPack.externalOf(m) == p, "群れに入っていない個体がいる: " + m.getType());
            helper.assertTrue(p.leader().getType() == type("arbora"), "先導役が前衛でない: " + p.leader().getType());
            GregariaEntity g = (GregariaEntity) ms.get(2);
            helper.assertTrue(g.pack() == null, "グレガリアが自前の群れに残っている");
            Mob lead = p.leader();
            lead.kill();
            p.membersView(); // 次の確かめで外れる
        });
        helper.runAfterDelay(6, () -> {
            CrawlerPack p = CrawlerPack.externalOf(ms.get(0));
            helper.assertTrue(p != null && p.size() == 5 && p.leader().isAlive() && p.leader().getType() == type("arbora"),
                    "先導役が倒れた後、前衛の生き残りが継がない: " + (p == null ? "群れなし" : p.leader().getType() + "・" + p.size()));
            for (Mob m : ms) m.discard();
            helper.succeed();
        });
    }

    /** 同じ升の 2 つの群れは 1 つにまとまる(大きさの上限なし)。5 体以下の升では新しく作らない。 */
    @GameTest(template = TEMPLATE, batch = "pack_merge2", timeoutTicks = 40)
    public static void mergesPacksInTheSameCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Mob> a = List.of(spawn(helper, "arbora", 1.5, 1.5), spawn(helper, "strobila", 2.5, 1.5));
        List<Mob> b = List.of(spawn(helper, "arbora", 4.5, 4.5), spawn(helper, "vesicaria", 5.5, 4.5), spawn(helper, "strobila", 5.5, 5.5));
        Mob single = spawn(helper, "vesicaria", 3.5, 3.5);
        helper.runAfterDelay(3, () -> {
            CrawlerPack pa = CrawlerPack.formExternal(level, a), pb = CrawlerPack.formExternal(level, b);
            aim(helper, List.of(single));
            helper.assertTrue(pa != pb, "前提: 2 つの群れ");
            run(helper);
            CrawlerPack p = CrawlerPack.externalOf(a.get(0));
            helper.assertTrue(p != null && p == CrawlerPack.externalOf(b.get(0)) && p.size() == 6, "同じ升の群れが 1 つにならない(浮いた 1 体も入るはず): " + (p == null ? 0 : p.size()));
            helper.assertTrue(CrawlerPack.externalOf(single) == p, "群れのある升の浮いた個体が入らない");
            for (Mob m : p.membersView().toArray(new Mob[0])) m.discard();
            helper.succeed();
        });
    }

    /** 歩いているゲスタトリクスは支援として入り、腰を据えたら群れから抜ける。 */
    @GameTest(template = TEMPLATE, batch = "pack_merge3", timeoutTicks = 40)
    public static void settledGestatrixLeavesPack(GameTestHelper helper) {
        List<Mob> ms = new ArrayList<>();
        for (int i = 0; i < 5; i++) ms.add(spawn(helper, i < 2 ? "arbora" : "strobila", 1.5 + i, 2.5));
        GestatrixEntity g = (GestatrixEntity) helper.spawn(ModCrawlers.GESTATRIX.get(), new Vec3(3.5, 2.0, 5.0));
        helper.runAfterDelay(3, () -> {
            List<Mob> all = new ArrayList<>(ms);
            all.add(g);
            aim(helper, all);
            run(helper);
            helper.assertTrue(CrawlerPack.externalOf(g) != null, "歩いているゲスタトリクスが群れに入らない");
            g.forceStateForTests(GestatrixEntity.SETTLE, 30);
            run(helper);
            helper.assertTrue(CrawlerPack.externalOf(g) == null, "腰を据えたゲスタトリクスが群れに残っている");
            for (Mob m : ms) m.discard();
            g.discard();
            helper.succeed();
        });
    }

    /** G1 の根付きは升(4×4 チャンク)ごとに rootedPerCell まで(11-17 深夜 ユーザー (b))。満ちたら根付きを選べない。 */
    @GameTest(template = TEMPLATE, batch = "rooted_cap", timeoutTicks = 20)
    public static void rootedCapPerCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int cap = com.yayuy.overgrownvegetation.data.DesignData.director().rootedPerCell;
        if (cap <= 0) {
            helper.succeed();
            return;
        }
        Vec3 at = new Vec3(3.5, 2.0, 3.5);
        List<Mob> made = new ArrayList<>();
        for (int i = 0; i < cap - 1; i++) made.add(helper.spawn(com.yayuy.overgrownvegetation.registry.ModG1.rooted(com.yayuy.overgrownvegetation.entity.g1.G1Rooted.CHICKEN).get(), at));
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(com.yayuy.overgrownvegetation.director.ForestDirector.rootRoom(level, pos), "上限の手前なのに根付けない");
            made.add(helper.spawn(com.yayuy.overgrownvegetation.registry.ModG1.rooted(com.yayuy.overgrownvegetation.entity.g1.G1Rooted.CHICKEN).get(), at));
        });
        helper.runAfterDelay(3, () -> {
            helper.assertFalse(com.yayuy.overgrownvegetation.director.ForestDirector.rootRoom(level, pos), "升の根付きが上限 " + cap + " なのにまだ根付ける");
            for (Mob m : made) m.discard();
            helper.succeed();
        });
    }
}
