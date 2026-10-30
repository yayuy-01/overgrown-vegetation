package com.yayuy.overgrownvegetation.base.build;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.Blueprint;
import com.yayuy.overgrownvegetation.base.plan.Blueprints;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Facing;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.base.plan.Zone;
import com.yayuy.overgrownvegetation.base.gen.BaseSurvey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /ov base …} — 同盟軍の基地の開発用コマンド(2026-10-01)。出力は開発用なので {@code Component.literal}。
 * <ul>
 *     <li>{@code place [レベル] [種] [向き] [種類]} / {@code place <レベル> type <種類>} — 足元を中心に基地を建てる(中心に近い順に 1 tick 2 チャンクずつ現れる)。
 *     向きは正門の向き 0 = 南・1 = 西・2 = 北・3 = 東。種類は {@link BaseType}(既定は浄化 = 浄化圏がつながる形)。</li>
 *     <li>{@code upgrade [レベル]} — いちばん近い基地を広げる(費用なし)。{@code finish} — 工事をその場で終える。</li>
 *     <li>{@code info} / {@code list} / {@code forget}(台帳から外すだけ。ブロックは残る)。</li>
 *     <li>{@code purifiers [now]} — いちばん近い基地の胞子除去装置の状態(フェーズ 2 までは置かない、{@link BasePurifiers})。{@code now} で今すぐ後から置く。</li>
 *     <li>{@code building <種類> [レベル]} — 建物 1 棟だけを足元の南に置く(見た目の確認用)。</li>
 *     <li>{@code survey [半径] [plans]} — 周りの村が来うる場所と世界生成の基地の数を数える({@code base/gen/BaseSurvey}。チャンクは作らない)。</li>
 *     <li>{@code generate [レベル] [種類]} — 足元のチャンクを中心に、世界生成と同じ道で基地をすぐ建てて台帳に載せる。</li>
 *     <li>{@code garrison [replenish | census]} — いちばん近い基地の守備兵の名簿と補充の様子({@link BaseGarrison})。{@code replenish} で
 *     間隔を待たずに 1 体補充する(貯蓄から払う)、{@code census} で名簿を実際の兵士で直す(基地が全部読み込まれているときだけ)。</li>
 *     <li>{@code refuge} — 足元が基地の壁の中(敵が湧かない避難場所、{@link BaseRefuge})か。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseCommand {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("base")
                        .requires(source -> source.hasPermission(2))
                        // 2026-10-10 ユーザー指定でコマンドを整理: 基地は /ov base place <レベル> だけ(足元に浄化型の基地を建て始める)。
                        .then(Commands.literal("place")
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, BasePlan.MAX_LEVEL))
                                        .executes(c -> place(c, IntegerArgumentType.getInteger(c, "level"), null, 0, BaseType.PURIFICATION))))));
    }

    /** 世界生成の地形の高さ(ノイズから。読み込んでいない場所でも分かる)で標本を取る({@link BaseNoise#sample})。 */
    public static BaseTerrain sampleNoise(ServerLevel level, BlockPos center, int rotation) {
        return BaseNoise.sample(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(), level, center, rotation);
    }

    /** 計画の浄化圏の和の外接の大きさ(「幅 × 奥行」)。 */
    private static String size(BasePlan plan) {
        int[] b = plan.bounds();
        return (b[2] - b[0] + 1) + " × " + (b[3] - b[1] + 1);
    }

    private static int place(CommandContext<CommandSourceStack> c, int level, Long seed, int rotation, BaseType type) {
        CommandSourceStack src = c.getSource();
        ServerLevel world = src.getLevel();
        Vec3 p = src.getPosition();
        BlockPos center = BlockPos.containing(p);
        long s = seed != null ? seed : world.getRandom().nextLong();
        BaseSites data = BaseSites.get(world.getServer());
        BaseTerrain terrain = sampleNoise(world, center, rotation);
        BaseSite site = data.add(world.dimension(), center, rotation, s, 0, terrain, BasePurifiers.allowed(world), type);
        site.setLevel(level);
        BaseConstruction.start(data, site, 0, level);
        BasePlan plan = site.plan(level);
        src.sendSuccess(() -> Component.literal("基地 #" + site.id + "(" + type.label + ")をレベル " + level + " で建て始めた(種 " + s + "・向き " + rotation
                + "・浄化圏 " + plan.lobes.size() + "・外接 " + size(plan)
                + (site.purifiers() ? "" : "・フェーズ " + BasePurifiers.PHASE + " まで胞子除去装置なし")
                + ")"), true);
        return site.id;
    }

    private BaseCommand() {
    }
}
