package com.yayuy.overgrownvegetation.client.light;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.ExtraArmorItem;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <b>動く光</b>(2026-10-15、ユーザー指定「琥珀の角灯の持ち歩く灯りは本物の動く光」)。光を持つ物を身に付けたプレイヤーの周りを、
 * 光のブロックを置かずに<b>描画だけで</b>明るくする(クライアントだけ)。
 *
 * <h2>仕組み</h2>
 * <ul>
 *     <li>2 tick ごとに光源の一覧(位置と明るさ)を作り直し、<b>作り直したものを丸ごと差し替える</b>({@link #SNAPSHOT})。
 *     ブロックの面の明るさはチャンクを組み立てる別スレッドから読まれるので、読む側は差し替えた配列を読むだけにする。</li>
 *     <li>明るさは「光源の明るさ − 光源からの距離(ブロックの真ん中まで、直線)」を四捨五入(0〜15。仮決め)。壁は通り抜ける。</li>
 *     <li>読むのは 2 か所の mixin: ブロックの面の明るさ({@code LevelRenderer#getLightColor}、ブロック・水・NeoForge の光の計算すべてがここを通る)と、
 *     Mob などの明るさ({@code EntityRenderer#getPackedLightCoords})。元の明るさより<b>暗くはしない</b>。</li>
 *     <li>光源のブロックの位置が変わったときだけ、前と今の位置の周り(明るさの半径)のチャンクの区画を組み立て直させる。</li>
 * </ul>
 *
 * <h2>しないこと</h2>
 * 見た目だけなので、Mob の湧き・作物の育ち・日照センサーには効かない。Sodium / Embeddium を入れるとブロックの面には効かない
 * (チャンクの組み立てを置き換えるため。Mob には効く)。光源は今は追加防具の {@link ExtraArmorItem#wornLightLevel} を付けたプレイヤーだけ
 * (手に持った物・落ちている物は照らさない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class DynamicLights {

    /** 光源の一覧の作り直しの間隔(tick)。 */
    private static final int UPDATE_INTERVAL = 2;
    /** これより遠くのプレイヤーは光源にしない(ブロック)。 */
    private static final double MAX_SOURCE_DISTANCE = 96.0D;
    /** 光源の高さ(足元から。腰のあたり)。 */
    private static final double SOURCE_HEIGHT = 1.0D;

    /** 1 つの光源: 位置と明るさ。 */
    private record Source(double x, double y, double z, int level, long blockKey) {
    }

    /** 今の光源の一覧(差し替えるだけ。中身は書き換えない)。 */
    private static volatile Source[] SNAPSHOT = new Source[0];

    /**
     * {@code pos} のブロックに足す動く光の明るさ(0〜15)。チャンクを組み立てる別スレッドからも呼ばれる。
     */
    public static int levelAt(BlockPos pos) {
        Source[] sources = SNAPSHOT;
        if (sources.length == 0) {
            return 0;
        }
        double cx = pos.getX() + 0.5D;
        double cy = pos.getY() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        int best = 0;
        for (Source s : sources) {
            double dx = cx - s.x();
            double dy = cy - s.y();
            double dz = cz - s.z();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= (double) s.level() * s.level()) {
                continue;
            }
            int v = (int) Math.round(s.level() - Math.sqrt(d2));
            if (v > best) {
                best = Math.min(15, v);
            }
        }
        return best;
    }

    /** 詰めた光の値({@code sky << 20 | block << 4})の、ブロックの光だけを動く光で底上げする。 */
    /** 動く光が 1 つでもあるか(無ければ呼ぶ側で位置を作らずに済ませる、2026-10-20)。 */
    public static boolean any() {
        return SNAPSHOT.length > 0;
    }

    public static int brighten(int packed, BlockPos pos) {
        int dyn = levelAt(pos);
        if (dyn <= 0) {
            return packed;
        }
        int block = (packed >> 4) & 0xF;
        if (dyn <= block) {
            return packed;
        }
        return (packed & ~(0xF << 4)) | (dyn << 4);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (SNAPSHOT.length > 0) {
                SNAPSHOT = new Source[0];
            }
            return;
        }
        if (mc.level.getGameTime() % UPDATE_INTERVAL != 0L) {
            return;
        }
        List<Source> next = new ArrayList<>();
        Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        // 動く光をオフにした(品質の段階 低・2026-10-23)ら光源を 1 つも作らない(前の光源のまわりは下で組み立て直される)。
        for (Player player : VisualQuality.dynamicLights() ? mc.level.players() : List.<Player>of()) {
            if (player.isSpectator() || player.distanceToSqr(camera) > MAX_SOURCE_DISTANCE * MAX_SOURCE_DISTANCE) {
                continue;
            }
            int level = wornLight(player);
            if (level <= 0) {
                continue;
            }
            double x = player.getX();
            double y = player.getY() + SOURCE_HEIGHT;
            double z = player.getZ();
            next.add(new Source(x, y, z, level, BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))));
        }
        Source[] before = SNAPSHOT;
        Source[] after = next.toArray(new Source[0]);
        if (sameBlocks(before, after)) {
            SNAPSHOT = after; // 位置の端数だけ変わった(組み立て直しは要らない。明るさは次の組み立て直しで反映)
            return;
        }
        SNAPSHOT = after;
        if (mc.levelRenderer != null) {
            for (Source s : before) {
                markDirty(mc, s);
            }
            for (Source s : after) {
                markDirty(mc, s);
            }
        }
    }

    /** 光を持つ追加防具を付けていれば、その明るさ(いちばん明るいもの)。 */
    private static int wornLight(Player player) {
        int best = 0;
        ExtraArmor armor = ExtraArmor.of(player);
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            ItemStack stack = armor.get(i);
            if (stack.getItem() instanceof ExtraArmorItem item && item.extraArmorPart().ordinal() == i) {
                best = Math.max(best, item.wornLightLevel(stack));
            }
        }
        return best;
    }

    private static boolean sameBlocks(Source[] a, Source[] b) {
        if (a.length != b.length) {
            return false;
        }
        long[] ka = new long[a.length];
        long[] kb = new long[b.length];
        for (int i = 0; i < a.length; i++) {
            ka[i] = a[i].blockKey() * 31L + a[i].level();
            kb[i] = b[i].blockKey() * 31L + b[i].level();
        }
        Arrays.sort(ka);
        Arrays.sort(kb);
        return Arrays.equals(ka, kb);
    }

    private static void markDirty(Minecraft mc, Source s) {
        int r = s.level();
        int x = BlockPos.getX(s.blockKey());
        int y = BlockPos.getY(s.blockKey());
        int z = BlockPos.getZ(s.blockKey());
        mc.levelRenderer.setBlocksDirty(x - r, y - r, z - r, x + r, y + r, z + r);
    }

    private DynamicLights() {
    }
}
