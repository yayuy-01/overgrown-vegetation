package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.NestRole;
import com.yayuy.overgrownvegetation.nest.BuilderGroups;
import com.yayuy.overgrownvegetation.nest.NestDigData;
import net.minecraft.nbt.CompoundTag;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>巣が自然に生まれるまでの道筋</b>のテスト(2026-09-20 のユーザー質問
 * 「巣が自然に発生するかどうか一度シミュレーションできますか」)。
 *
 * <p>営巣は段が多い —— <b>アルボラが集まる → 確率で建設グループ → 歩いて行く → 掘る</b>。
 * 最初の段(集まりから建設グループを組めるか)だけは、これまで<b>一度も機械で確かめていなかった</b>。
 * {@code BuilderGroups} の走査はプレイヤーの周り 128 マスしか見ないので、プレイヤーの居ない
 * テストサーバーではイベントからは決して動かない —— 面々を直接渡す入口
 * ({@link BuilderGroups#formFrom}) を使って辿る。
 *
 * <p>ここで見ないもの: 歩いて行く段と掘る段。前者は数百マスの移動で、後者は
 * {@code NestLayoutGameTests} が形として確かめている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NestFormationGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NestFormationGameTests.class);
        }

        private Registration() {
        }
    }

    /**
     * <b>アルボラが {@code builderRequired} 体集まれば建設グループが組める</b>。
     *
     * <p>組めたら全員が同じグループの UUID と<b>同じ建設地</b>を憶えているはず ——
     * バラバラだと道中で散って、いつまでも掘り始めない。
     */
    @GameTest(template = "gametest_empty7")
    public static void fifteenArboraFormABuilderGroup(GameTestHelper helper) {
        BuilderGroups.clearAll();                     // 前のテストが押さえた枠を返しておく
        NestTable t = DesignData.nest();
        List<ArboraEntity> crew = new ArrayList<>();
        for (int i = 0; i < t.builderRequired(); i++) {
            ArboraEntity arbora = helper.spawn(ModEntities.ARBORA.get(), new BlockPos(1 + i % 5, 1, 1 + i / 5));
            crew.add(arbora);
        }
        for (ArboraEntity arbora : crew) {
            if (arbora.nestRole() != NestRole.NONE) {
                helper.fail("湧いたばかりのアルボラが手空きでない: " + arbora.nestRole());
                return;
            }
        }
        String result = BuilderGroups.formFrom(helper.getLevel(), crew);
        UUID group = crew.get(0).builderGroup();
        if (group == null) {
            helper.fail("建設グループが組まれなかった: " + result);
            return;
        }
        BlockPos site = crew.get(0).builderSite();
        if (site == null) {
            helper.fail("建設地が決まっていない: " + result);
            return;
        }
        for (ArboraEntity arbora : crew) {
            if (!group.equals(arbora.builderGroup())) {
                helper.fail("グループがばらけている(" + arbora.builderGroup() + ")");
                return;
            }
            if (!site.equals(arbora.builderSite())) {
                helper.fail("建設地がばらけている(" + arbora.builderSite() + " と " + site + ")");
                return;
            }
            if (arbora.nestRole() != NestRole.BUILDER) {
                helper.fail("建設グループの役割になっていない: " + arbora.nestRole());
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>足りなければ組まない</b>。{@code builderRequired} に 1 体足りない集まりで組めてしまうと、
     * 巣が湧きすぎる。
     */
    @GameTest(template = "gametest_empty7")
    public static void notEnoughArboraFormNothing(GameTestHelper helper) {
        BuilderGroups.clearAll();
        NestTable t = DesignData.nest();
        List<ArboraEntity> crew = new ArrayList<>();
        for (int i = 0; i < t.builderRequired() - 1; i++) {
            crew.add(helper.spawn(ModEntities.ARBORA.get(), new BlockPos(1 + i % 5, 1, 1 + i / 5)));
        }
        BuilderGroups.formFrom(helper.getLevel(), crew);
        for (ArboraEntity arbora : crew) {
            if (arbora.builderGroup() != null) {
                helper.fail("人数が足りないのに建設グループが組まれた");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>建設グループは世界に 1 つだけ</b>(ユーザー指定 2026-09-20)。
     * 1 マスずつ掘るあいだチャンクを掴み続けるので、同時に 2 つ走らせると重い。
     */
    @GameTest(template = "gametest_empty7")
    public static void onlyOneBuilderGroupAtATime(GameTestHelper helper) {
        BuilderGroups.clearAll();
        NestTable t = DesignData.nest();
        List<ArboraEntity> first = new ArrayList<>();
        for (int i = 0; i < t.builderRequired(); i++) {
            first.add(helper.spawn(ModEntities.ARBORA.get(), new BlockPos(1 + i % 5, 1, 1 + i / 5)));
        }
        BuilderGroups.formFrom(helper.getLevel(), first);
        if (first.get(0).builderGroup() == null) {
            helper.fail("1 つめの建設グループが組まれなかった");
            return;
        }
        List<ArboraEntity> second = new ArrayList<>();
        for (int i = 0; i < t.builderRequired(); i++) {
            second.add(helper.spawn(ModEntities.ARBORA.get(), new BlockPos(1 + i % 5, 1, 4 + i / 5)));
        }
        BuilderGroups.formFrom(helper.getLevel(), second);
        for (ArboraEntity arbora : second) {
            if (arbora.builderGroup() != null) {
                helper.fail("2 つめの建設グループが組まれてしまった");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>掘りかけの巣がワールドに残る</b>(2026-09-20 のユーザー報告
     * 「再ログインのたびにグループがなくなると巣が一生完成しないのでは」)。
     *
     * <p>アルボラ側の記憶(グループ・建設地・役割)はエンティティの NBT にもとから残る。
     * 消えていたのは<b>掘削キュー</b>のほうで、ただの静的リストだった。
     * ここでは書き出して読み直す往復を確かめる —— 1 マスずつ掘る作りにして何分もかかるぶん、
     * ここが抜けていると<b>再ログインのたびに掘り直し</b>になる。
     *
     * <p>UUID の書き出しは<b>読む側だけ書いて書く側を忘れる</b>のが起きやすい形なので、
     * 掘り手の名簿までそのまま戻るかを見る。
     */
    @GameTest(template = "gametest_empty7")
    public static void halfDugNestSurvivesASaveAndLoad(GameTestHelper helper) {
        List<UUID> crew = List.of(UUID.randomUUID(), UUID.randomUUID());
        NestDigData.Snapshot before = new NestDigData.Snapshot(
                new BlockPos(123, 64, -456), 0x0123456789ABCDEFL, 12, 3456, 78, 9, crew);
        NestDigData data = NestDigData.get(helper.getLevel());
        data.store(before);
        CompoundTag tag = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        NestDigData.Snapshot after = NestDigData.FACTORY.deserializer()
                .apply(tag, helper.getLevel().registryAccess()).snapshot();
        data.clear();
        if (after == null) {
            helper.fail("掘りかけの巣が読み直せなかった");
            return;
        }
        if (!after.origin().equals(before.origin()) || after.seed() != before.seed()) {
            helper.fail("入口か種が戻らない(形を組み立て直せない)");
            return;
        }
        if (after.moundCursor() != before.moundCursor() || after.cursor() != before.cursor()
                || after.decorCursor() != before.decorCursor() || after.ventCursor() != before.ventCursor()) {
            helper.fail("どこまで掘ったかが戻らない");
            return;
        }
        if (!after.crew().equals(before.crew())) {
            helper.fail("掘り手の名簿が戻らない: " + after.crew());
            return;
        }
        helper.succeed();
    }

    private NestFormationGameTests() {
    }
}
