package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.adaptation.AdaptationData;
import com.yayuy.overgrownvegetation.adaptation.WeaponClassifier;
import com.yayuy.overgrownvegetation.data.AdaptationTable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * グループ5(適応種)の<b>武器への適応</b>のテスト(2026-09-23)。
 *
 * <p>守りたいのは 3 つ。
 * <ul>
 *     <li><b>積み方</b> —— 1 キル 2%、合計 30% で頭打ち、上限の後は全欄を比例して縮める(B 方式)。
 *     累計キル数の比で配分する作りに戻ると、長く遊んだワールドで耐性が動かなくなる。</li>
 *     <li><b>武器種の判定</b> —— 矢は撃った弓で、知らない武器はアイテム id で、{@code /kill} は数えない。</li>
 *     <li><b>既定の規則と同梱の json が食い違わない</b> —— 片方だけ直すと、config を消したときだけ挙動が変わる。</li>
 * </ul>
 *
 * <p>台帳はワールドに 1 つなので、各テストは<b>別々の種の id</b>を使い、最後に必ず消す
 * (同じバッチのテストは並列に走る)。グループ5 のタグはまだ空なので、ここでは G5 でない種の id を借りて
 * 台帳と判定を直接叩いている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AdaptationGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AdaptationGameTests.class);
        }

        private Registration() {
        }
    }

    private static final double EPS = 1.0E-6D;

    /** 上限までは 2% ずつ、その武器の欄だけが育つ(2 剣 + 3 弓 + 1 銃 = 4% / 6% / 2%)。 */
    @GameTest(template = "gametest_empty7")
    public static void ledgerAddsTwoPercentPerKillBelowCap(GameTestHelper helper) {
        String id = "overgrown_vegetation:test_adapt_below_cap";
        AdaptationData data = AdaptationData.get(helper.getLevel().getServer());
        try {
            kill(data, id, "sword", 2);
            kill(data, id, "bow", 3);
            kill(data, id, "gun", 1);
            expect(helper, data.resistance(id, "sword", 0.30D), 0.04D, "剣");
            expect(helper, data.resistance(id, "bow", 0.30D), 0.06D, "弓");
            expect(helper, data.resistance(id, "gun", 0.30D), 0.02D, "銃");
            expect(helper, data.resistance(id, "axe", 0.30D), 0.0D, "倒されていない斧");
            helper.succeed();
        } finally {
            data.forget(id);
        }
    }

    /** 15 キルで 30% に届き、そこから別の武器で倒すと古い欄が比例して縮む。 */
    @GameTest(template = "gametest_empty7")
    public static void ledgerScalesDownPastCap(GameTestHelper helper) {
        String id = "overgrown_vegetation:test_adapt_cap";
        AdaptationData data = AdaptationData.get(helper.getLevel().getServer());
        try {
            kill(data, id, "sword", 20);
            expect(helper, data.resistance(id, "sword", 0.30D), 0.30D, "剣だけで 20 キル");

            kill(data, id, "bow", 1);
            // 0.30 + 0.02 = 0.32 を 0.30 へ縮める → ×0.9375
            expect(helper, data.resistance(id, "sword", 0.30D), 0.28125D, "弓 1 キル後の剣");
            expect(helper, data.resistance(id, "bow", 0.30D), 0.01875D, "弓 1 キル後の弓");
            double sum = data.slots(id).values().stream().mapToDouble(Double::doubleValue).sum();
            expect(helper, sum, 0.30D, "合計");
            helper.succeed();
        } finally {
            data.forget(id);
        }
    }

    /**
     * B 方式を選んだ理由そのもの。剣で 300 体倒したあと弓で 30 体倒せば、弓の方が大きくなる
     * (累計の比なら 剣 27.3% / 弓 2.7% のまま動かない)。
     */
    @GameTest(template = "gametest_empty7")
    public static void ledgerForgetsOldHistory(GameTestHelper helper) {
        String id = "overgrown_vegetation:test_adapt_history";
        AdaptationData data = AdaptationData.get(helper.getLevel().getServer());
        try {
            kill(data, id, "sword", 300);
            kill(data, id, "bow", 30);
            double sword = data.resistance(id, "sword", 0.30D);
            double bow = data.resistance(id, "bow", 0.30D);
            // 剣 = 0.30 × 0.9375^30 ≒ 0.0431、弓 = 0.30 − 剣
            expect(helper, sword, 0.30D * Math.pow(0.9375D, 30), "剣");
            expect(helper, bow, 0.30D - 0.30D * Math.pow(0.9375D, 30), "弓");
            helper.succeed();
        } finally {
            data.forget(id);
        }
    }

    /** 上限は読むときにも掛かる(記憶線が壊れて上限が下がっても台帳を書き直さない)。 */
    @GameTest(template = "gametest_empty7")
    public static void ledgerAppliesCapWhenRead(GameTestHelper helper) {
        String id = "overgrown_vegetation:test_adapt_read_cap";
        AdaptationData data = AdaptationData.get(helper.getLevel().getServer());
        try {
            kill(data, id, "sword", 10);
            kill(data, id, "bow", 5);
            expect(helper, data.resistance(id, "sword", 0.15D), 0.10D, "上限 15% での剣");
            expect(helper, data.resistance(id, "bow", 0.15D), 0.05D, "上限 15% での弓");
            expect(helper, data.resistance(id, "sword", 0.45D), 0.20D, "上限 45% での剣(縮めない)");
            helper.succeed();
        } finally {
            data.forget(id);
        }
    }

    /** バニラの武器・飛び道具・環境ダメージの読み替え。 */
    @GameTest(template = "gametest_empty7")
    public static void classifierReadsVanillaWeapons(GameTestHelper helper) {
        DamageSources sources = helper.getLevel().damageSources();
        AdaptationTable table = AdaptationTable.DEFAULT;
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        check(helper, holding(player, Items.DIAMOND_SWORD, sources), table, "sword", "ダイヤの剣");
        check(helper, holding(player, Items.NETHERITE_AXE, sources), table, "axe", "ネザライトの斧");
        check(helper, holding(player, Items.IRON_PICKAXE, sources), table, "tool", "鉄のツルハシ");
        check(helper, holding(player, Items.MACE, sources), table, "mace", "メイス");
        check(helper, holding(player, ItemStack.EMPTY.getItem(), sources), table, WeaponClassifier.UNARMED, "素手");

        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        Arrow fromBow = new Arrow(helper.getLevel(), at.getX(), at.getY(), at.getZ(),
                new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        check(helper, sources.arrow(fromBow, player), table, "bow", "弓で撃った矢");
        Arrow fromCrossbow = new Arrow(helper.getLevel(), at.getX(), at.getY(), at.getZ(),
                new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
        check(helper, sources.arrow(fromCrossbow, player), table, "bow", "クロスボウで撃った矢");

        check(helper, sources.explosion(null, null), table, "explosion", "爆発");
        check(helper, sources.onFire(), table, "fire", "炎上");
        check(helper, sources.magic(), table, "magic", "魔法");
        check(helper, sources.fall(), table, "minecraft:fall", "落下(規則に無いのでダメージタイプ id)");
        check(helper, sources.genericKill(), table, null, "/kill は数えない");

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        check(helper, sources.mobAttack(zombie), table, "minecraft:zombie", "素手のゾンビ(種類 id)");
        zombie.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        check(helper, sources.mobAttack(zombie), table, "sword", "剣を持ったゾンビ");
        zombie.discard();
        helper.succeed();
    }

    /** 規則に無い武器はアイテム id が欄になり、config の規則はそれより優先される。 */
    @GameTest(template = "gametest_empty7")
    public static void classifierFallsBackToItemIdAndHonoursConfig(GameTestHelper helper) {
        DamageSources sources = helper.getLevel().damageSources();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DamageSource stick = holding(player, Items.STICK, sources);

        check(helper, stick, AdaptationTable.DEFAULT, "minecraft:stick", "規則に無い武器");

        AdaptationTable.RuleJson byItem = new AdaptationTable.RuleJson();
        byItem.key = "club";
        byItem.items = List.of("minecraft:stick");
        AdaptationTable.Json json = new AdaptationTable.Json();
        json.classes = List.of(byItem);
        check(helper, stick, AdaptationTable.fromJson(json), "club", "アイテム id の規則");

        // 名前空間の規則は武器のアイテムにも当たる(TaCz の銃を 1 つにまとめる書き方)
        AdaptationTable.RuleJson byNamespace = new AdaptationTable.RuleJson();
        byNamespace.key = "gun";
        byNamespace.namespaces = List.of("minecraft");
        json.classes = List.of(byNamespace);
        check(helper, stick, AdaptationTable.fromJson(json), "gun", "名前空間の規則");
        helper.succeed();
    }

    /** 同梱の {@code design/adaptation.json} と Java の既定値が同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void bundledJsonMatchesDefault(GameTestHelper helper) {
        AdaptationTable bundled;
        try (InputStream in = AdaptationGameTests.class
                .getResourceAsStream("/overgrown_vegetation/design/adaptation.json")) {
            if (in == null) {
                helper.fail("design/adaptation.json が同梱されていない");
                return;
            }
            bundled = AdaptationTable.fromJson(new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), AdaptationTable.Json.class));
        } catch (Exception e) {
            helper.fail("design/adaptation.json が読めない: " + e);
            return;
        }
        AdaptationTable def = AdaptationTable.DEFAULT;
        if (!bundled.rules().equals(def.rules())) {
            helper.fail("classes が Java の既定値と違う\njson: " + bundled.rules() + "\njava: " + def.rules());
            return;
        }
        if (bundled.perKill() != def.perKill() || bundled.cap() != def.cap()
                || bundled.forgetBelow() != def.forgetBelow()
                || !bundled.ignoredDamageTypes().equals(def.ignoredDamageTypes())) {
            helper.fail("perKill / cap / forgetBelow / ignoredDamageTypes が Java の既定値と違う");
            return;
        }
        helper.succeed();
    }

    /** 判定から台帳・耐性までを通しで({@link Adaptation} の入口)。 */
    @GameTest(template = "gametest_empty7")
    public static void resistanceComesFromKillsWithSameWeapon(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EntityType<?> species = EntityType.PIGLIN_BRUTE;
        String id = Adaptation.speciesId(species);
        DamageSources sources = helper.getLevel().damageSources();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        try {
            DamageSource sword = holding(player, Items.IRON_SWORD, sources);
            for (int i = 0; i < 15; i++) {
                Adaptation.recordKill(server, species, sword);
            }
            Adaptation.recordKill(server, species, sources.genericKill());

            expect(helper, Adaptation.resistance(server, species, holding(player, Items.GOLDEN_SWORD, sources)),
                    0.30D, "別の剣でも同じ剣の欄");
            expect(helper, Adaptation.resistance(server, species, holding(player, Items.IRON_AXE, sources)),
                    0.0D, "斧");
            if (!AdaptationData.get(server).slots(id).keySet().equals(java.util.Set.of("sword"))) {
                helper.fail("/kill が数えられている: " + AdaptationData.get(server).slots(id));
                return;
            }
            helper.succeed();
        } finally {
            AdaptationData.get(server).forget(id);
        }
    }

    /** グループ5 でない Mob が死んでも台帳に載らない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void nonAdaptiveDeathIsNotRecorded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        zombie.hurt(holding(player, Items.DIAMOND_SWORD, helper.getLevel().damageSources()), 1000.0F);
        helper.runAfterDelay(2L, () -> {
            Map<String, Double> slots = AdaptationData.get(server).slots(Adaptation.speciesId(EntityType.ZOMBIE));
            if (!slots.isEmpty()) {
                helper.fail("グループ5 でないゾンビが記録された: " + slots);
                return;
            }
            helper.succeed();
        });
    }

    // --- 道具 ---

    private static void kill(AdaptationData data, String id, String key, int times) {
        for (int i = 0; i < times; i++) {
            data.record(id, key, 0.02D, 0.30D, 0.001D);
        }
    }

    private static DamageSource holding(Player player, net.minecraft.world.item.Item item, DamageSources sources) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        return sources.playerAttack(player);
    }

    private static void check(GameTestHelper helper, DamageSource source, AdaptationTable table,
                              String expected, String what) {
        String actual = WeaponClassifier.classify(source, table);
        if (!Objects.equals(actual, expected)) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private static void expect(GameTestHelper helper, double actual, double expected, String what) {
        if (Math.abs(actual - expected) > EPS) {
            helper.fail(what + ": " + expected + " のはずが " + actual);
        }
    }

    private AdaptationGameTests() {
    }
}
