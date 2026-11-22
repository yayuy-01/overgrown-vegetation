package com.yayuy.overgrownvegetation.organ;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 器官ができたときの知らせと、器官を見に飛ぶ所(開発用。2026-11-20 ユーザー「器官が育つところを見たいから、器官ができたら
 * その場所に飛べるコマンドを用意してほしい」)。遊びの決まりには触れない。
 * <ul>
 *   <li>知らせ: 司令塔が芽吹かせた森の器官・建物の乗っ取りの器官・亀裂の巣と根を送り込む器官ができたとき、権限 2 のプレイヤーのチャットへ
 *       「器官ができた: 種類・大小・座標 [飛ぶ]」。[飛ぶ] は押すと飛ぶコマンドが走る。{@code /ov perf organ notify on|off}。
 *       既定は off【仮】で、再起動で off に戻る(保存しない)。</li>
 *   <li>飛ぶ: 器官の中ではなく、器官全体が見える少し離れた所へ、器官の方を向いて({@link #viewFrom})。</li>
 * </ul>
 */
public final class OrganNotices {
    private static final String K = "commands.overgrown_vegetation.organ_notice.";
    /** 知らせを出すか(再起動まで。既定 off【仮】)。 */
    private static boolean enabled;

    private OrganNotices() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** 森の器官(司令塔・乗っ取り)ができた。 */
    public static void organ(ServerLevel level, ForestOrgans.Organ o) {
        if (!enabled || o == null) return;
        Component kind = Component.translatable("commands.overgrown_vegetation.forest.kind." + o.kind.id);
        Component size = Component.translatable(K + (o.small ? "small" : "big"));
        Component where = Component.translatable(K + (o.takeover != null ? "takeover" : "forest"));
        String y = o.resolved() ? Integer.toString(o.y) : "?";
        send(level, Component.translatable(K + "organ", where, kind, size, o.x, y, o.z), "/ov perf organ tp " + o.id);
    }

    /** 亀裂の巣({@code borer} = false)・根を送り込む器官(true)ができた。 */
    public static void fissure(ServerLevel level, int id, int x, int z, boolean borer) {
        if (!enabled) return;
        send(level, Component.translatable(K + (borer ? "borer" : "fissure"), id, x, z),
                "/ov perf fissure tp " + id + (borer ? " borer" : ""));
    }

    private static void send(ServerLevel level, Component text, String command) {
        MutableComponent button = Component.translatable(K + "button").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
        Component line = Component.empty().append(text).append(" ").append(button);
        lastSent = command;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) p.sendSystemMessage(line);
        }
    }

    /** 最後に知らせたコマンド(GameTest 用。知らせを出していなければ前のまま)。 */
    static String lastSent;

    // ---------------------------------------------------------------- 飛ぶ先

    /** 飛ぶ先: [立つ所, 向き(yaw, pitch)]。 */
    public record View(Vec3 at, float yaw, float pitch) {
    }

    /**
     * 器官全体が見える少し離れた所。器官の大きさ(払う円の半径。読めなければ大 30・小 10)+ 8 だけ、今いる方へ離れる。
     * 飛べる人(クリエイティブ・観戦)は器官を見下ろす高さ、そうでなければその場の一番上(木の上を含む)に立たせる。
     */
    public static View viewFrom(ServerLevel level, ForestOrgans.Organ o, Vec3 from, boolean canFly) {
        int r = o.small ? 10 : 30;
        if (o.takeover == null) {
            OrganBlueprint bp = OrganWorld.blueprint(o);
            if (bp != null) r = OrganClearing.radius(bp, o.small);
        } else {
            r = o.small ? 8 : 18;
        }
        int baseY = o.resolved() ? o.y : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, o.x, o.z);
        Vec3 center = new Vec3(o.x + 0.5D, baseY + r * 0.3D, o.z + 0.5D);
        Vec3 dir = new Vec3(from.x - center.x, 0.0D, from.z - center.z);
        if (dir.lengthSqr() < 1.0D) dir = new Vec3(1.0D, 0.0D, 1.0D);
        dir = dir.normalize();
        double dist = r + 8.0D;
        int x = Mth.floor(center.x + dir.x * dist), z = Mth.floor(center.z + dir.z * dist);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        double y = canFly ? Math.max(top + 1.0D, baseY + r * 0.6D + 4.0D) : top;
        Vec3 at = new Vec3(x + 0.5D, y, z + 0.5D);
        Vec3 look = center.subtract(at.add(0.0D, 1.62D, 0.0D));
        float yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG));
        return new View(at, yaw, pitch);
    }

    /** 器官を見に飛ばす。 */
    public static void tp(ServerLevel level, ServerPlayer p, ForestOrgans.Organ o) {
        boolean fly = p.getAbilities().mayfly;
        View v = viewFrom(level, o, p.position(), fly);
        if (fly) p.getAbilities().flying = true;
        p.onUpdateAbilities();
        p.teleportTo(level, v.at().x, v.at().y, v.at().z, v.yaw(), v.pitch());
    }
}
