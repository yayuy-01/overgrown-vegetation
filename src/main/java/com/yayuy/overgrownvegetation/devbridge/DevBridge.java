package com.yayuy.overgrownvegetation.devbridge;

import com.mojang.logging.LogUtils;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.FileUtil;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Claude が自分で起動したクライアントを、外からファイルで操作する開発用の口(2026-10-02。ユーザーが
 * 「runClient を Claude が勝手に起動してよい」と了承)。使い方は {@code tools/claude-client/README.md}。
 *
 * <p>{@code gradlew.bat runClaudeClient}(ゲームフォルダ {@code runs/claude}、{@code -Dov.devbridge=true})のときだけ動く。
 * ユーザーの {@code runClient} では何もしない。配布 jar にはパッケージごと入らない(build.gradle の jar で除外)。
 *
 * <p>{@code runs/claude/devbridge/in.txt} に 1 行 1 命令で<b>追記</b>すると、上から順に 1 tick に 1 つずつ実行し、
 * 結果を {@code devbridge/out.log} に追記する。前提が満たされない命令(ワールドに入る前の {@code cmd} など)は
 * 満たされるまで待つ。動いている間に in.txt を消したり書き換えたりしない(どこまで読んだかが行番号なので)。
 * 起動したときに前回の in.txt / out.log は {@code *.prev} に回す。
 *
 * <ul>
 *   <li>{@code newworld [名前] [normal|flat] [シード]} — タイトル画面から新しいワールドを作って入る(クリエイティブ・コマンド可・ノーマル)</li>
 *   <li>{@code open <フォルダ名>} — 既存のワールドに入る</li>
 *   <li>{@code cmd <コマンド>} — 統合サーバーでプレイヤーとして権限 4 で実行。返ってきた文言を out.log へ</li>
 *   <li>{@code shot [名前]} — スクリーンショット({@code runs/claude/screenshots/<名前>.png})。書き終えたら SHOT を書く</li>
 *   <li>{@code wait <tick>} — 次の命令まで待つ(20 tick = 1 秒)</li>
 *   <li>{@code hud on|off} — 手・ホットバーなどの表示</li>
 *   <li>{@code look <yaw> <pitch>} — 視点の向き</li>
 *   <li>{@code camera first|back|front} — 一人称 / 三人称(後ろから)/ 三人称(前から)。自分の装備を撮るときに</li>
 *   <li>{@code interact <エンティティ id>} — 6 ブロック以内で一番近いその種類を右クリック(サーバーの届く距離は約 3)</li>
 *   <li>{@code attack <エンティティ id>} — 同じく殴る</li>
 *   <li>{@code respawn} — 死んでいたらリスポーン(死亡画面のボタンと同じ)。先に {@code cmd gamerule doImmediateRespawn true} にしておくと画面自体が出ない</li>
 *   <li>{@code status} — 画面・次元・座標・FPS・エンティティ数</li>
 *   <li>{@code echo <文字>} — out.log に目印を書く(バッチの終わりの合図に)</li>
 *   <li>{@code close} — 開いている画面を閉じる</li>
 *   <li>{@code leave} — 保存してタイトル画面へ戻る</li>
 *   <li>{@code quit} — ゲームを閉じる</li>
 * </ul>
 * 状態が変わると {@code TITLE}(タイトル画面)・{@code READY}(ワールドが見えている)・{@code SCREEN}(画面の切り替わり)を書く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class DevBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("ov.devbridge");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** newworld / open / leave のあと、次の状態になるまで待つ上限(5 分)。 */
    private static final long AWAIT_LIMIT_TICKS = 20L * 60 * 5;

    /** コマンドの返事を out.log へ流す。プレイヤーのチャットには出さない。 */
    private static final CommandSource OUTPUT = new CommandSource() {
        @Override
        public void sendSystemMessage(Component component) {
            log("  | " + component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };

    private static Path dir;
    private static boolean started;
    private static long tick;
    private static long waitUntil;
    /** {@code hold} で右クリック(使う)を押し続ける tick の終わり(0 = 押していない。2026-10-08、火炎放射器の確かめ用)。 */
    private static long holdUntil;
    /** sneak <tick>(2026-10-22、クリエイティブで飛びながら下りる確かめ): スニークを押している終わりの tick。 */
    private static long sneakUntil;
    /** walk <tick>(2026-10-23、シダの胞子の確かめ): 前へ歩くキーを押している終わりの tick。 */
    private static long walkUntil;
    private static List<String> lines = List.of();
    private static long lastSize = -1;
    private static long lastModified = -1;
    /** in.txt の次に実行する行(0 始まり)。 */
    private static int next;
    private static boolean onTitle;
    private static boolean inWorld;
    private static String lastScreen = "";
    /** "world" か "title" を待っている間は命令を進めない。 */
    private static String awaiting;
    private static long awaitingSince;
    /** 前提待ちの理由(同じ理由を毎 tick 書かない)。 */
    private static String waitingFor;

    private DevBridge() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        tick++;
        if (!started && !start(mc)) return;
        // 始められなかった(別のクライアントが使っている等)ら、次の tick からも何もしない(2026-10-20 まで 2 tick 目で dir の null を踏んで落ちていた)
        if (dir == null) return;
        if (sneakUntil > 0L) {
            // スニーク(クリエイティブで飛んでいるときは下降)の押しっぱなし。hold と同じく毎 tick 押し直す。
            if (tick < sneakUntil && mc.player != null && mc.screen == null) {
                mc.options.keyShift.setDown(true);
            } else {
                mc.options.keyShift.setDown(false);
                sneakUntil = 0L;
                log("SNEAK 終わり");
            }
        }
        if (walkUntil > 0L) {
            if (tick < walkUntil && mc.player != null && mc.screen == null) {
                mc.options.keyUp.setDown(true);
            } else {
                mc.options.keyUp.setDown(false);
                walkUntil = 0L;
                log("WALK 終わり");
            }
        }
        if (holdUntil > 0L) {
            // 押しっぱなしは毎 tick 押し直す(窓が裏に回ると全部のキーが離される)。
            if (tick < holdUntil && mc.player != null && mc.screen == null) {
                mc.options.keyUse.setDown(true);
            } else {
                mc.options.keyUse.setDown(false);
                holdUntil = 0L;
                log("HOLD 終わり");
            }
        }
        watchState(mc);
        if (awaiting != null) {
            // ワールドに入ったかは「画面が閉じた」ではなく「読み込みが終わった」で見る。死んだまま開くと死亡画面が出て
            // READY(画面なし)にならず、5 分待ってしまった(2026-10-02)。死亡画面なら次の respawn で抜けられる。
            boolean loaded = mc.level != null && mc.player != null && mc.getOverlay() == null
                    && !(mc.screen instanceof ReceivingLevelScreen) && !(mc.screen instanceof LevelLoadingScreen);
            boolean done = awaiting.equals("world") ? loaded : onTitle;
            if (done && awaiting.equals("world") && mc.screen != null) log("LOADED 画面 " + mc.screen.getClass().getSimpleName() + " が出ている");
            if (done) {
                awaiting = null;
            } else if (tick - awaitingSince > AWAIT_LIMIT_TICKS) {
                log("ERR " + awaiting + " を 5 分待っても来ない。待つのをやめて次の命令へ");
                awaiting = null;
            } else {
                return;
            }
        }
        if (tick % 5 == 0) reload();
        if (tick < waitUntil || next >= lines.size()) return;
        String line = lines.get(next).strip();
        if (line.isEmpty() || line.startsWith("#")) {
            next++;
            return;
        }
        try {
            String blocked = run(mc, line);
            if (blocked == null) {
                next++;
                waitingFor = null;
            } else if (!blocked.equals(waitingFor)) {
                waitingFor = blocked;
                log("WAIT-FOR " + blocked + " : " + line);
            }
        } catch (Exception e) {
            log("ERR " + line + " → " + e);
            next++;
            waitingFor = null;
        }
    }

    /** 最初の tick に 1 回。前回の in.txt / out.log を *.prev に回し、空の in.txt を作る。 */
    private static boolean start(Minecraft mc) {
        started = true;
        try {
            dir = mc.gameDirectory.toPath().resolve("devbridge");
            Files.createDirectories(dir);
            Path pid = dir.resolve("running.pid");
            String me = Long.toString(ProcessHandle.current().pid());
            // 別のクライアントが同じフォルダで動いていたら、命令のファイルを回さずに止める(2026-10-16、2 つのセッションのクライアントが
            // 同じ in.txt を読んでぶつかった。後から起動した方が前の in.txt を回し、先に終わった方が running.pid を消していた)。
            if (Files.exists(pid)) {
                try {
                    long other = Long.parseLong(Files.readString(pid, StandardCharsets.UTF_8).trim());
                    if (other != ProcessHandle.current().pid() && ProcessHandle.of(other).map(ProcessHandle::isAlive).orElse(false)) {
                        LOGGER.error("devbridge: 別のクライアント(pid {})が {} を使っているので、このクライアントでは命令を受けない", other, dir);
                        dir = null;
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                    // 壊れた pid は上書きする
                }
            }
            rotate("in.txt", "in.prev.txt");
            rotate("out.log", "out.prev.log");
            Files.writeString(dir.resolve("in.txt"), "", StandardCharsets.UTF_8);
            Files.writeString(pid, me, StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 自分の pid が書いてあるときだけ消す(後から別のクライアントが書いたものは残す)。
                    if (Files.exists(pid) && me.equals(Files.readString(pid, StandardCharsets.UTF_8).trim())) {
                        Files.delete(pid);
                    }
                } catch (IOException ignored) {
                    // 次の起動で上書きされる
                }
            }, "devbridge-pid-cleanup"));
            log("START pid=" + ProcessHandle.current().pid() + " dir=" + dir.toAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.error("devbridge: {} を用意できないので止める", dir, e);
            dir = null;
            return false;
        }
    }

    private static void rotate(String from, String to) throws IOException {
        Path src = dir.resolve(from);
        if (Files.exists(src)) Files.move(src, dir.resolve(to), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void watchState(Minecraft mc) {
        boolean title = mc.level == null && mc.getOverlay() == null && mc.screen instanceof TitleScreen;
        if (title && !onTitle) log("TITLE");
        onTitle = title;

        IntegratedServer server = mc.getSingleplayerServer();
        boolean world = mc.level != null && mc.player != null && server != null
                && mc.getOverlay() == null && mc.screen == null;
        if (world && !inWorld) log("READY " + server.getWorldData().getLevelName() + " " + mc.level.dimension().location());
        inWorld = world;

        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        if (!screen.equals(lastScreen)) {
            log("SCREEN " + screen);
            lastScreen = screen;
        }
    }

    /** in.txt が変わっていれば読み直す(追記だけの約束)。 */
    private static void reload() {
        Path in = dir.resolve("in.txt");
        try {
            if (!Files.exists(in)) return;
            long size = Files.size(in);
            long modified = Files.getLastModifiedTime(in).toMillis();
            if (size == lastSize && modified == lastModified) return;
            lastSize = size;
            lastModified = modified;
            lines = Files.readAllLines(in, StandardCharsets.UTF_8);
            if (next > lines.size()) {
                log("ERR in.txt が短くなった(" + lines.size() + " 行 < 読んだ " + next + " 行)。追記だけにすること。続きは末尾から");
                next = lines.size();
            }
        } catch (IOException e) {
            // 書いている途中に当たることがある。次の回に読み直す
            lastSize = -1;
        }
    }

    /** 1 命令を実行する。前提がまだなら、その理由を返して待つ(行は進めない)。 */
    /** skyboss break …(サーバーの側で)。 */
    private static void skybossBreak(net.minecraft.server.level.ServerLevel level, com.yayuy.overgrownvegetation.skyboss.SkyBossData data,
                                     com.yayuy.overgrownvegetation.skyboss.SkyBossShapes s, String[] a) {
        if (!data.isActive() || s == null) {
            log("SKYBOSS BREAK 居ない・形が無い");
            return;
        }
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = data.flight();
        com.yayuy.overgrownvegetation.skyboss.SkyBossPose ps = new com.yayuy.overgrownvegetation.skyboss.SkyBossPose();
        ps.parts = data.parts();
        ps.compute(f, f.x, f.y, f.z, f.bank, f.tick, true);
        java.util.List<Integer> buds = new java.util.ArrayList<>();
        boolean rod = a[1].equals("rod");
        if (a[1].equals("buds") && a.length >= 3) {
            int n = Integer.parseInt(a[2]);
            for (int i = 0; i < s.budCount && buds.size() < n; i++) {
                if (data.parts().budSolid(i)) {
                    buds.add(i);
                }
            }
        } else if (!rod) {
            buds.add(a[1].matches("\\d+") ? Integer.parseInt(a[1]) : s.budIndex(a[1]));
        }
        StringBuilder done = new StringBuilder();
        if (rod) {
            int ci = ps.rodFirstCapsule() + (s.rodPts.length - 1) / 2;
            boolean ok = data.breakPart(level, com.yayuy.overgrownvegetation.skyboss.SkyBossPose.PART_ROD, -1,
                    (ps.cA[ci * 3] + ps.cB[ci * 3]) * 0.5, (ps.cA[ci * 3 + 1] + ps.cB[ci * 3 + 1]) * 0.5, (ps.cA[ci * 3 + 2] + ps.cB[ci * 3 + 2]) * 0.5);
            done.append(ok ? " rod" : " rod(もう壊れている)");
        }
        for (int i : buds) {
            if (i < 0 || i >= s.budCount) {
                done.append(" 蕾の名前が違う");
                continue;
            }
            boolean ok = data.breakPart(level, com.yayuy.overgrownvegetation.skyboss.SkyBossPose.PART_BUD, i, ps.spC[i * 3], ps.spC[i * 3 + 1], ps.spC[i * 3 + 2]);
            done.append(' ').append(s.budName[i]).append(ok ? "" : "(もう壊れている)");
        }
        log(String.format(Locale.ROOT, "SKYBOSS BREAK%s → 体力 %.0f・壊れた蕾 %d・避雷針 %s (tick %d)", done, data.health(), data.parts().brokenBuds(),
                data.parts().rodSolid() ? "元どおり" : "壊れている", level.getGameTime()));
    }

    private static String run(Minecraft mc, String line) throws IOException {
        String[] parts = line.split("\\s+", 2);
        String op = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (op) {
            case "newworld" -> {
                if (!onTitle) return "タイトル画面";
                newWorld(mc, arg);
                await("world");
            }
            case "open" -> {
                if (!onTitle) return "タイトル画面";
                log("OPEN " + arg);
                await("world");
                mc.createWorldOpenFlows().openWorld(arg, () -> {
                    log("ERR open に失敗: " + arg);
                    awaiting = null;
                    mc.setScreen(new TitleScreen());
                });
            }
            case "cmd" -> {
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || mc.player == null || server == null) return "ワールドの中";
                command(server, mc.player.getUUID(), arg);
            }
            case "shot" -> shot(mc, arg);
            case "wait" -> waitUntil = tick + Long.parseLong(arg);
            case "hold" -> {
                // 右クリック(使う)を <tick> だけ押し続ける。押し始めは 1 回のクリックとして数える(長押しで使う道具が始まる)。
                // 次の命令は待たない(押している間に look・shot ができる。待つなら続けて wait)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                net.minecraft.client.KeyMapping.click(mc.options.keyUse.getKey());
                mc.options.keyUse.setDown(true);
                holdUntil = tick + Math.max(1L, ticks);
                log("HOLD " + ticks + " tick");
            }
            case "walk" -> {
                // 前へ歩くキーを <tick> だけ押し続ける(本当に歩くので、動いた分が xo との差に出る。次の命令は待たない)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                mc.options.keyUp.setDown(true);
                walkUntil = tick + Math.max(1L, ticks);
                log("WALK " + ticks + " tick");
            }
            case "sneak" -> {
                // スニークを <tick> だけ押し続ける(クリエイティブで飛んでいれば下降。次の命令は待たない)。
                if (mc.level == null || mc.player == null) return "ワールドの中";
                long ticks = Long.parseLong(arg);
                if (mc.player.getAbilities().mayfly && !mc.player.getAbilities().flying) {
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                }
                mc.options.keyShift.setDown(true);
                sneakUntil = tick + Math.max(1L, ticks);
                log("SNEAK " + ticks + " tick");
            }
            case "hud" -> {
                mc.options.hideGui = arg.equalsIgnoreCase("off");
                log("HUD " + (mc.options.hideGui ? "off" : "on"));
            }
            case "key" -> {
                // 割り当てたキーを 1 回押したことにする(名前は KeyMapping の名前。例 key key.overgrown_vegetation.sunlit_lantern。2026-10-16)
                String name = arg.trim();
                for (net.minecraft.client.KeyMapping mapping : mc.options.keyMappings) {
                    if (mapping.getName().equals(name)) {
                        net.minecraft.client.KeyMapping.click(mapping.getKey());
                        log("KEY " + name + " → " + mapping.getKey().getName());
                        return null;
                    }
                }
                return "そのキーの名前は無い: " + name;
            }
            case "wind" -> {
                // 汚染地域の風(CanopyWind): gust = カメラの風上に強い突風を 1 つ / calm = 突風を消して 1 分凪 / status = 今の風。2026-10-15
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                String what = arg.trim().toLowerCase(java.util.Locale.ROOT);
                if (what.equals("gust")) {
                    com.yayuy.overgrownvegetation.client.CanopyWind.forceGust(player.getX(), player.getZ());
                } else if (what.equals("calm")) {
                    com.yayuy.overgrownvegetation.client.CanopyWind.calm();
                } else if (what.startsWith("hide")) {
                    // 光の部品を隠して撮り比べる(2026-10-16): 1 = 筋の芯・2 = 筋のにじみ・4 = 筋の足元のにじみ・8 = 光る胞子・16 = 木漏れ日の筋・32 = 木漏れ日の斑点・64 = 全部の面の林冠の影(案 C。切ると地面の影の板に戻る)・128 = 草・幹の斑点(案 C)・4096 = 最高で加算の光を粒の層へ(前の描き先、2026-10-24)。0 で全部出す。
                    com.yayuy.overgrownvegetation.client.CanopyLight.debugHide = Integer.parseInt(what.substring(4).trim());
                    log("WIND hide " + com.yayuy.overgrownvegetation.client.CanopyLight.debugHide);
                    return null;
                } else if (what.startsWith("omen")) {
                    // 前触れ(TD の群れが近づくと発光が遠くから消える)の強さを直に決める(2026-10-16)。群れが無ければ 45 秒保ってから戻る。
                    com.yayuy.overgrownvegetation.client.TdOmen.forceForDev(Float.parseFloat(what.substring(4).trim()));
                    log("WIND omen " + com.yayuy.overgrownvegetation.client.TdOmen.strength());
                    return null;
                } else if (what.startsWith("leafhist")) {
                    // 周りの地面から太陽へたどった葉のブロックの数と樹冠の層の数の分布(2026-10-16、濃い森の光の数値決め)。既定は半径 16。
                    String r = what.substring(8).trim();
                    log("WIND leafhist " + com.yayuy.overgrownvegetation.client.CanopyLight.leafHistogram(r.isEmpty() ? 16 : Integer.parseInt(r)));
                    return null;
                } else if (what.startsWith("column") || what.startsWith("bulge") || what.startsWith("dive")) {
                    // 雲の面(2026-10-19、sky.CloudSky#columnAt・sky.CloudSurface): column <x> <z> = 雲の柱を書く / bulge <x> <z> <半径> <盛り上がる tick> <保つ tick>
                    // / dive <x> <z> <半径> <埋まり始め tick> <埋まりきり tick> = 雲の柱を貫く丸い穴。返事は WIND column|bulge|dive …
                    String[] p = what.split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    double px = Double.parseDouble(p[1]);
                    double pz = Double.parseDouble(p[2]);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        if (p[0].equals("column")) {
                            com.yayuy.overgrownvegetation.sky.CloudSky.CloudColumn c = com.yayuy.overgrownvegetation.sky.CloudSky.columnAt(level, px, pz, 0.0F);
                            log("WIND column " + (c == null ? "雲なし" : "底 " + c.bottom() + " 天辺 " + c.top() + " 厚さ " + c.thickness() + " 雲海 " + c.seaBottom() + "〜" + c.seaTop()));
                        } else if (p[0].equals("bulge")) {
                            log("WIND bulge " + com.yayuy.overgrownvegetation.sky.CloudSurface.bulge(level, px, pz, Float.parseFloat(p[3]),
                                    Integer.parseInt(p[4]), Integer.parseInt(p[5])));
                        } else {
                            log("WIND dive " + com.yayuy.overgrownvegetation.sky.CloudSurface.hole(level, px, pz, Double.NaN, Double.NaN, Float.parseFloat(p[3]), 10,
                                    Integer.parseInt(p[4]), Integer.parseInt(p[5])));
                        }
                    });
                    return null;
                } else if (what.startsWith("puff")) {
                    // 吐いた雲(2026-10-19、sky.CloudPuffs#puff): puff <x> <y> <z> <半径> <高さ> <長さ tick>。返事は雲の番号。
                    String[] p = what.substring(4).trim().split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    double px = Double.parseDouble(p[0]);
                    double py = Double.parseDouble(p[1]);
                    double pz = Double.parseDouble(p[2]);
                    float pr = Float.parseFloat(p[3]);
                    float ph = Float.parseFloat(p[4]);
                    int pl = Integer.parseInt(p[5]);
                    server.execute(() -> log("WIND puff " + com.yayuy.overgrownvegetation.sky.CloudPuffs.puff(server.overworld(), px, py, pz, pr, ph, pl)));
                    return null;
                } else if (what.startsWith("bolt")) {
                    // 決めた 2 点の間の雷(2026-10-19、CloudLightning#strikeBetween): bolt <x1> <y1> <z1> <x2> <y2> <z2> [1 = 即死]。返事は前兆の tick。
                    String[] p = what.substring(4).trim().split("\\s+");
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    Vec3 from = new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                    Vec3 to = new Vec3(Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]));
                    boolean harmful = p.length > 6 && p[6].equals("1");
                    server.execute(() -> log("WIND bolt 前兆 " + com.yayuy.overgrownvegetation.sky.CloudLightning.strikeBetween(server.overworld(), from, to, harmful)));
                    return null;
                } else if (what.startsWith("holeshot")) {
                    // 雲の穴(2026-10-18): プレイヤーの所から本物の砲弾(攻城砲)をほぼ真上へ撃つ(着弾しても地形は壊れない)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return "統合サーバー";
                    Vec3 at = player.position();
                    float yaw = player.getYRot();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        double h = 1.2;
                        Vec3 v = new Vec3(-Math.sin(Math.toRadians(yaw)) * h, 9.0, Math.cos(Math.toRadians(yaw)) * h);
                        com.yayuy.overgrownvegetation.siege.ShellTracker.get(level).fire(level, at.add(0.0, 2.0, 0.0), v);
                    });
                    log("WIND holeshot " + Math.round(at.x) + " " + Math.round(at.y) + " " + Math.round(at.z));
                    return null;
                } else if (what.startsWith("hole")) {
                    // 雲の穴(2026-10-18): hole <x> <y> <z> [半径] でその点を縦に貫く穴を開ける。
                    String[] p = what.substring(4).trim().split("\\s+");
                    double hx = Double.parseDouble(p[0]);
                    double hy = Double.parseDouble(p[1]);
                    double hz = Double.parseDouble(p[2]);
                    float hr = p.length > 3 ? Float.parseFloat(p[3]) : 24.0F;
                    com.yayuy.overgrownvegetation.client.CloudHoles.devPunch(hx, hy, hz, hr);
                    log("WIND hole " + com.yayuy.overgrownvegetation.client.CloudHoles.count());
                    return null;
                } else if (what.equals("leyflow")) {
                    // 地脈の流れをすぐに起こす(2026-10-16)。
                    com.yayuy.overgrownvegetation.client.LeyFlow.startNow();
                    int[] near = com.yayuy.overgrownvegetation.client.LeyFlow.nearestCell(player.getX(), player.getZ());
                    log("WIND leyflow いちばん近い線のマス " + (near == null ? "無し" : near[0] + " " + near[1]) + " " + com.yayuy.overgrownvegetation.client.LeyFlow.describeChunks(player.chunkPosition().x, player.chunkPosition().z));
                    return null;
                } else if (what.equals("gaps")) {
                    // 近くの林冠の穴(木を植えない輪、TreeSpacing#canopyGap)の中心と半径を、近い順に 6 つまで書く(2026-10-16)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND gaps: 統合サーバーが無い");
                        return null;
                    }
                    long seed = server.overworld().getSeed();
                    double chance = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.treeCanopyGapChance.get();
                    int grid = com.yayuy.overgrownvegetation.corruption.TreeSpacing.GAP_GRID;
                    int gx = Math.floorDiv((int) Math.floor(player.getX()), grid);
                    int gz = Math.floorDiv((int) Math.floor(player.getZ()), grid);
                    java.util.List<double[]> gaps = new java.util.ArrayList<>();
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dx = -3; dx <= 3; dx++) {
                            double[] g = com.yayuy.overgrownvegetation.corruption.TreeSpacing.canopyGap(seed, gx + dx, gz + dz, chance);
                            if (g != null) {
                                gaps.add(g);
                            }
                        }
                    }
                    gaps.sort(java.util.Comparator.comparingDouble(g -> (g[0] - player.getX()) * (g[0] - player.getX()) + (g[1] - player.getZ()) * (g[1] - player.getZ())));
                    StringBuilder sb = new StringBuilder("WIND gaps");
                    for (int i = 0; i < Math.min(6, gaps.size()); i++) {
                        double[] g = gaps.get(i);
                        sb.append(String.format(java.util.Locale.ROOT, " (%.1f %.1f r%.1f)", g[0], g[1], g[2]));
                    }
                    log(sb.toString());
                    return null;
                } else if (what.startsWith("layley")) {
                    // 周り r ブロック(既定 24)の本物の地脈の線(強さ 0.5 以上)の上の地面を地脈ブロックにする(2026-10-16、極相の密林の確かめ用)。
                    String rs = what.substring(6).trim();
                    int radius = rs.isEmpty() ? 24 : Integer.parseInt(rs);
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND layley: 統合サーバーが無い");
                        return null;
                    }
                    int px = net.minecraft.util.Mth.floor(player.getX());
                    int pz = net.minecraft.util.Mth.floor(player.getZ());
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        double[] flow = new double[4];
                        int placed = 0;
                        for (int x = px - radius; x <= px + radius; x++) {
                            for (int z = pz - radius; z <= pz + radius; z++) {
                                if (!com.yayuy.overgrownvegetation.corruption.LeyVeinField.flowAt(seed, x, z, flow) || flow[0] < 0.5) {
                                    continue;
                                }
                                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                                level.setBlock(new net.minecraft.core.BlockPos(x, y, z), com.yayuy.overgrownvegetation.registry.ModBlocks.LEY_VEIN.get().defaultBlockState(), 3);
                                placed++;
                            }
                        }
                        log("WIND layley " + placed);
                    });
                    return null;
                } else if (what.equals("crystal")) {
                    // 足元から 3 ブロック先にエネルギー結晶の柱(高さ 5)を立てる(2026-10-16、結晶の共鳴の確かめ用)。
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return null;
                    }
                    net.minecraft.core.BlockPos base = player.blockPosition().offset(3, 0, 0);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        for (int dy = 0; dy < 5; dy++) {
                            level.setBlock(base.above(dy), com.yayuy.overgrownvegetation.registry.ModBlocks.ENERGY_CRYSTAL.get().defaultBlockState(), 3);
                        }
                        log("WIND crystal " + base.getX() + " " + base.getY() + " " + base.getZ());
                    });
                    return null;
                } else if (what.equals("leynear")) {
                    // いちばん近い地脈の線の点と、地脈探しの針の先を出す(汚染の有無に関係なく。2026-10-22、地脈の花の確かめ)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND leynear: 統合サーバーが無い");
                        return null;
                    }
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        int[] p = com.yayuy.overgrownvegetation.corruption.LeyVeinField.nearestLinePoint(level.getSeed(), from.getX(), from.getZ(), 256);
                        net.minecraft.core.BlockPos t = com.yayuy.overgrownvegetation.item.LeyCompassItem.target(level, from);
                        log("WIND leynear " + (p == null ? "なし" : p[0] + " " + p[1]) + " 針の先 " + (t == null ? "なし" : t.getX() + " " + t.getZ()));
                    });
                    return null;
                } else if (what.startsWith("findblock ")) {
                    // 読み込み済みの周り(半径 r、地表の上下 8)で、そのブロックのいちばん近い 1 つ(2026-10-22、天然の地脈の結晶を撮る)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    String[] a = what.substring("findblock ".length()).trim().split("\\s+");
                    if (server == null || a.length < 2) {
                        log("WIND findblock: 使い方 findblock <id> <半径>");
                        return null;
                    }
                    net.minecraft.world.level.block.Block target = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(a[0]));
                    int r = Integer.parseInt(a[1]);
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.core.BlockPos best = null;
                        double bestD = Double.MAX_VALUE;
                        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
                        for (int dx = -r; dx <= r; dx++) {
                            for (int dz = -r; dz <= r; dz++) {
                                int x = from.getX() + dx;
                                int z = from.getZ() + dz;
                                if (!level.hasChunk(x >> 4, z >> 4)) {
                                    continue;
                                }
                                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                                for (int y = top - 8; y <= top + 2; y++) {
                                    if (level.getBlockState(m.set(x, y, z)).is(target)) {
                                        double d = (double) dx * dx + (double) dz * dz;
                                        if (d < bestD) {
                                            bestD = d;
                                            best = m.immutable();
                                        }
                                    }
                                }
                            }
                        }
                        log("WIND findblock " + (best == null ? "なし" : best.getX() + " " + best.getY() + " " + best.getZ()));
                    });
                    return null;
                } else if (what.startsWith("leybiome ")) {
                    // そのバイオームの上を通る地脈の線の点を探す(半径 1024、8 おき。読み込まずにバイオームの源を引く。2026-10-22)
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND leybiome: 統合サーバーが無い");
                        return null;
                    }
                    String biomeId = what.substring("leybiome ".length()).trim();
                    net.minecraft.core.BlockPos from = player.blockPosition();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        var gen = level.getChunkSource().getGenerator();
                        var sampler = level.getChunkSource().randomState().sampler();
                        for (int r = 0; r <= 1024; r += 8) {
                            for (int dx = -r; dx <= r; dx += 8) {
                                for (int dz = -r; dz <= r; dz += 8) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                        continue;
                                    }
                                    int x = from.getX() + dx;
                                    int z = from.getZ() + dz;
                                    int[] p = com.yayuy.overgrownvegetation.corruption.LeyVeinField.nearestLinePoint(seed, x, z, 16);
                                    if (p == null || Math.abs(p[0] - x) > 6 || Math.abs(p[1] - z) > 6) {
                                        continue;
                                    }
                                    var b = gen.getBiomeSource().getNoiseBiome(p[0] >> 2, 64 >> 2, p[1] >> 2, sampler);
                                    if (b.unwrapKey().map(k -> k.location().toString().equals(biomeId)).orElse(false)) {
                                        log("WIND leybiome " + p[0] + " " + p[1]);
                                        return;
                                    }
                                }
                            }
                        }
                        log("WIND leybiome なし");
                    });
                    return null;
                } else if (what.equals("gotoley")) {
                    // いちばん近い「過繁茂の地域を通る地脈の線」の上へ動く(統合サーバーで線の場を引く。半径 24 チャンク)。2026-10-16
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        log("WIND gotoley: 統合サーバーが無い");
                        return null;
                    }
                    java.util.UUID id = player.getUUID();
                    int pcx = player.chunkPosition().x;
                    int pcz = player.chunkPosition().z;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        long seed = level.getSeed();
                        double[] flow = new double[4];
                        for (int r = 0; r <= 24; r++) {
                            for (int dz = -r; dz <= r; dz++) {
                                for (int dx = -r; dx <= r; dx++) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                        continue;
                                    }
                                    int cx = pcx + dx;
                                    int cz = pcz + dz;
                                    if (!com.yayuy.overgrownvegetation.corruption.LeyVeinField.hasVeinInChunk(seed, cx, cz)) {
                                        continue;
                                    }
                                    for (int i = 0; i < 256; i++) {
                                        int x = (cx << 4) + (i & 15);
                                        int z = (cz << 4) + (i >> 4);
                                        if (!com.yayuy.overgrownvegetation.corruption.LeyVeinField.flowAt(seed, x, z, flow) || flow[0] < 0.8) {
                                            continue;
                                        }
                                        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                                        // 巨大樹の枝の上などへ行かないよう、地面の高さはプレイヤーの高さの近くから下へ探す。
                                        int startY = Math.min(y, (int) player.getY() + 8);
                                        for (y = startY; y > level.getMinBuildHeight() + 1; y--) {
                                            if (level.getBlockState(new net.minecraft.core.BlockPos(x, y - 1, z)).canOcclude()) {
                                                break;
                                            }
                                        }
                                        if (!com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(
                                                level.getBiome(new net.minecraft.core.BlockPos(x, y, z)))) {
                                            break;
                                        }
                                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                                        if (sp != null) {
                                            sp.teleportTo(level, x + 0.5, y + 1.0, z + 0.5, sp.getYRot(), sp.getXRot());
                                        }
                                        log("WIND gotoley " + x + " " + y + " " + z + " 向き " + String.format("%.2f %.2f", flow[1], flow[2]));
                                        return;
                                    }
                                }
                            }
                        }
                        log("WIND gotoley: 24 チャンク以内に過繁茂を通る線が無い");
                    });
                    return null;
                } else if (what.equals("lookshaft") || what.equals("gotoshaft") || what.equals("undershaft")) {
                    // いちばん近い光の筋の中ほどへカメラを向ける(動きを撮るとき、筋が画面に入るように)。
                    // gotoshaft は、筋の中ほどから水平に 10〜14 ブロック離れて(横から見る)、筋までの間に何も無い所へ先に動く(観戦モードで)。
                    net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
                    net.minecraft.world.phys.Vec3[] ends = com.yayuy.overgrownvegetation.client.CanopyLight.nearestShaftEnds(eye);
                    if (ends == null) {
                        log("WIND 光の筋が無い");
                        return null;
                    }
                    net.minecraft.world.phys.Vec3 mid = ends[0].add(ends[1]).scale(0.5);
                    if (what.equals("undershaft")) {
                        // 筋の落ちる所のすぐ脇(1 ブロック)から見上げる(筋の軸に沿って見たときのちらつきの確かめ)。
                        net.minecraft.world.phys.Vec3 under = ends[1].add(0.8, 1.7, 0.0);
                        player.setPos(under.x, under.y - player.getEyeHeight(), under.z);
                        eye = under;
                    }
                    if (what.equals("gotoshaft") && mc.level != null) {
                        net.minecraft.world.phys.Vec3 chosen = null;
                        for (int ring = 10; ring <= 14 && chosen == null; ring += 2) {
                            for (int k = 0; k < 16 && chosen == null; k++) {
                                double a = k * Math.PI / 8.0;
                                net.minecraft.world.phys.Vec3 cand = new net.minecraft.world.phys.Vec3(
                                        mid.x + Math.cos(a) * ring, ends[1].y + 1.7, mid.z + Math.sin(a) * ring);
                                var hitMid = mc.level.clip(new net.minecraft.world.level.ClipContext(cand, mid,
                                        net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                                var hitLow = mc.level.clip(new net.minecraft.world.level.ClipContext(cand, ends[1].add(0, 0.5, 0),
                                        net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                                if (hitMid.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                                        && hitLow.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                                    chosen = cand;
                                }
                            }
                        }
                        if (chosen == null) {
                            log("WIND 筋の見える場所が無い");
                            return null;
                        }
                        player.setPos(chosen.x, chosen.y - player.getEyeHeight(), chosen.z);
                        eye = chosen;
                    }
                    net.minecraft.world.phys.Vec3 d = mid.subtract(eye);
                    float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
                    float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    player.yRotO = yaw;
                    player.xRotO = pitch;
                    log(String.format("WIND 筋へ向けた yaw %.0f pitch %.0f 距離 %.1f", yaw, pitch, d.length()));
                }
                log("WIND " + com.yayuy.overgrownvegetation.client.CanopyWind.describe(player.getX(), player.getZ()));
            }
            case "look" -> {
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                float yaw = Float.parseFloat(a[0]);
                float pitch = a.length > 1 ? Float.parseFloat(a[1]) : player.getXRot();
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.yRotO = yaw;
                player.xRotO = pitch;
                player.setYHeadRot(yaw);
                log(String.format(Locale.ROOT, "LOOK yaw=%.1f pitch=%.1f", yaw, pitch));
            }
            case "camera" -> {
                CameraType type = switch (arg.toLowerCase(Locale.ROOT)) {
                    case "back" -> CameraType.THIRD_PERSON_BACK;
                    case "front" -> CameraType.THIRD_PERSON_FRONT;
                    default -> CameraType.FIRST_PERSON;
                };
                mc.options.setCameraType(type);
                log("CAMERA " + type);
            }
            case "interact", "attack" -> {
                LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null || mc.level == null) return "ワールドの中";
                Entity target = nearest(mc, player, arg);
                if (target == null) {
                    log("ERR " + op + ": 6 ブロック以内に " + arg + " が見えない");
                    return null;
                }
                player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
                String what = String.format(Locale.ROOT, "%s (距離 %.1f)", target.getType().builtInRegistryHolder().key().location(),
                        Math.sqrt(boxDistanceSqr(target.getBoundingBox(), player.getEyePosition())));
                if (op.equals("attack")) {
                    mc.gameMode.attack(player, target);
                    player.swing(InteractionHand.MAIN_HAND);
                    log("ATTACK " + what);
                } else {
                    // バニラの右クリック(Minecraft#startUseItem)と同じ順: interactAt → だめなら interact
                    EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
                    InteractionResult result = mc.gameMode.interactAt(player, target, hit, InteractionHand.MAIN_HAND);
                    if (!result.consumesAction()) result = mc.gameMode.interact(player, target, InteractionHand.MAIN_HAND);
                    if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
                    log("INTERACT " + what + " → " + result);
                }
            }
            case "useblock" -> {
                // ブロックの右クリック(上の面の真ん中を押す)。サーバーの届く距離は約 4.5 なので先に近くへ tp
                LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null || mc.level == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                        Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                Vec3 hitAt = Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0);
                player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                        hitAt, net.minecraft.core.Direction.UP, pos, false);
                InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
                log(String.format(Locale.ROOT, "USEBLOCK %s %s (距離 %.1f) → %s", pos.toShortString(),
                        mc.level.getBlockState(pos).getBlock().builtInRegistryHolder().key().location(),
                        Math.sqrt(player.getEyePosition().distanceToSqr(hitAt)), result));
            }
            case "click" -> {
                // 開いている画面をクリック(GUI の拡大後の座標。押して離す)。画面の大きさも書く
                if (mc.screen == null) {
                    log("ERR click: 画面が開いていない");
                    return null;
                }
                String[] a = arg.split("\\s+");
                double x = Double.parseDouble(a[0]);
                double y = Double.parseDouble(a[1]);
                int button = a.length > 2 ? Integer.parseInt(a[2]) : 0;
                boolean down = mc.screen.mouseClicked(x, y, button);
                boolean up = mc.screen.mouseReleased(x, y, button);
                log(String.format(Locale.ROOT, "CLICK %.1f %.1f (画面 %dx%d) → %s/%s", x, y,
                        mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), down, up));
            }
            case "roots" -> {
                // 根の先端・斜めのアーチを試しに生やす(2026-10-09)。例: roots ground 0 -61 0 8(地面の高さ y・本数)/ roots hang 0 -50 0 8(天井の石の高さ y)/ roots arch 0 -61 0 6
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                String kind = a[0];
                int x0 = Integer.parseInt(a[1]);
                int y0 = Integer.parseInt(a[2]);
                int z0 = Integer.parseInt(a[3]);
                int count = a.length > 4 ? Integer.parseInt(a[4]) : 6;
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(x0 * 31L + z0);
                    net.minecraft.world.level.block.state.BlockState log =
                            com.yayuy.overgrownvegetation.registry.ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
                    it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    int built = 0;
                    for (int i = 0; i < count; i++) {
                        // 横に並べる(14 ブロックおき)
                        int x = x0 + (i % 4) * 14;
                        int z = z0 + (i / 4) * 14;
                        boolean ok;
                        if (kind.equals("hang")) {
                            ok = com.yayuy.overgrownvegetation.corruption.CaveGrowth.buildHangingRootTip(level,
                                    new net.minecraft.core.BlockPos(x, y0, z), random,
                                    com.yayuy.overgrownvegetation.corruption.CaveGrowth.Mode.JUNGLE);
                        } else if (kind.equals("arch")) {
                            double angle = random.nextDouble() * Math.PI * 2.0D;
                            int span = 6 + random.nextInt(8);
                            ok = com.yayuy.overgrownvegetation.corruption.CorruptionArch.build(level,
                                    new net.minecraft.core.BlockPos(x, y0, z),
                                    new net.minecraft.core.BlockPos(x + (int) Math.round(Math.cos(angle) * span), y0,
                                            z + (int) Math.round(Math.sin(angle) * span)),
                                    2 + random.nextInt(3), random.nextInt(2), log, touched, null, null);
                        } else {
                            var shape = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.rollGround(random);
                            var cells = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.plan(x + 0.5D, y0 - 0.5D, z + 0.5D, shape);
                            ok = com.yayuy.overgrownvegetation.corruption.CorruptionRootTip.place(level, cells, log, touched, new java.util.ArrayList<>());
                        }
                        if (ok) built++;
                    }
                    log("ROOTS " + kind + " " + built + "/" + count);
                });
            }
            case "corrupt" -> {
                // 箱の中のブロックを、汚染の本来の経路(CorruptionRegistry)で 1 回ずつ汚染する。例: corrupt 0 -60 0 15 -60 15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int changed = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2],
                            v.length > 3 ? v[3] : v[0], v.length > 4 ? v[4] : v[1], v.length > 5 ? v[5] : v[2])) {
                        net.minecraft.core.BlockPos at = p.immutable();
                        net.minecraft.world.level.block.state.BlockState before = level.getBlockState(at);
                        if (before.isAir() || !com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.canCorrupt(level, at, before)) continue;
                        net.minecraft.world.level.block.state.BlockState after =
                                com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, at, before, level.random);
                        if (com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.noChange(before, after)) {
                            // 置き換えずに覆った(座標の記録)
                            if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at)) changed++;
                            continue;
                        }
                        com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.placeCorrupted(level, at, before, after,
                                net.minecraft.world.level.block.Block.UPDATE_ALL);
                        changed++;
                    }
                    log("CORRUPT " + changed + " マス");
                });
            }
            case "biomeconv" -> {
                // そのブロックを含むチャンクのバイオームを本来の判定で書き換える。例: biomeconv 100 -200 / biomeconv 100 -200 climax。
                // 無印 = 地表の汚染割合で過繁茂か(maybeConvert)、climax = 極相へ(forceClimax)。2026-10-23 湿地・結晶地帯の封印の確かめ
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] b = arg.trim().split("\\s+");
                var chunk = new net.minecraft.world.level.ChunkPos(Integer.parseInt(b[0]) >> 4, Integer.parseInt(b[1]) >> 4);
                boolean climax = b.length > 2 && b[2].equals("climax");
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    if (climax) {
                        com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter.forceClimax(level, chunk);
                    } else {
                        com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter.maybeConvert(level, chunk);
                    }
                    log("BIOMECONV " + chunk + " → " + level.getBiome(chunk.getMiddleBlockPosition(64)).unwrapKey()
                            .map(k -> k.location().toString()).orElse("?"));
                });
            }
            case "count" -> {
                // 箱の中のそのブロックの数を数える(壊さない)。例: count overgrown_vegetation:overgrown_sapling 0 -64 0 63 0 63。2026-10-15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                String[] countArgs = arg.trim().split("\\s+");
                var id = net.minecraft.resources.ResourceLocation.tryParse(countArgs[0]);
                int[] v = java.util.Arrays.stream(countArgs).skip(1).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null || id == null) return;
                    var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                    int n = 0;
                    for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(v[0], v[1], v[2], v[3], v[4], v[5])) {
                        if (level.isLoaded(p) && level.getBlockState(p).is(block)) n++;
                    }
                    log("COUNT " + id + " " + n);
                });
            }
            case "anchors" -> {
                // 木の間隔(TreeSpacing)で木を立てるはずの列に、今なにがあるかを集計する。例: anchors -310 13 -230 93(x1 z1 x2 z2)。2026-10-15
                // 返事は ANCHOR 分類 数 の行。木が立たない理由(上の草・水・覆い・未汚染など)を数で見るため。
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
                    int spacing = cfg.treeSpacing.get();
                    double clearing = cfg.treeClearingChance.get();
                    java.util.Map<String, Integer> tally = new java.util.TreeMap<>();
                    var log = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LOG.get();
                    var sapling = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get();
                    net.minecraft.core.BlockPos.MutableBlockPos p = new net.minecraft.core.BlockPos.MutableBlockPos();
                    for (int x = Math.min(v[0], v[2]); x <= Math.max(v[0], v[2]); x++) {
                        for (int z = Math.min(v[1], v[3]); z <= Math.max(v[1], v[3]); z++) {
                            if (!com.yayuy.overgrownvegetation.corruption.TreeSpacing.allowsTree(level.getSeed(), x, z, spacing, clearing)) continue;
                            // 林冠の穴の輪の中の列は木を立てない(2026-10-16)。木が無い理由として別に数える。
                            if (com.yayuy.overgrownvegetation.corruption.TreeSpacing.inCanopyGap(level.getSeed(), x, z, cfg.treeCanopyGapChance.get())) {
                                tally.merge("林冠の穴", 1, Integer::sum);
                                continue;
                            }
                            if (!level.hasChunk(x >> 4, z >> 4)) { tally.merge("未読み込み", 1, Integer::sum); continue; }
                            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                            String kind = null;
                            for (int y = top; y > top - 48 && kind == null; y--) {
                                p.set(x, y, z);
                                var s = level.getBlockState(p);
                                if (s.is(sapling)) kind = "苗木";
                                else if (s.is(log) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_WOOD.get())) {
                                    // 幹の根元(下が土)なら木、そうでなければ枝・ほかの木の幹
                                    var below = level.getBlockState(p.below());
                                    if (!below.is(log) && !below.isAir() && below.isSolidRender(level, p.below())) kind = "木";
                                } else if (!s.getFluidState().isEmpty()) kind = "水";
                                else if (s.isSolidRender(level, p) && !s.is(log)) {
                                    var above = level.getBlockState(p.above());
                                    kind = "なし: " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()
                                            + " の上 " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(above.getBlock()).getPath();
                                }
                            }
                            tally.merge(kind == null ? "不明" : kind, 1, Integer::sum);
                        }
                    }
                    tally.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                            .forEach(e -> log("ANCHOR " + e.getKey() + " " + e.getValue()));
                    log("ANCHOR 終わり");
                });
            }
            case "grow" -> {
                // 汚染した地面(高さ y のブロック)の上に、汚染の本来の経路(CorruptionSpreader#growSurfacePlants)で下草・苗木を生やす。
                // 苗木の間隔(treeSpacing)もそのまま効く。例: grow 0 -61 0 63 63(x1 y z1 x2 z2)。2026-10-15
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    int saplings = 0;
                    for (int x = Math.min(v[0], v[3]); x <= Math.max(v[0], v[3]); x++) {
                        for (int z = Math.min(v[2], v[4]); z <= Math.max(v[2], v[4]); z++) {
                            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(x, v[1], z);
                            com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growSurfacePlants(level, at, level.getBlockState(at),
                                    com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.surfaceGrowthChance(level, at));
                            if (level.getBlockState(at.above()).is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())) {
                                saplings++;
                            }
                        }
                    }
                    log("GROW 苗木 " + saplings + " 本");
                });
            }
            case "block" -> {
                // そのマスのブロックの状態をサーバーから読んで書く(汚染の置き換え先・向き・開閉の確かめ)。例: block 3 -60 2
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.level == null || server == null) return "ワールドの中";
                int[] v = java.util.Arrays.stream(arg.split("\\s+")).mapToInt(Integer::parseInt).toArray();
                var dimension = mc.level.dimension();
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
                    if (level == null) return;
                    net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(v[0], v[1], v[2]);
                    boolean recorded = com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(level, at);
                    log("BLOCK " + v[0] + " " + v[1] + " " + v[2] + " " + level.getBlockState(at) + (recorded ? " (苔の記録で覆われている)" : ""));
                });
            }
            case "research" -> {
                // 研究のノードを開く(2026-10-24、封印されたレシピを撮る用)。例: research ley_conversion
                IntegratedServer server = mc.getSingleplayerServer();
                if (mc.player == null || server == null) return "ワールドの中";
                java.util.UUID id = mc.player.getUUID();
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayer(id);
                    if (sp == null) return;
                    sp.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.RESEARCH).unlock(arg.trim());
                    com.yayuy.overgrownvegetation.research.ResearchLogic.sync(sp);
                    log("RESEARCH " + arg.trim());
                });
            }
            case "creative" -> {
                // クリエイティブのインベントリを開いてタブを選ぶ。2 つ目は下へのスクロール(0〜1)。例: creative overgrown_vegetation:covered 0.5
                LocalPlayer player = mc.player;
                if (player == null || mc.level == null) return "ワールドの中";
                String[] a = arg.split("\\s+");
                ResourceLocation key = ResourceLocation.tryParse(a[0]);
                net.minecraft.world.item.CreativeModeTab tab = key == null ? null : BuiltInRegistries.CREATIVE_MODE_TAB.get(key);
                if (tab == null) {
                    log("ERR creative: タブが無い " + a[0]);
                    return null;
                }
                var screen = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                        player, mc.level.enabledFeatures(), mc.options.operatorItemsTab().get());
                mc.setScreen(screen);
                try {
                    // selectTab は private。setScreen の init が前回のタブを選び直すので、そのあとで選ぶ
                    var select = net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class
                            .getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                    select.setAccessible(true);
                    select.invoke(screen, tab);
                    if (a.length > 1) screen.getMenu().scrollTo(Float.parseFloat(a[1]));
                    log("CREATIVE " + key + " (" + tab.getDisplayItems().size() + " 個)");
                } catch (ReflectiveOperationException e) {
                    log("ERR creative: " + e);
                }
            }
            case "respawn" -> {
                LocalPlayer player = mc.player;
                if (player == null) return "ワールドの中";
                if (player.isDeadOrDying()) {
                    player.respawn(); // 死亡画面の「リスポーン」と同じ。画面はリスポーンが届くと閉じる
                    log("RESPAWN");
                } else {
                    log("RESPAWN 生きているので何もしない");
                }
            }
            case "status" -> status(mc);
            // チャンクの読み込み解除の中身(2026-10-18、ワールドを閉じると固まる件)。chunkwatch は leave の前に流す
            case "chunkdump" -> log(ChunkDump.dump(mc, dir, arg.isEmpty() ? "chunkdump" : arg));
            case "chunkwatch" -> log(ChunkDump.watch(mc, dir));
            case "echo" -> log("ECHO " + arg);
            // 画面の色の調子の試作(2026-10-21、client/light/MoodState)。mood [all|lightmap|lut|vignette on|off] / mood stage <-1|0..3> / mood time on|off / mood stat
            case "mood" -> log(com.yayuy.overgrownvegetation.client.light.MoodState.devCommand(arg));
            // 霧と空の色の詰めの試作(2026-10-23、client/FogMood)。fogmood [all|curve|color|sun|wet on|off]
            case "fogmood" -> log(com.yayuy.overgrownvegetation.client.FogMood.devCommand(arg));
            // 重さを測る(2026-10-18、FxProfiler)。bench は終わるまで次の命令へ進まない。
            case "fxprof" -> log(FxProfiler.command(arg));
            // 漂う胞子の束の入り切り(2026-10-23、GpuSpores)。on / off / 空 = 状態だけ。
            case "gpuspores" -> {
                if (arg.equals("on") || arg.equals("off")) {
                    com.yayuy.overgrownvegetation.client.GpuSpores.enabled = arg.equals("on");
                }
                log(com.yayuy.overgrownvegetation.client.GpuSpores.describe());
            }
            case "winsize" -> log(FxProfiler.winsize(mc, arg));
            // 描画解像度(2026-10-23、client.RenderScale)。rscale 0.5 / 2.0(4K の塗りつぶしを 1080p の窓で)/ off = 設定の値へ戻す
            case "rscale" -> {
                if (!arg.isBlank()) {
                    com.yayuy.overgrownvegetation.client.RenderScale.devScale = arg.trim().equalsIgnoreCase("off") ? 0.0F : Float.parseFloat(arg.trim());
                }
                log(com.yayuy.overgrownvegetation.client.RenderScale.status(mc));
            }
            // グラフィックの設定(2026-10-23、描画解像度の縮小をファビュラスで確かめる)。graphics fast|fancy|fabulous
            case "graphics" -> {
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.valueOf(arg.trim().toUpperCase(java.util.Locale.ROOT)));
                mc.levelRenderer.allChanged();
                log("GRAPHICS " + mc.options.graphicsMode().get());
            }
            // 植物の cutout_mipped への寄せ(2026-10-23、client.PlantLayers)。plantmip on|off(設定を書き換えて資源を読み直す。終わると PLANTMIP 終わり)
            case "plantmip" -> {
                com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.plantMipmaps.set(!arg.trim().equalsIgnoreCase("off"));
                log("PLANTMIP " + com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.plantMipmaps.get());
                mc.reloadResourcePacks().thenRun(() -> mc.execute(() -> log("PLANTMIP 終わり")));
            }
            // 夜だけ灯る面の発光の入り切り(2026-10-24、client.NightGlow。off = 前と同じに一日中光る)
            case "nightglow" -> {
                com.yayuy.overgrownvegetation.client.NightGlow.devEnabled = !arg.trim().equalsIgnoreCase("off");
                log("NIGHTGLOW " + com.yayuy.overgrownvegetation.client.NightGlow.devEnabled);
            }
            // 下草を描く距離(2026-10-23、client.PlantLayers。0 = 縮めない)
            case "undergrowth" -> {
                com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.undergrowthDistance.set(Integer.parseInt(arg.trim()));
                log("UNDERGROWTH " + com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.undergrowthDistance.get());
            }
            // 朝霧の板の「多い」の枚数を差し替える(2026-10-24、前の 5+3 と撮り比べる)。mistsheets <朝霧> <雲海> / off
            case "mistsheets" -> {
                String[] ms = arg.trim().split("\\s+");
                if (ms[0].equals("off")) {
                    com.yayuy.overgrownvegetation.client.MorningMistView.devSetSheets(0, 0);
                } else {
                    com.yayuy.overgrownvegetation.client.MorningMistView.devSetSheets(Integer.parseInt(ms[0]), Integer.parseInt(ms[1]));
                }
                log("MISTSHEETS " + arg.trim());
            }
            // 胞子の帯(2026-10-24、SporePlumes#devCommand): old / new = 前の描き方と直した描き方 / density <k> / green <0〜1>
            case "plume" -> log(com.yayuy.overgrownvegetation.client.SporePlumes.devCommand(arg));
            // 遠くの雲の描き方(2026-10-24): old = 空と一緒に地形より先 / new = 森の霧の中は地形の後に空の画素だけ
            case "farclouds" -> {
                com.yayuy.overgrownvegetation.client.VoxelClouds.devOldFarPass(arg.trim().equals("old"));
                log("FARCLOUDS " + arg.trim());
            }
            // 地形のシェーダーの演出を uniform の強さ 0 で消す(2026-10-23、TerrainFxMask。511 = 全部 / off)
            case "fxmask" -> log(TerrainFxMask.command(arg));
            // 資源パックの入り切り(2026-10-23、バニラの地形のシェーダーと比べる)。rpack <フォルダ名> on|off。読み直しが終わると RPACK 終わり
            case "rpack" -> {
                String[] rp = arg.trim().split("\\s+");
                var repo = mc.getResourcePackRepository();
                repo.reload();
                java.util.List<String> sel = new java.util.ArrayList<>(repo.getSelectedIds());
                String id = "file/" + rp[0];
                sel.remove(id);
                if (rp.length < 2 || !rp[1].equalsIgnoreCase("off")) {
                    sel.add(id);
                }
                repo.setSelected(sel);
                log("RPACK 選んだパック " + repo.getSelectedIds());
                mc.reloadResourcePacks().thenRun(() -> mc.execute(() -> log("RPACK 終わり")));
            }
            case "rd" -> {
                // 描画距離を変える(区画の数で重さが変わるかの撮り比べ用、2026-10-19)。options.txt には書かない
                mc.options.renderDistance().set(Integer.parseInt(arg.trim()));
                log("RD " + mc.options.renderDistance().get());
            }
            case "gldebug" -> {
                // GL のデバッグ出力を入り切りする(バニラは glDebugVerbosity 1 で入れている。ドライバの確かめが重いかの撮り比べ用、2026-10-19)
                if (arg.equalsIgnoreCase("off")) {
                    org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
                } else {
                    org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
                }
                log("GLDEBUG " + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT));
            }
            case "fastcull" -> {
                // 見える区画の選び直しの近道(client.SectionFrustumCache)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.SectionFrustumCache.enabled = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.SectionFrustumCache.enabled = true;
                }
                log("FASTCULL " + com.yayuy.overgrownvegetation.client.SectionFrustumCache.enabled + "・グラフの区画 "
                        + com.yayuy.overgrownvegetation.client.SectionFrustumCache.cachedSize() + "・作り直し "
                        + com.yayuy.overgrownvegetation.client.SectionFrustumCache.rebuilds + " 回・足し "
                        + com.yayuy.overgrownvegetation.client.SectionFrustumCache.appends + " 回・見える区画 "
                        + ((com.yayuy.overgrownvegetation.mixin.client.LevelRendererVisibleAccessor) mc.levelRenderer).overgrown_vegetation$visibleSections().size());
            }
            case "shortlists" -> {
                // 見える区画の短い一覧(mixin.client.LevelRendererSectionListsMixin)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.SectionFrustumCache.shortLists = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.SectionFrustumCache.shortLists = true;
                }
                log("SHORTLISTS " + com.yayuy.overgrownvegetation.client.SectionFrustumCache.shortLists);
            }
            case "sunmapcheck" -> log("SUNMAPCHECK " + com.yayuy.overgrownvegetation.client.CanopySunMap.debugCheckSync());
            // 葉の層の重ね塗りを数える(2026-10-24、LeafLayerProbe)。leafprobe <フレーム数> [equal|always|plain]。leafcull 0|1|2 = 候補 F5 の試し
            case "leafprobe" -> {
                return LeafLayerProbe.command(arg, DevBridge::log);
            }
            case "leafcull" -> log(LeafLayerProbe.cull(arg));
            case "depthat" -> {
                return FxProfiler.depthAt(arg, DevBridge::log);
            }
            case "uploadbudget" -> {
                // 区画の送り込みを 1 フレーム 2 ms までに(client.SectionUploadBudget)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.SectionUploadBudget.enabled = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.SectionUploadBudget.enabled = true;
                }
                log("UPLOADBUDGET " + com.yayuy.overgrownvegetation.client.SectionUploadBudget.enabled + "・次のフレームへ回した回数 "
                        + com.yayuy.overgrownvegetation.client.SectionUploadBudget.deferred);
            }
            case "fogbacklog" -> {
                // 霧で描かずにいる区画を裏で組み立てておく(client.FogCullBacklog)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.FogCullBacklog.enabled = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.FogCullBacklog.enabled = true;
                }
                log("FOGBACKLOG " + com.yayuy.overgrownvegetation.client.FogCullBacklog.enabled + "・霧で外した区画 "
                        + com.yayuy.overgrownvegetation.client.FogCullBacklog.size() + "・組み立てへ回した数 "
                        + com.yayuy.overgrownvegetation.client.FogCullBacklog.scheduled);
            }
            case "lazycopy" -> {
                // 区画を組み立てる前の写しを上下 1 区画だけに(client.LazySectionCopy)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.LazySectionCopy.enabled = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.LazySectionCopy.enabled = true;
                }
                log("LAZYCOPY " + com.yayuy.overgrownvegetation.client.LazySectionCopy.enabled);
            }
            case "fastdraw" -> {
                // 地形の区画を決まった器で描く(client.FastChunkDraw)を入り切りする。撮り比べ用(2026-10-20)
                if (arg.equalsIgnoreCase("off")) {
                    com.yayuy.overgrownvegetation.client.FastChunkDraw.enabled = false;
                } else if (arg.equalsIgnoreCase("on")) {
                    com.yayuy.overgrownvegetation.client.FastChunkDraw.enabled = true;
                }
                log("FASTDRAW " + com.yayuy.overgrownvegetation.client.FastChunkDraw.enabled + "・差し替え "
                        + com.yayuy.overgrownvegetation.client.FastChunkDraw.fastBinds + " 回・バニラ "
                        + com.yayuy.overgrownvegetation.client.FastChunkDraw.vanillaBinds + " 回");
            }
            case "bench" -> {
                return FxProfiler.bench(mc, arg, DevBridge::log, dir);
            }
            case "close" -> {
                if (mc.screen != null) mc.screen.onClose();
                log("CLOSE");
            }
            case "inventory" -> {
                // サバイバルの持ち物の画面を開く(追加防具の枠を撮るため。2026-10-15)
                if (mc.player == null) return "ワールドの中";
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                log("INVENTORY");
            }
            case "pause" -> {
                // ポーズ画面を開く(葉っぱのボタン = Overgrown Vegetation の設定 などを押すため。2026-10-09)
                if (mc.level == null) return "ワールドの中";
                mc.pauseGame(false);
                log("PAUSE");
            }
            case "mouse" -> {
                // カーソルを動かす(GUI の拡大後の座標)。撮るときにツールチップ・枠の強調を出したく / 出したくないとき(2026-10-16)
                String[] p = arg.trim().split("\\s+");
                double s = mc.getWindow().getGuiScale();
                double mx = Double.parseDouble(p[0]) * s, my = Double.parseDouble(p[1]) * s;
                long window = mc.getWindow().getWindow();
                org.lwjgl.glfw.GLFW.glfwSetCursorPos(window, mx, my);
                try {
                    // glfwSetCursorPos は動いた知らせを出さないことがあるので、ゲームのマウスにも直に伝える。
                    java.lang.reflect.Method onMove = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    onMove.setAccessible(true);
                    onMove.invoke(mc.mouseHandler, window, mx, my);
                } catch (ReflectiveOperationException e) {
                    log("ERR mouse: " + e);
                }
                log("MOUSE " + p[0] + " " + p[1] + " → " + mc.mouseHandler.xpos() + "," + mc.mouseHandler.ypos());
            }
            case "guiscale" -> {
                // GUI の拡大を変える(0 = 自動)。字の大きさで割り付けの変わる画面を、起動し直さずに見比べる(2026-10-16)
                mc.options.guiScale().set(Integer.parseInt(arg.trim()));
                mc.resizeDisplay();
                log("GUISCALE " + mc.getWindow().getGuiScale() + " " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
            }
            case "widgets" -> {
                // 開いている画面のボタン・入力欄の位置(GUI の拡大後)と文字。click の座標を決めるのに(2026-10-09)
                if (mc.screen == null) {
                    log("ERR widgets: 画面が開いていない");
                    return null;
                }
                for (var child : mc.screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                        log(String.format(Locale.ROOT, "WIDGET %d %d %dx%d %s %s", w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                                w.getClass().getSimpleName(), w.getMessage().getString()));
                    }
                }
            }
            case "tooltip" -> {
                // 手に持った物のツールチップ(F3+H なしのふつうの表示)を 1 行ずつ。ホバーは撮れないので文字で確かめる(2026-10-09)
                if (mc.player == null || mc.level == null) return "ワールドの中";
                net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
                for (Component tip : held.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player,
                        net.minecraft.world.item.TooltipFlag.Default.NORMAL)) {
                    log("TIP " + tip.getString());
                }
            }
            case "skyboss" -> {
                // 空の巨大生物の当たり判定の確かめ用(2026-10-18 夜): skyboss <体の骨> <横> <上>
                // = その骨の真ん中の輪切りの中心から、体の横の向きへ <横>・世界の上へ <上> ブロックの所へ移って、輪切りの中心を向く。返事は SKYBOSS …(体力も)
                // 10-18 夜: skyboss rod|budNN_l <横> <上>(避雷針・蕾の所から)、skyboss break rod|<蕾の名前か番号>|buds <数>(弱点を今すぐ壊す。ボスの体力は減らさない)
                String[] a0 = arg.split("\\s+");
                // 10-19: skyboss w <骨> <横> <上> = <横> を体の横(転がると傾く)でなく、体の軸に直角な世界の水平の向きに取る(転がる途中を真横から撮る)
                boolean worldSide = a0.length >= 4 && a0[0].equals("w");
                String[] a = worldSide ? new String[]{a0[1], a0[2], a0[3]} : a0;
                // 10-19: skyboss hang(背の垂れ草の板が世界の真下から何度か。描いた最後のフレームで)・skyboss lines on|off(確かめ用の線)
                if (a[0].equals("hang")) {
                    log("SKYBOSS HANG " + com.yayuy.overgrownvegetation.client.skyboss.SkyBossModel.hangReport());
                    return null;
                }
                if (a[0].equals("arcs")) {
                    log("SKYBOSS ARCS " + com.yayuy.overgrownvegetation.client.skyboss.SkyBossModel.arcReport());
                    return null;
                }
                if (a[0].equals("lines")) {
                    boolean on = !(a.length >= 2 && a[1].equals("off"));
                    com.yayuy.overgrownvegetation.client.skyboss.SkyBossModel.debugLines(on);
                    log("SKYBOSS LINES " + (on ? "on" : "off"));
                    return null;
                }
                if (a[0].equals("eyes")) {
                    // 10-19 夕方: skyboss eyes <x> <y> | off(瞳を決めた向きへ。目の局所の x = 後ろ・y。符号の確かめ用)
                    com.yayuy.overgrownvegetation.client.skyboss.SkyBossModel.eyeForce = a.length >= 3
                            ? new float[]{Float.parseFloat(a[1]), Float.parseFloat(a[2])} : null;
                    log("SKYBOSS EYES " + (a.length >= 3 ? a[1] + " " + a[2] : "off"));
                    return null;
                }
                boolean brk = a.length >= 2 && a[0].equals("break");
                // 10-19: skyboss status(雷雲に寄る・留まるの状態)・skyboss seek [範囲](いちばん近い巨大な積乱雲へ今すぐ寄らせる)
                // 10-19: skyboss roll <度>(体の軸のまわりの向きへ今すぐ転がる。180 = 腹が上・0 = 背が上。背の垂れ草を見る)
                // 10-19: skyboss charge <0〜1>(溜め)・skyboss discharge(今すぐ放電を始める)。status に放電の状態も
                // 10-19 の技: skyboss attack volley|sap|downdraft|spit|lash|grab|seed|beam(次の tick にその技を出す。戦いの中にする)・skyboss light <0〜1>(日の光の溜まり)
                boolean info = a[0].equals("status") || a[0].equals("seek") || a[0].equals("roll") || a[0].equals("charge") || a[0].equals("discharge")
                        || a[0].equals("attack") || a[0].equals("light") || a[0].equals("tentgrab") || a[0].equals("shiver") || a[0].equals("sea");
                if (a.length < 3 && !brk && !info) return "skyboss <骨|rod|budNN_l> <横> <上> / skyboss break rod|<蕾>|buds <数>";
                net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                if (server == null || mc.player == null) return "ワールドの中";
                java.util.UUID me = mc.player.getUUID();
                double side = brk || info ? 0.0 : Double.parseDouble(a[1]);
                double up = brk || info ? 0.0 : Double.parseDouble(a[2]);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    com.yayuy.overgrownvegetation.skyboss.SkyBossData data = com.yayuy.overgrownvegetation.skyboss.SkyBossData.get(level);
                    com.yayuy.overgrownvegetation.skyboss.SkyBossShapes s = com.yayuy.overgrownvegetation.skyboss.SkyBossShapes.get();
                    net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(me);
                    if (brk) {
                        skybossBreak(level, data, s, a);
                        return;
                    }
                    if (info) {
                        if (!data.isActive()) {
                            log("SKYBOSS 居ない");
                            return;
                        }
                        if (a[0].equals("seek")) {
                            double range = a.length >= 2 ? Double.parseDouble(a[1]) : 20000.0;
                            log("SKYBOSS SEEK " + (data.debugSeek(level, range) ? "寄り始めた" : "範囲の中に巨大な積乱雲が無い"));
                        }
                        if (a[0].equals("roll") && a.length >= 2) {
                            data.debugRoll(Float.parseFloat(a[1]));
                            log("SKYBOSS ROLL " + a[1] + " へ転がり始めた");
                        }
                        if (a[0].equals("charge") && a.length >= 2) {
                            data.debugCharge(level, Float.parseFloat(a[1]));
                            log("SKYBOSS CHARGE " + a[1]);
                        }
                        if (a[0].equals("attack") && a.length >= 2) {
                            long gtA = level.getGameTime();
                            data.markCombat(gtA);
                            log("SKYBOSS ATTACK " + a[1] + " " + (data.attacks().forceNext(a[1], gtA) ? "次の tick に出す" : "知らない技"));
                        }
                        if (a[0].equals("tentgrab") && p != null) {
                            // skyboss tentgrab [触手 0〜7]: 次の tick に絡め取るを始め、捕まえる間(56 tick 後)に触手の先の方が来る所へ移って待つ
                            int tn = a.length >= 2 ? Integer.parseInt(a[1]) : 0;
                            double[] at = data.debugGrabSpot(level, tn, 56);
                            if (at != null) {
                                p.teleportTo(at[0], at[1] - p.getBbHeight() * 0.5, at[2]);
                                p.setDeltaMovement(0.0, 0.0, 0.0);
                            }
                            log("SKYBOSS TENTGRAB " + (at == null ? "形が無い" : String.format(Locale.ROOT, "(%.0f, %.0f, %.0f) で待つ", at[0], at[1], at[2])));
                        }
                        if (a[0].equals("sea") && a.length >= 2) {
                            // 10-19 夕方: skyboss sea dive|breach(雲海に潜ろうとする・今すぐ飛び出す)
                            log("SKYBOSS SEA " + a[1] + " " + data.debugSea(level, a[1]));
                        }
                        if (a[0].equals("shiver")) {
                            // skyboss shiver: 次の tick にときどきの身震い(10-19 夕方。戦いの外で渡っているときだけ)
                            data.rhythm().debugShiverNow(level.getGameTime());
                            log("SKYBOSS SHIVER 次の tick に");
                        }
                        if (a[0].equals("light") && a.length >= 2) {
                            data.debugLight(level, Float.parseFloat(a[1]));
                            log("SKYBOSS LIGHT " + a[1]);
                        }
                        if (a[0].equals("discharge")) {
                            log("SKYBOSS DISCHARGE " + (data.debugDischarge(level) ? "始めた" : "始められない(転がっている途中か、もう放電中)"));
                        }
                        long gt = level.getGameTime();
                        log("SKYBOSS DSTATUS " + data.dischargeStatus(gt));
                        log("SKYBOSS STATUS " + data.feedingStatus(gt) + String.format(Locale.ROOT, " 体力 %.0f 頭(世界の座標)(%.0f, %.0f, %.0f)",
                                data.health(), com.yayuy.overgrownvegetation.skyboss.SkyBossWorld.worldX(data.flight().x, gt), data.flight().y,
                                com.yayuy.overgrownvegetation.skyboss.SkyBossWorld.worldZ(data.flight().z, gt)));
                        return;
                    }
                    if (data.isActive() && s != null && p != null && a[0].matches("fan[0-7]")) {
                        // skyboss fanN <横> <下>: 扇 N(0〜3 = 左の腕 1〜4、4〜7 = 右)の付け根から、世界の真下へ <下> ブロックの所へ移って真上を見る(<横> は使わない)
                        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight ff = data.flight();
                        com.yayuy.overgrownvegetation.skyboss.SkyBossPose pf = new com.yayuy.overgrownvegetation.skyboss.SkyBossPose();
                        pf.compute(ff, ff.x, ff.y, ff.z, ff.bank, ff.tick, true);
                        int arm = a[0].charAt(3) - '0';
                        int ci = arm * (s.armPoints - 1) + (s.armPoints - 2);
                        double fx = pf.cB[ci * 3];
                        double fy = pf.cB[ci * 3 + 1];
                        double fz = pf.cB[ci * 3 + 2];
                        p.teleportTo(level, fx, fy - up - p.getEyeHeight(), fz, java.util.Set.of(), p.getYRot(), -90.0F);
                        log(String.format(java.util.Locale.ROOT, "SKYBOSS %s 付け根 (%.1f, %.1f, %.1f) → 真下 %.0f 傾き %.1f 扇 %d", a[0], fx, fy, fz, up, ff.bank, ff.fanMode));
                        return;
                    }
                    int bud = s == null ? -1 : s.budIndex(a[0]);
                    boolean rodT = a[0].equals("rod");
                    if (!data.isActive() || s == null || p == null || (s.bodyIndex(a[0]) < 0 && bud < 0 && !rodT)) {
                        log("SKYBOSS 居ない・形が無い・骨の名前が違う");
                        return;
                    }
                    com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = data.flight();
                    com.yayuy.overgrownvegetation.skyboss.SkyBossPose ps = new com.yayuy.overgrownvegetation.skyboss.SkyBossPose();
                    ps.parts = data.parts();
                    ps.compute(f, f.x, f.y, f.z, f.bank, f.tick, true);
                    // 避雷針・蕾は、持ち主の体の骨の横の向きで、その点から
                    int b = rodT ? s.rodOwner : bud >= 0 ? s.budOwner[bud] : s.bodyIndex(a[0]);
                    int idx = 0;
                    for (int i = 0; i < b; i++) {
                        idx += s.bodySlices[i].length;
                    }
                    idx += s.bodySlices[b].length / 2;
                    int o = idx * 3;
                    double half = ps.sLen[idx] * 0.5;
                    double cx = ps.sA[o] + ps.sEz[o] * half;
                    double cy = ps.sA[o + 1] + ps.sEz[o + 1] * half;
                    double cz = ps.sA[o + 2] + ps.sEz[o + 2] * half;
                    if (rodT) {
                        int ci = ps.rodFirstCapsule() + (s.rodPts.length - 1) / 2;
                        cx = (ps.cA[ci * 3] + ps.cB[ci * 3]) * 0.5;
                        cy = (ps.cA[ci * 3 + 1] + ps.cB[ci * 3 + 1]) * 0.5;
                        cz = (ps.cA[ci * 3 + 2] + ps.cB[ci * 3 + 2]) * 0.5;
                    } else if (bud >= 0) {
                        cx = ps.spC[bud * 3];
                        cy = ps.spC[bud * 3 + 1];
                        cz = ps.spC[bud * 3 + 2];
                    }
                    double ex = ps.sEx[o];
                    double ey = ps.sEx[o + 1];
                    double ez = ps.sEx[o + 2];
                    if (worldSide) {
                        // 体の軸(sEz)に直角な水平の向き
                        double hl = Math.max(1.0E-9, Math.hypot(ps.sEz[o], ps.sEz[o + 2]));
                        ex = -ps.sEz[o + 2] / hl;
                        ey = 0.0;
                        ez = ps.sEz[o] / hl;
                    }
                    double x = cx + ex * side;
                    double y = cy + ey * side + up;
                    double z = cz + ez * side;
                    double dx = cx - x;
                    double dy = cy - y;
                    double dz = cz - z;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    p.teleportTo(level, x, y - p.getEyeHeight(), z, java.util.Set.of(), yaw, pitch);
                    log(String.format(java.util.Locale.ROOT, "SKYBOSS %s 中心 (%.1f, %.1f, %.1f) 横の半径 %.1f 縦 %.1f → 目 (%.1f, %.1f, %.1f) 体力 %.0f 傾き %.1f 扇 %d",
                            a[0], cx, cy, cz, ps.sRx[idx], ps.sRy[idx], x, y, z, data.health(), f.bank, f.fanMode));
                });
            }
            case "reloadres" -> {
                // 資源(モデル・テクスチャ)を読み直す = F3+T。build/resources/main の写しを直してから流すと、起動し直さずに見た目を詰められる(2026-10-09)
                mc.reloadResourcePacks().thenRun(() -> log("RELOADRES 終わり"));
            }
            case "leave" -> {
                if (mc.level == null) return "ワールドの中";
                log("LEAVE");
                boolean local = mc.isLocalServer();
                mc.level.disconnect();
                if (local) {
                    mc.disconnect(new GenericMessageScreen(Component.literal("Saving world")));
                } else {
                    mc.disconnect();
                }
                mc.setScreen(new TitleScreen());
                await("title");
            }
            case "quit" -> {
                log("QUIT");
                mc.stop();
            }
            default -> log("ERR 知らない命令: " + line);
        }
        return null;
    }

    /** 目(視点)から箱までの距離が 6 以内で一番近い、その種類のエンティティ。大きい体でも箱の端で測る。 */
    private static Entity nearest(Minecraft mc, LocalPlayer player, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        Optional<EntityType<?>> type = key == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(key);
        if (type.isEmpty()) return null;
        Vec3 eye = player.getEyePosition();
        Entity best = null;
        double bestSqr = 6.0 * 6.0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == player || entity.getType() != type.get()) continue;
            double sqr = boxDistanceSqr(entity.getBoundingBox(), eye);
            if (sqr <= bestSqr) {
                best = entity;
                bestSqr = sqr;
            }
        }
        return best;
    }

    private static double boxDistanceSqr(AABB box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0.0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0.0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0.0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void await(String state) {
        awaiting = state;
        awaitingSince = tick;
    }

    private static void newWorld(Minecraft mc, String arg) throws IOException {
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        String name = a.length > 0 ? a[0] : "claude_test";
        boolean flat = a.length > 1 && a[1].equalsIgnoreCase("flat");
        long seed = 0L;
        if (a.length > 2) {
            try {
                seed = Long.parseLong(a[2]);
            } catch (NumberFormatException e) {
                seed = a[2].hashCode(); // バニラと同じく文字のシードは hashCode
            }
        }
        // 同じ名前のフォルダがあれば作成画面と同じく別の名前にする(上書きしない)
        String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), name, "");
        LevelSettings settings = new LevelSettings(folder, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        Function<RegistryAccess, WorldDimensions> dimensions = flat
                ? registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                : WorldPresets::createNormalWorldDimensions;
        log("NEWWORLD " + folder + (flat ? " flat" : " normal") + " seed=" + seed);
        mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(seed, true, false), dimensions, new TitleScreen());
    }

    /** 統合サーバーのスレッドで、プレイヤーとして実行する(~ や @s がプレイヤーを指す)。 */
    private static void command(IntegratedServer server, UUID playerId, String raw) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        log("CMD> " + command);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            CommandSourceStack source = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
                    .withSource(OUTPUT)
                    .withPermission(4);
            server.getCommands().performPrefixedCommand(source, command);
        });
    }

    private static void shot(Minecraft mc, String arg) {
        String name = arg.isEmpty() ? "shot_" + tick : arg.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (!name.endsWith(".png")) name += ".png";
        String file = name;
        Path path = mc.gameDirectory.toPath().resolve("screenshots").resolve(file).toAbsolutePath();
        // 書き込みは別スレッド。書き終えたら(または失敗したら)返事が来る
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(),
                message -> log("SHOT " + path + " : " + message.getString()));
    }

    private static void status(Minecraft mc) {
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        String world = mc.level == null ? "none" : mc.level.dimension().location().toString();
        String pos = mc.player == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f yaw=%.0f pitch=%.0f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot());
        int entities = mc.level == null ? 0 : mc.level.getEntityCount();
        log("STATUS screen=" + screen + " world=" + world + " pos=" + pos + " fps=" + mc.getFps() + " entities=" + entities
                + " queue=" + next + "/" + lines.size());
    }

    /** out.log に 1 行。描画のスレッドとサーバーのスレッドの両方から呼ばれる。 */
    private static synchronized void log(String message) {
        if (dir == null) return;
        String line = "[" + LocalTime.now().format(TIME) + " t=" + tick + "] " + message + System.lineSeparator();
        try {
            Files.writeString(dir.resolve("out.log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("devbridge: out.log に書けない: {}", message, e);
        }
    }
}
