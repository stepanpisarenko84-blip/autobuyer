package com.example.autobuyer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ArmorStandEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Mod("autobuyer")
public class AutoBuyer {

    // ===================== НАСТРОЙКИ =====================

    // --- продажа (/buyer -> вкладка "Ресурсы") ---
    static final String SELL_COMMAND = "/buyer";
    static final String SECTION_NAME = "Ресурсы";
    static final int SECTION_SLOT_FALLBACK = 23;

    // Что сдаём скупщику
    static final Set<Item> SELL_ITEMS = new HashSet<>(Arrays.asList(
            Items.DIAMOND, Items.GOLD_INGOT, Items.IRON_INGOT
    ));
    // true = не продавать зачарованные и переименованные предметы
    static final boolean SELL_SKIP_SPECIAL = true;

    // Способ продажи (Shift + ЛКМ):
    //  true  -> кликаем по иконке ресурса В МЕНЮ скупщика (навестись на ресурс в меню)
    //  false -> кликаем по предмету в ИНВЕНТАРЕ игрока
    // Если выбранный способ не работает, мод сам переключится на второй.
    static final boolean SELL_FROM_MENU_FIRST = true;
    static final int SELL_DELAY_TICKS = 2;       // пауза между кликами при продаже (20 тиков = 1 сек)
    static final int SELL_STUCK_LIMIT = 8;       // сколько кликов без результата до смены способа

    // --- покупка (NPC "Археолог") ---
    static final String NPC_HOLOGRAM_TEXT = "археолог";
    static final double NPC_MAX_DIST = 5.0;
    static final String MAIN_MENU_TITLE = "Главное меню";
    static final String SHOP_ITEM_NAME = "Покупка товаров";
    static final int SHOP_ITEM_FALLBACK_SLOT = 21;
    static final String SHOP_TITLE = "Купить товары";

    // Что покупаем: название в меню, запасной слот, предмет (для подсчёта в инвентаре)
    static final String[] BUY_NAMES = { "Алмаз", "Золотой слиток", "Железный слиток" };
    static final int[] BUY_FALLBACK_SLOTS = { 12, 13, 14 };
    static final Item[] BUY_ITEMS = { Items.DIAMOND, Items.GOLD_INGOT, Items.IRON_INGOT };

    // Сколько стаков (по 64) каждого ресурса набирать за один проход.
    // 12 + 12 + 12 = 36 слотов = весь инвентарь. Покупка идёт, пока не наберётся столько
    // (или пока не кончится место/деньги), так что неважно, сколько штук даёт один клик.
    static final int BUY_STACKS_PER_ITEM = 12;

    // Скорость покупки:
    static final int BUY_DELAY_TICKS = 1;        // пауза между "пачками" кликов (1 = каждый тик)
    static final int BUY_CLICKS_PER_TICK = 1;    // кликов за один раз; если сервер не кикает - можно 2-4
    static final boolean BUY_SHIFT_CLICK = false; // true = Shift+ЛКМ (во многих магазинах покупает сразу стак)
    static final int BUY_STUCK_LIMIT = 20;       // кликов без прироста предмета до перехода к следующему

    // --- общее ---
    static final int OPEN_TIMEOUT_TICKS = 60;
    // Клавиша отладки: при открытом меню выводит в чат список предметов со слотами
    static final int DUMP_KEY = GLFW.GLFW_KEY_K;
    // =====================================================

    enum State {
        OFF,
        SEND_SELL_CMD, WAIT_SECTIONS, WAIT_SELL_MENU, SELLING,
        INTERACT_NPC, WAIT_MAIN_MENU, WAIT_SHOP, BUYING
    }

    private State state = State.OFF;
    private int timer = 0;
    private KeyBinding toggleKey;
    private boolean dumpWasDown = false;

    // продажа
    private boolean sellFromMenu = SELL_FROM_MENU_FIRST;
    private int sellBest = Integer.MAX_VALUE;
    private int sellNoProgress = 0;
    private int sellSwitches = 0;

