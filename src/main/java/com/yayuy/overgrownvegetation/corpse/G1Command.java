package com.yayuy.overgrownvegetation.corpse;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 確かめ用のコマンド {@code /ov perf g1 hatch [半径]}(2026-11-08。退避前の {@code /ov perf g1} の場所に戻した)。
 * 半径(既定 16)の中の G1 の死体を、10 分を待たずに今すぐ起き上がらせる。感染させるのは {@code /ov perf exposure set <量>} で足りる
 * (暴露量 1500 で 20 ダメージ → 死ねば死体になる)。
 *
 * <p>2026-11-12: {@code /ov perf g1 evolve [半径]} — 1 段目に evolve_in を流させる(進化の時計が無い間の確かめ用)。
 * <p>2026-11-12: {@code /ov perf g1 burst [幅] [高さ]} — 進化体の死に際の破裂({@link G1DeathBurst})を、向いている向きの 4 ブロック先で起こす
 * (進化体はまだ MOD に入っていないので、その代わりの試し撃ち。本物の汚染の球と浮遊の上げも起きる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class G1Command {

    private G1Command() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("g1")
                                .then(Commands.literal("hatch")
                                        .executes(ctx -> hatch(ctx.getSource(), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> hatch(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                // 2026-11-12: 1 段目の evolve_in(段階 0 から変わる所)を流す。進化の時計は司令塔と一緒に(今はこの口だけ)
                                .then(Commands.literal("evolve")
                                        .executes(ctx -> evolve(ctx.getSource(), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> evolve(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                // 2026-11-12(能力の第 1 回): 1 段目の種の技の冷却と技の間を飛ばし、きっかけを立てる(撮影・確かめ用)。
                                // ゾンビは実行した人を殴った相手として仲間を呼ぶ・チビゾンビは袋を噴く・村人は霧を吹く(ほかの技は条件がそろえば出る)
                                .then(Commands.literal("skills")
                                        .executes(ctx -> skills(ctx.getSource(), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> skills(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                // 2026-11-12: 進化体の破裂(G1DeathBurst)を進化体なしで試し撃ちする。幅・高さ = 当たり判定(既定はゾンビ)
                                .then(Commands.literal("burst")
                                        .executes(ctx -> burst(ctx.getSource(), 0.6F, 1.95F))
                                        .then(Commands.argument("width", FloatArgumentType.floatArg(0.1F, 4.0F))
                                                .executes(ctx -> burst(ctx.getSource(), FloatArgumentType.getFloat(ctx, "width"), 1.95F))
                                                .then(Commands.argument("height", FloatArgumentType.floatArg(0.1F, 5.0F))
                                                        .executes(ctx -> burst(ctx.getSource(), FloatArgumentType.getFloat(ctx, "width"),
                                                                FloatArgumentType.getFloat(ctx, "height")))))))));
    }

    /** 実行した所の 4 ブロック先(向いている向きの水平方向、高さは足元)で破裂させる。 */
    private static int burst(CommandSourceStack source, float width, float height) {
        ServerLevel level = source.getLevel();
        Vec3 look = Vec3.directionFromRotation(0.0F, source.getRotation().y);
        Vec3 feet = source.getPosition().add(look.scale(4.0D));
        double percent = G1DeathBurst.burst(level, feet, width, height);
        double size = G1DeathBurst.size(width, height);
        int radius = G1DeathBurst.sphereRadius(size);
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "G1 の破裂: 幅 %.2f 高さ %.2f(大きさ %.2f)・汚染の半径 %d・浮遊 +%.0f%%", width, height, size, radius, percent)), true);
        return 1;
    }

    /** 半径の中の 1 段目の種の技を今すぐ使える状態にする(冷却・技の間を飛ばし、殴られたときの技のきっかけを立てる)。 */
    private static int skills(CommandSourceStack source, int radius) {
        java.util.List<com.yayuy.overgrownvegetation.entity.DevelopedParasitized> found = source.getLevel().getEntitiesOfClass(
                com.yayuy.overgrownvegetation.entity.DevelopedParasitized.class, net.minecraft.world.phys.AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2));
        for (com.yayuy.overgrownvegetation.entity.DevelopedParasitized d : found) {
            d.forceSkillsReady();
            if (d.dev() == com.yayuy.overgrownvegetation.entity.g1.G1Developed.ZOMBIE
                    && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity who) {
                d.callAllies(who);
            }
        }
        int n = found.size();
        source.sendSuccess(() -> Component.literal("1 段目の種の技を使える状態にした: " + n + " 体"), true);
        return n;
    }

    /** 半径の中の 1 段目に evolve_in を流させる(流している間は動かない)。 */
    private static int evolve(CommandSourceStack source, int radius) {
        java.util.List<com.yayuy.overgrownvegetation.entity.DevelopedParasitized> found = source.getLevel().getEntitiesOfClass(
                com.yayuy.overgrownvegetation.entity.DevelopedParasitized.class, net.minecraft.world.phys.AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2));
        found.forEach(com.yayuy.overgrownvegetation.entity.DevelopedParasitized::startEvolveIn);
        int n = found.size();
        source.sendSuccess(() -> Component.literal("1 段目に evolve_in を流させた: " + n + " 体"), true);
        return n;
    }

    private static int hatch(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (level.getBlockEntity(p) instanceof CorruptedCorpseBlockEntity) {
                found.add(p.immutable());
            }
        }
        for (BlockPos p : found) {
            if (level.getBlockEntity(p) instanceof CorruptedCorpseBlockEntity be) {
                be.rise(level, p);
            }
        }
        int n = found.size();
        source.sendSuccess(() -> Component.literal("G1 の死体を起き上がらせた: " + n + " 体"), true);
        return n;
    }
}
