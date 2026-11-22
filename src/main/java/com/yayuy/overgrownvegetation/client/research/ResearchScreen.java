package com.yayuy.overgrownvegetation.client.research;

import com.yayuy.overgrownvegetation.network.ResearchActionPayload;
import com.yayuy.overgrownvegetation.network.ResearchStatePayload;
import com.yayuy.overgrownvegetation.research.Research;
import com.yayuy.overgrownvegetation.research.ResearchTree;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>研究ツリーの画面</b>(2026-11-17 決定 22。試作 https://claude.ai/artifact/3ZCqhyvhekyi4uP6ypXu6K でユーザーが選んだ形)。
 *
 * <ul>
 *     <li>ツリーの形 = 段で横に並ぶ(左から段 1・2・3。縦の行は 防具・素材・近接・遠距離)。前提の線は直角に折れる。</li>
 *     <li>節 = 星座の点(地脈の光る丸 + 中にアイテムの絵 + 下に小さく名前)。開いた = 白く強く光る / 開ける = 青緑に明滅 / まだ = 暗い。</li>
 *     <li>まだ開けない節は 1 つ先だけ「?」(前提がどれも「開いた」か「開ける」の節)。その先は点も線も描かない。</li>
 *     <li>払う物は下の詳細の欄(標本の数と持っている数・地脈の力・時間・器具・前提・開く作り方)。</li>
 *     <li>入れ方 = 選んで「研究する」を押すだけ(持ち物から標本を取る)。</li>
 * </ul>
 * 絵(点・線・帯)は仮で、のちにユーザーが描き直す。ツリーはドラッグとホイールで動かす。
 */
public class ResearchScreen extends Screen {

    private static final int COL_W = 46;
    private static final int ROW_H = 24;
    private static final int TIER_GAP = 34;
    private static final int DOT_R = 9;
    private static final int TOP = 18;

    private final BlockPos bench;
    private ResearchTree tree;
    private Set<String> done = new HashSet<>();
    private String active = "";
    private int progress;
    private boolean activeHere;
    private int energy;
    private int buffer;
    private int instruments;

    private String selected;
    private double panX;
    private double panY;
    private boolean dragging;
    private Button startButton;
    private Button cancelButton;

    private final Map<String, int[]> pos = new HashMap<>();
    private final Map<Integer, int[]> tierSpan = new HashMap<>();

    public ResearchScreen(ResearchStatePayload p) {
        super(Component.translatable("research.overgrown_vegetation.title"));
        this.bench = p.bench();
        this.tree = ResearchTree.parse(p.treeJson());
        update(p);
        layoutTree();
        this.selected = firstAvailable();
    }

    /** 通信を受け取ったとき(ResearchNetwork から)。 */
    public static void accept(ResearchStatePayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.open()) {
            mc.setScreen(new ResearchScreen(p));
        } else if (mc.screen instanceof ResearchScreen s && s.bench.equals(p.bench())) {
            s.update(p);
        }
    }

    private void update(ResearchStatePayload p) {
        done = new HashSet<>(p.done());
        active = p.active();
        progress = p.progress();
        activeHere = p.activeHere();
        energy = p.energy();
        buffer = p.buffer();
        instruments = p.instruments();
    }

    private boolean isDone(String id) {
        ResearchTree.Node n = tree.node(id);
        return (n != null && n.isRoot()) || done.contains(id);
    }

    private Research.State state(String id) {
        return Research.state(tree, this::isDone, id);
    }

    /** 見せ方: 0 = 隠す、1 = 「?」、2 = 見せる(決定 22: 1 つ先だけ「?」)。 */
    private int visibility(String id) {
        Research.State s = state(id);
        if (s != Research.State.LOCKED) return 2;
        ResearchTree.Node n = tree.node(id);
        return n.requires().stream().allMatch(r -> tree.node(r) != null && state(r) != Research.State.LOCKED) ? 1 : 0;
    }

    private String firstAvailable() {
        for (ResearchTree.Node n : tree.nodes().values()) {
            if (state(n.id()) == Research.State.AVAILABLE) return n.id();
        }
        return tree.nodes().isEmpty() ? null : tree.nodes().keySet().iterator().next();
    }

    // ---- 並べ方 ----

    private void layoutTree() {
        pos.clear();
        tierSpan.clear();
        int maxTier = 0;
        Map<Integer, Double> maxCol = new HashMap<>();
        for (ResearchTree.Node n : tree.nodes().values()) {
            maxTier = Math.max(maxTier, n.tier());
            maxCol.merge(n.tier(), n.col(), Math::max);
        }
        int x = 20;
        for (int t = 0; t <= maxTier; t++) {
            double mc = maxCol.getOrDefault(t, 0.0);
            int w = (int) Math.round(mc * COL_W);
            tierSpan.put(t, new int[]{x - 18, x + w + 18});
            for (ResearchTree.Node n : tree.nodes().values()) {
                if (n.tier() != t) continue;
                int lane = n.isRoot() ? 1 : Math.max(0, ResearchTree.LANES.indexOf(n.lane()));
                double laneY = lane;
                // 同じ段・同じ行・同じ列に 2 つあれば少しずらす
                pos.put(n.id(), new int[]{x + (int) Math.round(n.col() * COL_W), TOP + (int) Math.round(laneY * ROW_H)});
            }
            x += w + TIER_GAP + 36;
        }
    }

    private int treeLeft() {
        return 8;
    }

    private int treeTop() {
        return 20;
    }

    private int treeRight() {
        return width - 8;
    }

    private int treeBottom() {
        return Math.max(treeTop() + 80, (int) (height * 0.53));
    }

    @Override
    protected void init() {
        int by = height - 26;
        startButton = addRenderableWidget(Button.builder(Component.translatable("research.overgrown_vegetation.start"), b -> {
            if (selected != null) PacketDistributor.sendToServer(new ResearchActionPayload(ResearchActionPayload.START, bench, selected));
        }).bounds(width - 100, by, 92, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("research.overgrown_vegetation.cancel"), b ->
                PacketDistributor.sendToServer(new ResearchActionPayload(ResearchActionPayload.CANCEL, bench, ""))
        ).bounds(width - 100, by, 92, 20).build());
        // はじめは選んだ節が真ん中寄りに見えるように
        int[] p = selected != null ? pos.get(selected) : null;
        if (p != null && panX == 0) {
            panX = Mth.clamp((treeRight() - treeLeft()) / 3.0 - p[0], -2000, 0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new ResearchActionPayload(ResearchActionPayload.CLOSE, bench, ""));
        super.removed();
    }

    @Override
    public void tick() {
        ResearchTree.Node n = selected == null ? null : tree.node(selected);
        startButton.visible = active.isEmpty();
        startButton.active = n != null && whyNot(n) == null;
        cancelButton.visible = !active.isEmpty();
        startButton.setMessage(Component.translatable("research.overgrown_vegetation.start"));
    }

    /** 始められない理由(画面の上での見立て。最後はサーバーが決める)。 */
    private Component whyNot(ResearchTree.Node n) {
        if (n.isRoot()) return Component.translatable("research.overgrown_vegetation.why.unknown");
        if (!active.isEmpty()) return Component.translatable("research.overgrown_vegetation.why.busy");
        Research.State s = state(n.id());
        if (s == Research.State.DONE) return Component.translatable("research.overgrown_vegetation.why.done");
        if (s == Research.State.LOCKED) return Component.translatable("research.overgrown_vegetation.why.requires");
        if (!n.specimensExist()) return Component.translatable("research.overgrown_vegetation.why.no_specimen");
        if (!instrumentsOk(n.instrumentTier())) return Component.translatable("research.overgrown_vegetation.why.instruments");
        for (ResearchTree.Specimen sp : n.specimens()) {
            int have = have(sp.asItem());
            if (have < sp.count()) {
                return Component.translatable("research.overgrown_vegetation.why.short", sp.asItem().getDescription(), sp.count() - have);
            }
        }
        return null;
    }

    private boolean instrumentsOk(int tier) {
        if (tree.tier(tier).instruments().isEmpty()) return true;
        return tier == 2 ? (instruments & 1) != 0 : tier >= 3 ? (instruments & 2) != 0 : true;
    }

    private int have(Item item) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        int c = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) c += s.getCount();
        }
        return c;
    }

    // ---- 操作 ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (inTree(mx, my)) {
            String hit = nodeAt(mx, my);
            if (hit != null) {
                selected = hit;
                return true;
            }
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            panX += dx;
            panY += dy;
            clampPan();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (inTree(mx, my)) {
            panX += (sy + sx) * 24;
            clampPan();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private void clampPan() {
        int maxX = 0;
        int maxY = 0;
        for (int[] p : pos.values()) {
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int viewW = treeRight() - treeLeft();
        int viewH = treeBottom() - treeTop();
        panX = Mth.clamp(panX, Math.min(0, viewW - maxX - 60), 40);
        panY = Mth.clamp(panY, Math.min(0, viewH - maxY - 40), 20);
    }

    private boolean inTree(double mx, double my) {
        return mx >= treeLeft() && mx < treeRight() && my >= treeTop() && my < treeBottom();
    }

    private int sx(int x) {
        return treeLeft() + (int) panX + x;
    }

    private int sy(int y) {
        return treeTop() + (int) panY + y;
    }

    private String nodeAt(double mx, double my) {
        for (Map.Entry<String, int[]> e : pos.entrySet()) {
            if (visibility(e.getKey()) == 0) continue;
            double dx = mx - sx(e.getValue()[0]);
            double dy = my - sy(e.getValue()[1]);
            if (dx * dx + dy * dy <= (DOT_R + 2) * (DOT_R + 2)) return e.getKey();
        }
        return null;
    }

    // ---- 描く ----

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g, mx, my, partial);
        g.drawString(font, title, 10, 6, 0xFFFFFF, true);
        Component power = Component.translatable("research.overgrown_vegetation.power", fmt(energy), fmt(buffer));
        g.drawString(font, power, width - 10 - font.width(power), 6, 0x7FE3E8, true);

        // ツリー
        g.fill(treeLeft() - 1, treeTop() - 1, treeRight() + 1, treeBottom() + 1, 0xFF000000);
        g.fill(treeLeft(), treeTop(), treeRight(), treeBottom(), 0xFF16211A);
        g.enableScissor(treeLeft(), treeTop(), treeRight(), treeBottom());
        drawBands(g);
        drawLines(g);
        String hover = inTree(mx, my) ? nodeAt(mx, my) : null;
        for (ResearchTree.Node n : tree.nodes().values()) drawNode(g, n, hover);
        g.disableScissor();

        drawDetail(g);
        drawRunning(g);
        for (var r : renderables) r.render(g, mx, my, partial);
        if (hover != null) {
            List<Component> tip = new ArrayList<>();
            if (visibility(hover) == 1) {
                tip.add(Component.literal("???"));
            } else {
                tip.add(Research.nodeName(hover));
                tip.add(stateText(hover).copy().withStyle(ChatFormatting.GRAY));
            }
            g.renderComponentTooltip(font, tip, mx, my);
        }
    }

    private void drawBands(GuiGraphics g) {
        for (Map.Entry<Integer, int[]> e : tierSpan.entrySet()) {
            int t = e.getKey();
            if (t == 0) continue;
            int x0 = sx(e.getValue()[0]);
            int x1 = sx(e.getValue()[1]);
            g.fill(x0, treeTop(), x1, treeBottom(), (t % 2 == 1) ? 0x10FFFFFF : 0x18000000);
            g.drawString(font, Component.translatable("research.overgrown_vegetation.tier." + t), x0 + 4, sy(4), 0xCFE3C8, false);
        }
    }

    private void drawLines(GuiGraphics g) {
        for (ResearchTree.Node n : tree.nodes().values()) {
            if (visibility(n.id()) == 0) continue;
            int[] c = pos.get(n.id());
            Research.State s = state(n.id());
            int col = s == Research.State.DONE ? 0xFFC8F6F8 : s == Research.State.AVAILABLE ? 0xFF5CC0C6 : 0xFF33494A;
            for (String r : n.requires()) {
                int[] p = pos.get(r);
                if (p == null || visibility(r) == 0) continue;
                int ax = sx(p[0]);
                int ay = sy(p[1]);
                int bx = sx(c[0]);
                int by = sy(c[1]);
                int midX = (ax + bx) / 2;
                hLine(g, ax, midX, ay, col);
                vLine(g, midX, ay, by, col);
                hLine(g, midX, bx, by, col);
            }
        }
    }

    private static void hLine(GuiGraphics g, int x0, int x1, int y, int col) {
        int a = Math.min(x0, x1);
        int b = Math.max(x0, x1);
        g.fill(a - 1, y - 2, b + 2, y + 2, 0xFF000000);
        g.fill(a, y - 1, b + 1, y + 1, col);
    }

    private static void vLine(GuiGraphics g, int x, int y0, int y1, int col) {
        int a = Math.min(y0, y1);
        int b = Math.max(y0, y1);
        g.fill(x - 2, a - 1, x + 2, b + 2, 0xFF000000);
        g.fill(x - 1, a, x + 1, b + 1, col);
    }

    private static void disc(GuiGraphics g, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.floor(Math.sqrt(r * r - dy * dy + 0.5));
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, col);
        }
    }

    private void drawNode(GuiGraphics g, ResearchTree.Node n, String hover) {
        int vis = visibility(n.id());
        if (vis == 0) return;
        int[] p = pos.get(n.id());
        int x = sx(p[0]);
        int y = sy(p[1]);
        Research.State s = state(n.id());
        long t = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0;
        if (n.id().equals(selected)) {
            disc(g, x, y, DOT_R + 5, 0xFFFFFF55); // 選んだ節: 黄色の輪(上に重ねる点で内側が隠れる)
            disc(g, x, y, DOT_R + 4, 0xFF16211A);
        }
        if (s == Research.State.DONE) {
            disc(g, x, y, DOT_R + 4, 0x30FFFFFF);
            disc(g, x, y, DOT_R + 2, 0xFFFFFFFF);
            disc(g, x, y, DOT_R, 0xFF3C5D5F);
        } else if (s == Research.State.AVAILABLE) {
            int a = 0x30 + (int) (0x30 * (0.5 + 0.5 * Math.sin(t * 0.15)));
            disc(g, x, y, DOT_R + 4, (a << 24) | 0x5CC0C6);
            disc(g, x, y, DOT_R + 2, 0xFF7FD6DB);
            disc(g, x, y, DOT_R, 0xFF2A3B3C);
        } else {
            disc(g, x, y, DOT_R + 2, 0xFF3C4D4E);
            disc(g, x, y, DOT_R, 0xFF1D2627);
        }
        if (vis == 1) {
            g.drawCenteredString(font, "?", x, y - 4, 0xBBBBBB);
            return;
        }
        g.renderItem(icon(n), x - 8, y - 8);
        if (s == Research.State.LOCKED) disc(g, x, y, DOT_R - 1, 0x99000000);
        // 名前(点の下に小さく)
        g.pose().pushPose();
        g.pose().translate(x, y + DOT_R + 4, 200);
        g.pose().scale(0.5F, 0.5F, 1F);
        Component name = Research.nodeName(n.id());
        g.drawString(font, name, -font.width(name) / 2, 0, s == Research.State.LOCKED ? 0x6F8586 : 0xD6F2F3, true);
        g.pose().popPose();
    }

    private ItemStack icon(ResearchTree.Node n) {
        Item item = BuiltInRegistries.ITEM.containsKey(n.icon()) ? BuiltInRegistries.ITEM.get(n.icon()) : Items.BARRIER;
        return new ItemStack(item);
    }

    private Component stateText(String id) {
        return switch (state(id)) {
            case DONE -> Component.translatable("research.overgrown_vegetation.state.done");
            case AVAILABLE -> Component.translatable("research.overgrown_vegetation.state.available");
            case LOCKED -> Component.translatable("research.overgrown_vegetation.state.locked");
        };
    }

    private void drawDetail(GuiGraphics g) {
        int x0 = 8;
        int y0 = treeBottom() + 6;
        int x1 = width - 8;
        int y1 = height - 4;
        g.fill(x0, y0, x1, y1, 0xC0101814);
        if (selected == null || tree.node(selected) == null) return;
        ResearchTree.Node n = tree.node(selected);
        int x = x0 + 6;
        int y = y0 + 5;
        if (visibility(n.id()) == 1) {
            g.drawString(font, "???", x, y, 0xFFFFFF, true);
            g.drawString(font, Component.translatable("research.overgrown_vegetation.unknown_desc"), x, y + 12, 0xAAAAAA, false);
            return;
        }
        g.renderItem(icon(n), x, y);
        g.drawString(font, Research.nodeName(n.id()), x + 20, y, 0xFFFFFF, true);
        Research.State s = state(n.id());
        int stCol = s == Research.State.DONE ? 0xFFD27F : s == Research.State.AVAILABLE ? 0x7FE3A0 : 0xD08080;
        Component st = stateText(n.id());
        if (n.tier() > 0) st = st.copy().append(" ・ ").append(Component.translatable("research.overgrown_vegetation.tier." + n.tier()));
        g.drawString(font, st, x + 20, y + 9, stCol, false);
        y += 22;
        int textW = Math.max(120, (x1 - x0) / 2 - 12);
        // 左: 説明(下の前提の 1 行の分を残して入るだけ)
        int descBottom = y1 - (n.requires().isEmpty() || n.isRoot() ? 4 : 14);
        for (FormattedCharSequence line : font.split(Component.translatable("research.overgrown_vegetation." + n.id() + ".desc"), textW)) {
            if (y + 9 > descBottom) break;
            g.drawString(font, line, x, y, 0xD8D8D8, false);
            y += 10;
        }
        if (!n.requires().isEmpty() && !n.isRoot()) {
            MutableLine req = new MutableLine(Component.translatable("research.overgrown_vegetation.requires"));
            for (String r : n.requires()) req.add(Research.nodeName(r).copy().withStyle(isDone(r) ? ChatFormatting.GREEN : ChatFormatting.RED));
            g.drawString(font, req.get(), x, y1 - 12, 0x9AA59C, false);
        }

        // 右: 払う物(決定 20: 標本・地脈の力・時間)と開く作り方
        if (n.isRoot()) return;
        int rx = (x0 + x1) / 2 + 6;
        int ry = y0 + 5;
        g.drawString(font, Component.translatable("research.overgrown_vegetation.cost"), rx, ry, 0x9AA59C, false);
        ry += 12;
        int ix = rx;
        for (ResearchTree.Specimen sp : n.specimens()) {
            if (sp.exists()) {
                ItemStack st2 = new ItemStack(sp.asItem());
                g.fill(ix - 1, ry - 1, ix + 17, ry + 17, 0xFF373737);
                g.renderItem(st2, ix, ry);
                int hv = have(sp.asItem());
                String c = hv + "/" + sp.count();
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                g.drawString(font, c, ix + 17 - font.width(c), ry + 9, hv >= sp.count() ? 0xFFFFFF : 0xFF5555, true);
                g.pose().popPose();
            } else {
                g.fill(ix - 1, ry - 1, ix + 17, ry + 17, 0xFF373737);
                g.drawCenteredString(font, "?", ix + 8, ry + 4, 0xAAAAAA);
            }
            ix += 26;
        }
        if (!n.specimensExist()) {
            g.drawString(font, Component.translatable("research.overgrown_vegetation.why.no_specimen"), ix + 2, ry + 4, 0xD08080, false);
        }
        ry += 22;
        long fe = n.totalFe();
        g.drawString(font, Component.translatable("research.overgrown_vegetation.fe", fmt(fe), n.fePerTick()), rx, ry, 0x7FE3E8, false);
        ry += 10;
        int gw = Math.min(160, x1 - rx - 10);
        g.fill(rx, ry, rx + gw, ry + 5, 0xFF373737);
        g.fill(rx, ry, rx + (int) (gw * Math.min(1.0, energy / (double) Math.max(1, buffer))), ry + 5, 0xFF2F8F96);
        ry += 9;
        g.drawString(font, Component.translatable("research.overgrown_vegetation.time", fmtHours(n.ticks()), fmtReal(n.ticks())), rx, ry, 0xD8D8D8, false);
        ry += 11;
        List<ResourceLocation> inst = tree.tier(n.instrumentTier()).instruments();
        if (!inst.isEmpty()) {
            boolean ok = instrumentsOk(n.instrumentTier());
            MutableLine l = new MutableLine(Component.translatable("research.overgrown_vegetation.instruments"));
            for (ResourceLocation id : inst) {
                l.add(BuiltInRegistries.BLOCK.get(id).getName().copy().withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            g.drawString(font, l.get(), rx, ry, 0x9AA59C, false);
            ry += 11;
        }
        // 開く作り方(できあがる物の絵)
        if (!n.unlocks().isEmpty() && Minecraft.getInstance().level != null) {
            Component head = Component.translatable("research.overgrown_vegetation.unlocks");
            g.drawString(font, head, rx, ry + 4, 0x9AA59C, false);
            int ux = rx + font.width(head) + 4;
            var level = Minecraft.getInstance().level;
            for (ResourceLocation id : n.unlocks()) {
                ItemStack out = level.getRecipeManager().byKey(id).map(h -> h.value().getResultItem(level.registryAccess())).orElse(ItemStack.EMPTY);
                if (out.isEmpty()) continue;
                g.renderItem(out, ux, ry);
                ux += 17;
            }
        }
        // 始められない理由(ボタンの上)
        if (active.isEmpty() && s != Research.State.DONE) {
            Component why = whyNot(n);
            if (why != null) g.drawString(font, why, x1 - 4 - font.width(why), height - 40, 0xFF8080, false);
        }
    }

    /** 走っている研究の進み(ツリーの右上)。 */
    private void drawRunning(GuiGraphics g) {
        if (active.isEmpty() || tree.node(active) == null) return;
        ResearchTree.Node a = tree.node(active);
        double frac = Math.min(1.0, progress / (double) Math.max(1, a.ticks()));
        Component line = Component.translatable(activeHere ? "research.overgrown_vegetation.running" : "research.overgrown_vegetation.running_elsewhere",
                Research.nodeName(active), (int) Math.floor(frac * 100));
        int w = Math.max(font.width(line), 100);
        int rx = treeRight() - 6;
        int ty = treeTop() + 4;
        g.fill(rx - w - 4, ty - 2, rx + 2, ty + 15, 0xC0000000);
        g.drawString(font, line, rx - font.width(line), ty, 0xFFFFFF, true);
        g.fill(rx - w, ty + 10, rx, ty + 13, 0xFF373737);
        g.fill(rx - w, ty + 10, rx - w + (int) (w * frac), ty + 13, 0xFFFFFFFF);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String fmtHours(int ticks) {
        double h = ticks / 1000.0;
        return h == Math.floor(h) ? String.valueOf((int) h) : String.format("%.1f", h);
    }

    private static String fmtReal(int ticks) {
        int s = ticks / 20;
        int m = s / 60;
        int r = s % 60;
        return m > 0 ? (r > 0 ? m + ":" + String.format("%02d", r) : m + ":00") : "0:" + String.format("%02d", r);
    }

    /** 「前提: A・B」のような 1 行。 */
    private static final class MutableLine {
        private final net.minecraft.network.chat.MutableComponent c;
        private boolean first = true;

        MutableLine(Component head) {
            c = head.copy().append(" ");
        }

        void add(Component part) {
            if (!first) c.append("・");
            c.append(part);
            first = false;
        }

        Component get() {
            return c;
        }
    }
}