    // покупка
    private int buyItemIdx = 0;
    private int buyBest = 0;
    private int buyNoProgress = 0;
    private boolean boughtAny = false;

    public AutoBuyer() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientSetup(FMLClientSetupEvent e) {
        toggleKey = new KeyBinding("Вкл/выкл автоскупщик", GLFW.GLFW_KEY_R, "AutoBuyer");
        ClientRegistry.registerKeyBinding(toggleKey);
    }

    private void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(new StringTextComponent("[AutoBuyer] " + s), false);
    }

    private void setState(State s) { state = s; timer = 0; }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) { state = State.OFF; return; }

        ClientPlayerEntity p = mc.player;
        Screen scr = mc.screen;
        ContainerScreen<?> cs = (scr instanceof ContainerScreen) ? (ContainerScreen<?>) scr : null;

        // отладочная клавиша (опрашиваем напрямую, т.к. KeyBinding не срабатывает при открытом меню)
        boolean dumpDown = InputMappings.isKeyDown(mc.getWindow().getWindow(), DUMP_KEY);
        if (dumpDown && !dumpWasDown && cs != null) dumpMenu(p, cs);
        dumpWasDown = dumpDown;

        while (toggleKey != null && toggleKey.consumeClick()) {
            if (state == State.OFF) { msg("ВКЛ"); setState(State.SEND_SELL_CMD); }
            else { msg("ВЫКЛ"); state = State.OFF; p.closeContainer(); }
        }
        if (state == State.OFF) return;

        timer++;

        switch (state) {

            // ---------- ПРОДАЖА ----------
            case SEND_SELL_CMD:
                if (timer < 10) return;
                p.chat(SELL_COMMAND);
                setState(State.WAIT_SECTIONS);
                break;

            case WAIT_SECTIONS:
                if (cs != null && title(cs).contains("секцию")) {
                    if (timer < 5) return;
                    Container c = cs.getMenu();
                    int slot = findInMenuByName(c, p, SECTION_NAME, false);
                    if (slot < 0) slot = SECTION_SLOT_FALLBACK;
                    click(mc, c, slot, 0, ClickType.PICKUP);
                    setState(State.WAIT_SELL_MENU);
                } else if (timer > OPEN_TIMEOUT_TICKS) timeout("не открылось меню /buyer");
                break;

            case WAIT_SELL_MENU:
                if (cs != null && !title(cs).contains("секцию")) {
                    sellFromMenu = SELL_FROM_MENU_FIRST;
                    sellBest = Integer.MAX_VALUE;
                    sellNoProgress = 0;
                    sellSwitches = 0;
                    setState(State.SELLING);
                } else if (timer > OPEN_TIMEOUT_TICKS) timeout("не открылась вкладка Ресурсы");
                break;

            case SELLING:
                tickSelling(mc, p, cs);
                break;

            // ---------- ПОКУПКА ----------
            case INTERACT_NPC: {
                if (timer < 10) return;
                Entity npc = findNpc(mc, p);
                if (npc == null) { timeout("не нашёл NPC Археолог рядом (встань ближе)"); return; }
                mc.gameMode.interact(p, npc, Hand.MAIN_HAND);
                setState(State.WAIT_MAIN_MENU);
                break;
            }

            case WAIT_MAIN_MENU:
                if (cs != null && title(cs).contains(MAIN_MENU_TITLE)) {
                    if (timer < 5) return;
                    Container c = cs.getMenu();
                    int slot = findInMenuByName(c, p, SHOP_ITEM_NAME, false);
                    if (slot < 0) slot = SHOP_ITEM_FALLBACK_SLOT;
                    click(mc, c, slot, 0, ClickType.PICKUP);
                    setState(State.WAIT_SHOP);
                } else if (timer > OPEN_TIMEOUT_TICKS) timeout("не открылось меню NPC");
                break;

            case WAIT_SHOP:
                if (cs != null && title(cs).contains(SHOP_TITLE)) {
                    if (timer < 5) return;
                    buyItemIdx = 0;
                    boughtAny = false;
                    beginBuyItem(p);
                    setState(State.BUYING);
                } else if (timer > OPEN_TIMEOUT_TICKS) timeout("не открылось меню покупки");
                break;

            case BUYING:
                tickBuying(mc, p, cs);
                break;

            default:
                break;
        }
    }

    // ===================== ПРОДАЖА =====================

    private void tickSelling(Minecraft mc, ClientPlayerEntity p, ContainerScreen<?> cs) {
        if (cs == null) { timeout("меню скупщика закрылось"); return; }
        if (timer % SELL_DELAY_TICKS != 0) return;
        Container c = cs.getMenu();

        int left = countSellable(p);
        if (left == 0) {                       // всё продано
            p.closeContainer();
            setState(State.INTERACT_NPC);
            return;
        }

        // прогресс = количество ресурсов в инвентаре уменьшилось
        if (left < sellBest) { sellBest = left; sellNoProgress = 0; sellSwitches = 0; }
        else sellNoProgress++;

        if (sellNoProgress >= SELL_STUCK_LIMIT) {
            sellNoProgress = 0;
            sellSwitches++;
            if (sellSwitches >= 2) {
                timeout("не получается продать ни одним способом. Открой меню и нажми K, "
                        + "чтобы увидеть слоты, затем поправь настройки");
                return;
            }
            sellFromMenu = !sellFromMenu;
            msg("Не продаётся, пробую другой способ: "
                    + (sellFromMenu ? "клик по ресурсу в меню" : "клик по предмету в инвентаре"));
        }

        int slot = sellFromMenu ? findSellSlotInMenu(c, p) : findSellSlotInInventory(c, p);
        if (slot < 0) {                        // этим способом кликать не по чему - сразу меняем способ
            sellNoProgress = SELL_STUCK_LIMIT;
            return;
        }
        click(mc, c, slot, 0, ClickType.QUICK_MOVE);   // Shift + ЛКМ
    }

    private static boolean isSellable(ItemStack st) {
        if (st.isEmpty() || !SELL_ITEMS.contains(st.getItem())) return false;
        if (SELL_SKIP_SPECIAL && (st.isEnchanted() || st.hasCustomHoverName())) return false;
        return true;
    }

    private static int countSellable(PlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.inventory.getItem(i);
            if (isSellable(st)) n += st.getCount();
        }
        return n;
    }

    private static int countItem(PlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.inventory.getItem(i);
            if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
        }
        return n;
    }

    // иконка ресурса в самом меню скупщика (не в инвентаре игрока)
    private static int findSellSlotInMenu(Container c, PlayerEntity p) {
        for (Slot s : c.slots) {
            if (s.container == p.inventory) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty() || !SELL_ITEMS.contains(st.getItem())) continue;
            if (countItem(p, st.getItem()) > 0) return s.index;   // клик имеет смысл, только если такой ресурс у нас есть
        }
        return -1;
    }

    // предмет в инвентаре игрока
    private static int findSellSlotInInventory(Container c, PlayerEntity p) {
        for (Slot s : c.slots) {
            if (s.container != p.inventory) continue;
            if (isSellable(s.getItem())) return s.index;
        }
        return -1;
    }

    // ===================== ПОКУПКА =====================

    private void beginBuyItem(PlayerEntity p) {
        buyBest = countItem(p, BUY_ITEMS[buyItemIdx]);
        buyNoProgress = 0;
    }

    private void nextBuyItem(PlayerEntity p) {
        buyItemIdx++;
        if (buyItemIdx < BUY_NAMES.length) beginBuyItem(p);
    }

    private void tickBuying(Minecraft mc, ClientPlayerEntity p, ContainerScreen<?> cs) {
        if (cs == null) { timeout("меню покупки закрылось"); return; }
        if (timer % BUY_DELAY_TICKS != 0) return;
        Container c = cs.getMenu();

        for (int k = 0; k < BUY_CLICKS_PER_TICK; k++) {
            if (buyItemIdx >= BUY_NAMES.length || p.inventory.getFreeSlot() == -1) {
                finishBuying(p);
                return;
            }

            int have = countItem(p, BUY_ITEMS[buyItemIdx]);
            if (have > buyBest) { buyBest = have; buyNoProgress = 0; boughtAny = true; }
            else buyNoProgress++;

            // набрали сколько хотели, либо предмет не покупается (нет денег / нет в наличии)
            if (have >= BUY_STACKS_PER_ITEM * 64 || buyNoProgress >= BUY_STUCK_LIMIT) {
                nextBuyItem(p);
                continue;
            }

            int slot = findInMenuByName(c, p, BUY_NAMES[buyItemIdx], true);
            if (slot < 0) slot = BUY_FALLBACK_SLOTS[buyItemIdx];
            click(mc, c, slot, 0, BUY_SHIFT_CLICK ? ClickType.QUICK_MOVE : ClickType.PICKUP);
        }
    }

    private void finishBuying(ClientPlayerEntity p) {
        p.closeContainer();
        if (!boughtAny) { timeout("ничего не куплено (нет денег или места?)"); return; }
        setState(State.SEND_SELL_CMD);
    }

    // ===================== ОБЩЕЕ =====================

    private void timeout(String reason) {
        msg("Остановка: " + reason);
        state = State.OFF;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen instanceof ContainerScreen) mc.player.closeContainer();
    }

    private static String title(ContainerScreen<?> cs) {
        return cs.getTitle().getString();
    }

    private static void click(Minecraft mc, Container c, int slot, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(c.containerId, slot, button, type, mc.player);
    }

    // ищем слот в меню (не в инвентаре игрока) по названию предмета
    private static int findInMenuByName(Container c, PlayerEntity p, String name, boolean exact) {
        for (Slot s : c.slots) {
            if (s.container == p.inventory) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String n = st.getHoverName().getString();
            if (exact ? n.equals(name) : n.contains(name)) return s.index;
        }
        return -1;
    }

    // отладка: что лежит в слотах открытого меню
    private void dumpMenu(ClientPlayerEntity p, ContainerScreen<?> cs) {
        Container c = cs.getMenu();
        msg("Меню: \"" + title(cs) + "\"");
        int shown = 0;
        for (Slot s : c.slots) {
            if (s.container == p.inventory) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String line = "#" + s.index + " " + st.getHoverName().getString()
                    + " x" + st.getCount() + " [" + st.getItem().getRegistryName() + "]";
            System.out.println("[AutoBuyer] " + line);
            if (shown < 30) msg(line);
            shown++;
        }
        if (shown > 30) msg("... и ещё " + (shown - 30) + " (полный список в логе)");
        if (shown == 0) msg("в меню нет предметов");
    }

    // NPC = житель, ближайший к голограмме "АРХЕОЛОГ"; если голограммы нет, ближайший житель
    private static Entity findNpc(Minecraft mc, PlayerEntity p) {
        if (mc.level == null) return null;
        ArmorStandEntity holo = null;
        double best = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStandEntity
                    && e.getName().getString().toLowerCase().contains(NPC_HOLOGRAM_TEXT)) {
                double d = p.distanceToSqr(e);
                if (d < best) { best = d; holo = (ArmorStandEntity) e; }
            }
        }
        VillagerEntity result = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof VillagerEntity)) continue;
            if (p.distanceTo(e) > NPC_MAX_DIST) continue;
            double d = (holo != null) ? holo.distanceToSqr(e) : p.distanceToSqr(e);
            if (d < bestD) { bestD = d; result = (VillagerEntity) e; }
        }
        return result;
    }
}
