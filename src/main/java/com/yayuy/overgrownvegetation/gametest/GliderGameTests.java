package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyUpdraftBlock;
import com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.item.GliderItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 布のグライダー(2026-10-22)。動き方の決まりを偽プレイヤーで確かめる(プレイヤーの移動そのものは手元のクライアントなので、速さの決まりだけ)。
 * <ul>
 *     <li>開いた滑空の速さが 前 {@link GliderItem#SPEED} : 下 {@link GliderItem#SINK}(1 : 0.25)に寄る・ふつうのジャンプ(落下の距離が小さい)では開かない・
 *     グライダーを持っていないと開かない({@link #glidesOneDownForFourAhead})。</li>
 *     <li>上昇気流機の柱の中では落下の距離が 0 でも開いて上がる({@link #risesInUpdraftColumn})。</li>
 *     <li>開いている 1 秒ごとに耐久 1 減り、残り 1 で止まって開かなくなる({@link #wearsAndStopsWhenTorn})。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GliderGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GliderGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "glider";

    private static Player gliderPlayer(GameTestHelper helper, Vec3 at) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.setPos(at);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.GLIDER.get()));
        p.setYRot(0.0F); // 南(+z)を向く
        return p;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void glidesOneDownForFourAhead(GameTestHelper helper) {
        Player p = gliderPlayer(helper, Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 5, 3))));
        p.fallDistance = 0.5F;
        p.setDeltaMovement(0.0D, -0.3D, 0.0D);
        if (GliderItem.glide(p)) {
            helper.fail("ふつうのジャンプほどの落下で開いた");
            return;
        }
        p.fallDistance = GliderItem.OPEN_FALL + 1.0F;
        p.setDeltaMovement(0.0D, -0.6D, 0.0D);
        for (int i = 0; i < 80; i++) {
            if (!GliderItem.glide(p)) {
                helper.fail("落ちているのに開かない(" + i + " tick 目)");
                return;
            }
        }
        Vec3 v = p.getDeltaMovement();
        double ahead = v.horizontalDistance();
        if (Math.abs(v.y + GliderItem.SINK) > 1.0E-6 || v.z < GliderItem.SPEED * 0.98D || Math.abs(v.x) > 1.0E-3) {
            helper.fail("滑空の速さが違う: " + v + "(前 " + GliderItem.SPEED + "・下 " + GliderItem.SINK + " のはず)");
            return;
        }
        if (Math.abs(-v.y / ahead - 0.25D) > 0.01D) {
            helper.fail("前 1 : 下 0.25 になっていない: " + (-v.y / ahead));
            return;
        }
        // 開いたら、落下の距離が消えても(サーバーが毎 tick 消す)閉じない
        p.fallDistance = 0.0F;
        if (!GliderItem.glide(p)) {
            helper.fail("落下の距離が消えたら閉じた");
            return;
        }
        // 持ち替えたら閉じる
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.fallDistance = 10.0F;
        if (GliderItem.glide(p)) {
            helper.fail("グライダーを持っていないのに開いた");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void risesInUpdraftColumn(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, ModBlocks.LEY_UPDRAFT.get());
        LeyUpdraftBlockEntity be = (LeyUpdraftBlockEntity) helper.getBlockEntity(rel);
        be.setBatteryCharge(BatteryItem.CAPACITY);
        be.setHasBattery(true);
        BlockPos at = helper.absolutePos(rel);
        helper.runAfterDelay(3, () -> {
            if (!helper.getBlockState(rel).getValue(LeyUpdraftBlock.POWERED)) {
                helper.fail("上昇気流機が動かない");
                return;
            }
            Player p = gliderPlayer(helper, new Vec3(at.getX() + 0.5D, at.getY() + 4.0D, at.getZ() + 0.5D));
            p.fallDistance = 0.0F;
            p.setDeltaMovement(0.0D, -0.1D, 0.0D);
            if (!GliderItem.glide(p) || Math.abs(p.getDeltaMovement().y - GliderItem.UPDRAFT_LIFT) > 1.0E-6) {
                helper.fail("柱の中で上がらない: " + p.getDeltaMovement());
                return;
            }
            // 柱の外(隣の列)では落下の距離が小さいので開かない
            Player q = gliderPlayer(helper, new Vec3(at.getX() + 1.5D, at.getY() + 4.0D, at.getZ() + 0.5D));
            if (GliderItem.glide(q)) {
                helper.fail("柱の外で落下の距離 0 なのに開いた");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void wearsAndStopsWhenTorn(GameTestHelper helper) {
        Player p = gliderPlayer(helper, Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 5, 3))));
        ItemStack glider = p.getMainHandItem();
        if (glider.getMaxDamage() != GliderItem.DURABILITY) {
            helper.fail("耐久が " + GliderItem.DURABILITY + " でない: " + glider.getMaxDamage());
            return;
        }
        for (int i = 0; i < 30; i++) {
            GliderItem.wear(helper.getLevel(), p, glider);
        }
        if (glider.getDamageValue() != 30) {
            helper.fail("30 秒開いて耐久が 30 減っていない: " + glider.getDamageValue());
            return;
        }
        glider.setDamageValue(GliderItem.DURABILITY - 2);
        GliderItem.wear(helper.getLevel(), p, glider);
        GliderItem.wear(helper.getLevel(), p, glider);
        if (glider.isEmpty() || glider.getDamageValue() != GliderItem.DURABILITY - 1 || GliderItem.usable(glider)) {
            helper.fail("破れたら残り 1 で止まるはず: " + glider.getDamageValue() + " / 空 " + glider.isEmpty());
            return;
        }
        p.fallDistance = 10.0F;
        if (GliderItem.glide(p)) {
            helper.fail("破れたグライダーが開いた");
            return;
        }
        if (!glider.getItem().isValidRepairItem(glider, new ItemStack(ModItems.PLANT_FIBER.get()))) {
            helper.fail("繊維質で直せない");
            return;
        }
        helper.succeed();
    }
}
