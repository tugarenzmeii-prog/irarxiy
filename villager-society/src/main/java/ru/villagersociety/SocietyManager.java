package ru.villagersociety;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Вся логика общества жителей.
 *  - собрания: жители рядом голосуют, кто главнее и кто хуже (у каждого своё мнение, они его меняют сами);
 *  - ранги считаются из мнений всех; пока никто ни за кого не голосовал — иерархии нет;
 *  - еда делится по рангам, малыши кормятся первыми, правители и знать их не трогают;
 *  - изгои всю ночь стоят на ногах, простолюдины стоят по очереди;
 *  - правитель посылает слабых с данью, знать расталкивает нижних;
 *  - жители разговаривают в чате (слышно игрокам в радиусе 40 блоков).
 */
public final class SocietyManager {
    private static final Random RNG = new Random();

    private static final Map<UUID, Rank> RANKS = new HashMap<>();
    private static final Map<UUID, Integer> SCORES = new HashMap<>();
    private static final Map<UUID, Order> ORDERS = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();

    private record Order(ServerWorld world, UUID subject, UUID giver, long deadline) {}
    private record Task(ServerWorld world, long time, Runnable action) {}

    private SocietyManager() {}

    // ============================================================ главный тик
    public static void tick(ServerWorld world) {
        long t = world.getTime();
        runTasks(world, t);
        if (t % 10 == 0) enforceNight(world);
        if (t % 20 == 5) processOrders(world);
        if (t % 100 == 7) updateRanks(world);
        if (t % 200 == 50) hunger(world);
        if (t % 240 == 31) holdAssembly(world);
        if (t % 300 == 200) smallTalk(world);
        if (t % 360 == 123) giveOrders(world);
        if (t % 400 == 300) shove(world);
        if (t % 500 == 77) feed(world);
        if (t % 12000 == 0 && RANKS.size() > 5000) RANKS.clear();
    }

    // ============================================================ публичные помощники (для команды)
    public static Rank rankOf(VillagerEntity v) {
        return RANKS.getOrDefault(v.getUuid(), Rank.NONE);
    }

    public static int scoreOf(VillagerEntity v) {
        return SCORES.getOrDefault(v.getUuid(), 0);
    }

    public static String nameOf(VillagerEntity v) {
        return ensureName(v).name();
    }

    public static boolean isRation(ItemStack s) {
        return s.isOf(Items.BREAD) || s.isOf(Items.CARROT) || s.isOf(Items.POTATO) || s.isOf(Items.BEETROOT);
    }

    public static int countFood(VillagerEntity v) {
        SimpleInventory inv = v.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (isRation(s)) n += s.getCount();
        }
        return n;
    }

    // ============================================================ время суток
    /** Если после обновления Minecraft эта строка перестанет компилироваться — поправь только её. */
    private static boolean isNight(ServerWorld w) {
        return w.getTimeOfDay() % 24000L >= 12000L;
    }

    // ============================================================ планировщик реплик
    private static void later(ServerWorld w, int delayTicks, Runnable r) {
        TASKS.add(new Task(w, w.getTime() + delayTicks, r));
    }

    private static void runTasks(ServerWorld world, long now) {
        if (TASKS.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task k = it.next();
            if (k.world() == world && k.time() <= now) {
                due.add(k.action());
                it.remove();
            }
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (Exception e) {
                VillagerSocietyMod.LOGGER.warn("Ошибка в отложенной реплике", e);
            }
        }
    }

    // ============================================================ списки и группы
    private static List<VillagerEntity> adults(ServerWorld w) {
        return new ArrayList<>(w.getEntitiesByType(EntityType.VILLAGER, v -> v.isAlive() && !v.isBaby()));
    }

    private static List<VillagerEntity> babies(ServerWorld w) {
        return new ArrayList<>(w.getEntitiesByType(EntityType.VILLAGER, v -> v.isAlive() && v.isBaby()));
    }

    private static List<List<VillagerEntity>> clusters(List<VillagerEntity> all, double radius) {
        double r2 = radius * radius;
        List<List<VillagerEntity>> result = new ArrayList<>();
        Set<VillagerEntity> seen = new HashSet<>();
        for (VillagerEntity start : all) {
            if (!seen.add(start)) continue;
            List<VillagerEntity> comp = new ArrayList<>();
            Deque<VillagerEntity> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                VillagerEntity c = queue.poll();
                comp.add(c);
                for (VillagerEntity o : all) {
                    if (!seen.contains(o) && c.squaredDistanceTo(o) <= r2) {
                        seen.add(o);
                        queue.add(o);
                    }
                }
            }
            result.add(comp);
        }
        return result;
    }

    private static boolean playerNear(ServerWorld w, VillagerEntity v, int radius) {
        double r2 = (double) radius * radius;
        return !w.getPlayers(p -> p.squaredDistanceTo(v) < r2).isEmpty();
    }

    private static Rank rank(VillagerEntity v) {
        return RANKS.getOrDefault(v.getUuid(), Rank.NONE);
    }

    // ============================================================ имена и подписи
    private static SocietyData ensureName(VillagerEntity v) {
        SocietyData d = v.getAttachedOrCreate(VillagerSocietyMod.SOCIETY);
        Text cn = v.getCustomName();
        String cur = cn == null ? null : cn.getString();
        if (cur != null && !cur.isEmpty() && !cur.startsWith("[") && !cur.equals(d.name())) {
            d = d.withName(cur); // игрок дал имя биркой — уважаем
            v.setAttached(VillagerSocietyMod.SOCIETY, d);
        } else if (d.name().isEmpty()) {
            d = d.withName(Speech.nameFor(v.getUuid()));
            v.setAttached(VillagerSocietyMod.SOCIETY, d);
        }
        return d;
    }

    private static void applyLabel(VillagerEntity v, Rank r) {
        String name = ensureName(v).name();
        Text label = r == Rank.NONE
                ? Text.literal(name).formatted(Formatting.WHITE)
                : Text.literal("[" + r.title + "] " + name).formatted(r.color);
        Text cur = v.getCustomName();
        if (cur == null || !cur.getString().equals(label.getString())) {
            v.setCustomName(label);
        }
        v.setCustomNameVisible(true);
    }

    // ============================================================ речь
    private static void say(ServerWorld w, VillagerEntity v, String msg) {
        if (!v.isAlive()) return;
        Rank r = rank(v);
        Text line = Text.literal("<" + nameOf(v) + "> ").formatted(r == Rank.NONE ? Formatting.GRAY : r.color)
                .append(Text.literal(msg).formatted(Formatting.WHITE));
        for (ServerPlayerEntity p : w.getPlayers(pl -> pl.squaredDistanceTo(v) < 1600)) {
            p.sendMessage(line, false);
        }
        w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_VILLAGER_TRADE,
                SoundCategory.NEUTRAL, 0.8f, 0.9f + RNG.nextFloat() * 0.2f);
    }

    private static String fmt(String pattern, String name) {
        return pattern.contains("%s") ? String.format(pattern, name) : pattern;
    }

    // ============================================================ ранги
    private static void updateRanks(ServerWorld world) {
        List<VillagerEntity> all = adults(world);
        for (List<VillagerEntity> cl : clusters(all, 24)) {
            Map<UUID, Integer> score = new HashMap<>();
            for (VillagerEntity v : cl) {
                int s = 0;
                for (VillagerEntity m : cl) {
                    if (m != v) s += m.getAttachedOrCreate(VillagerSocietyMod.SOCIETY).opinionOf(v.getUuid());
                }
                score.put(v.getUuid(), s);
            }
            List<VillagerEntity> sorted = new ArrayList<>(cl);
            sorted.sort((x, y) -> {
                int c = Integer.compare(score.get(y.getUuid()), score.get(x.getUuid()));
                return c != 0 ? c : x.getUuid().compareTo(y.getUuid());
            });
            int n = sorted.size();
            boolean hierarchy = n >= 2 && score.get(sorted.get(0).getUuid()) > score.get(sorted.get(n - 1).getUuid());
            int nobles = Math.max(0, (n - 1) * 3 / 10);
            int outcasts = n >= 3 ? Math.max(1, n / 4) : 0;
            for (int i = 0; i < n; i++) {
                VillagerEntity v = sorted.get(i);
                Rank r;
                if (!hierarchy) r = Rank.NONE;
                else if (i == 0) r = Rank.RULER;
                else if (i <= nobles) r = Rank.NOBLE;
                else if (i >= n - outcasts) r = Rank.OUTCAST;
                else r = Rank.COMMON;
                setRank(world, v, r, score.get(v.getUuid()));
            }
        }
        // малыши: имя есть, ранга нет
        for (VillagerEntity baby : babies(world)) {
            RANKS.remove(baby.getUuid());
            applyLabel(baby, Rank.NONE);
        }
    }

    private static void setRank(ServerWorld world, VillagerEntity v, Rank r, int score) {
        Rank prev = RANKS.put(v.getUuid(), r);
        SCORES.put(v.getUuid(), score);
        applyLabel(v, r);
        if (prev != null && prev != r && playerNear(world, v, 40)) {
            if (r == Rank.RULER) say(world, v, Speech.pick(Speech.NEW_RULER));
            else if (r == Rank.OUTCAST) say(world, v, Speech.pick(Speech.NEW_OUTCAST));
        }
    }

    // ============================================================ голосование
    private static double prestige(VillagerEntity c) {
        double charisma = Math.floorMod(c.getUuid().hashCode(), 7);
        return charisma + Math.min(countFood(c), 8) * 0.4;
    }

    private static UUID argMax(Map<UUID, Integer> votes, UUID excluded) {
        int max = 0;
        List<UUID> top = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : votes.entrySet()) {
            if (e.getKey().equals(excluded)) continue;
            if (e.getValue() > max) {
                max = e.getValue();
                top.clear();
                top.add(e.getKey());
            } else if (e.getValue() == max && max > 0) {
                top.add(e.getKey());
            }
        }
        return top.isEmpty() ? null : top.get(RNG.nextInt(top.size()));
    }

    private static List<VillagerEntity> pickGroup(List<VillagerEntity> all, VillagerEntity conv) {
        List<VillagerEntity> others = new ArrayList<>();
        for (VillagerEntity v : all) {
            if (v != conv && v.squaredDistanceTo(conv) <= 144) others.add(v);
        }
        Collections.shuffle(others, RNG);
        List<VillagerEntity> group = new ArrayList<>();
        group.add(conv);
        for (int i = 0; i < others.size() && group.size() < 8; i++) group.add(others.get(i));
        return group;
    }

    private static VillagerEntity byId(ServerWorld w, UUID id) {
        Entity e = id == null ? null : w.getEntity(id);
        return e instanceof VillagerEntity v && v.isAlive() ? v : null;
    }

    private static void holdAssembly(ServerWorld world) {
        List<VillagerEntity> all = adults(world);
        if (all.size() < 2) return;
        VillagerEntity conv = all.get(RNG.nextInt(all.size()));
        List<VillagerEntity> group = pickGroup(all, conv);
        if (group.size() < 2) return;

        // --- бюллетени: каждый голосует «кто главнее» и «кто хуже»
        Map<UUID, Integer> upVotes = new HashMap<>();
        Map<UUID, Integer> downVotes = new HashMap<>();
        Map<UUID, UUID> upChoice = new HashMap<>();
        Map<UUID, UUID> downChoice = new HashMap<>();
        for (VillagerEntity voter : group) {
            SocietyData vd = voter.getAttachedOrCreate(VillagerSocietyMod.SOCIETY);
            VillagerEntity bestC = null;
            VillagerEntity worstC = null;
            double best = -1e9;
            double worst = 1e9;
            for (VillagerEntity c : group) {
                double base = prestige(c);
                if (c == voter) {
                    double s = base * 0.5 + RNG.nextDouble() * 4 + 1; // амбиции
                    if (s > best) {
                        best = s;
                        bestC = c;
                    }
                } else {
                    double s = vd.opinionOf(c.getUuid()) + base + RNG.nextDouble() * 6;
                    if (s > best) {
                        best = s;
                        bestC = c;
                    }
                    if (s < worst) {
                        worst = s;
                        worstC = c;
                    }
                }
            }
            if (bestC != null) {
                upChoice.put(voter.getUuid(), bestC.getUuid());
                upVotes.merge(bestC.getUuid(), 1, Integer::sum);
            }
            if (worstC != null && worstC != bestC) {
                downChoice.put(voter.getUuid(), worstC.getUuid());
                downVotes.merge(worstC.getUuid(), 1, Integer::sum);
            }
        }
        UUID winner = argMax(upVotes, null);
        UUID loser = argMax(downVotes, winner);

        // --- мнения меняются сразу: каждый жител сам решает, что думать
        for (VillagerEntity voter : group) {
            UUID vid = voter.getUuid();
            SocietyData d = voter.getAttachedOrCreate(VillagerSocietyMod.SOCIETY);
            UUID up = upChoice.get(vid);
            if (up != null && !up.equals(vid)) d = d.adjust(up, 2);
            UUID dn = downChoice.get(vid);
            if (dn != null) d = d.adjust(dn, -2);
            if (winner != null && !winner.equals(vid) && RNG.nextInt(100) < 60) d = d.adjust(winner, 3);
            if (loser != null && !loser.equals(vid) && RNG.nextInt(100) < 60) d = d.adjust(loser, -3);
            voter.setAttached(VillagerSocietyMod.SOCIETY, d);
        }

        if (!playerNear(world, conv, 40)) return;

        // --- разговор с задержками, чтобы читалось как настоящее собрание
        int delay = 0;
        say(world, conv, Speech.pick(Speech.ASSEMBLY_START));
        for (VillagerEntity voter : group) {
            delay += 35;
            UUID vid = voter.getUuid();
            UUID up = upChoice.get(vid);
            UUID dn = downChoice.get(vid);
            final boolean sayDown = dn != null && RNG.nextInt(100) < 40;
            later(world, delay, () -> {
                VillagerEntity me = byId(world, vid);
                if (me == null) return;
                if (up != null && up.equals(vid)) {
                    say(world, me, Speech.pick(Speech.VOTE_SELF));
                } else if (up != null) {
                    VillagerEntity c = byId(world, up);
                    if (c != null) say(world, me, fmt(Speech.pick(Speech.VOTE_UP), nameOf(c)));
                }
                if (sayDown) {
                    VillagerEntity c = byId(world, dn);
                    if (c != null) say(world, me, fmt(Speech.pick(Speech.VOTE_DOWN), nameOf(c)));
                }
            });
        }
        delay += 50;
        final UUID convId = conv.getUuid();
        final UUID win = winner;
        final UUID lose = loser;
        later(world, delay, () -> {
            VillagerEntity c0 = byId(world, convId);
            VillagerEntity w0 = byId(world, win);
            VillagerEntity l0 = byId(world, lose);
            if (c0 != null && w0 != null) say(world, c0, fmt(Speech.pick(Speech.RESULT_WIN), nameOf(w0)));
            if (c0 != null && l0 != null) say(world, c0, fmt(Speech.pick(Speech.RESULT_LOSE), nameOf(l0)));
        });
        later(world, delay + 40, () -> {
            VillagerEntity w0 = byId(world, win);
            VillagerEntity l0 = byId(world, lose);
            if (w0 != null) say(world, w0, Speech.pick(Speech.WIN_REACT));
            if (l0 != null) say(world, l0, Speech.pick(Speech.LOSE_REACT));
        });
    }

    // ============================================================ болтовня
    private static void smallTalk(ServerWorld world) {
        List<VillagerEntity> everyone = new ArrayList<>(world.getEntitiesByType(EntityType.VILLAGER, v -> v.isAlive()));
        if (everyone.isEmpty()) return;
        VillagerEntity a = everyone.get(RNG.nextInt(everyone.size()));
        if (!playerNear(world, a, 40)) return;
        if (a.isBaby()) {
            say(world, a, Speech.pick(Speech.BABY));
            return;
        }
        Rank ra = rank(a);
        VillagerEntity nearest = null;
        double bd = 100;
        for (VillagerEntity o : adults(world)) {
            if (o == a) continue;
            double d = a.squaredDistanceTo(o);
            if (d < bd) {
                bd = d;
                nearest = o;
            }
        }
        if (nearest == null) {
            say(world, a, Speech.pick(Speech.mono(ra)));
            return;
        }
        final VillagerEntity b = nearest;
        say(world, a, fmt(Speech.pick(Speech.talk(ra)), nameOf(b)));
        final UUID bid = b.getUuid();
        later(world, 50, () -> {
            VillagerEntity bb = byId(world, bid);
            if (bb != null) say(world, bb, Speech.pick(Speech.reply(rank(bb))));
        });
    }

    // ============================================================ ночь: кто не спит
    private static boolean mustStand(VillagerEntity v, Rank r, ServerWorld w) {
        if (r == Rank.OUTCAST) return true; // изгои не спят и не отдыхают никогда
        if (r == Rank.COMMON) { // простолюдины стоят по очереди
            long day = w.getTimeOfDay() / 24000L;
            return (Math.floorMod(v.getUuid().hashCode(), 2) + day) % 2 == 0;
        }
        return false; // правитель, знать и все, пока иерархии нет — спят как обычно
    }

    private static void enforceNight(ServerWorld world) {
        if (RANKS.isEmpty() || !isNight(world)) return;
        for (VillagerEntity v : adults(world)) {
            if (ORDERS.containsKey(v.getUuid())) continue;
            if (!mustStand(v, rank(v), world)) continue;
            if (v.isSleeping()) v.wakeUp();
            v.getBrain().forget(MemoryModuleType.HOME); // не идти к кровати
            v.getNavigation().stop();                   // стоять на месте
        }
    }

    // ============================================================ голод
    private static void hunger(ServerWorld world) {
        if (RANKS.isEmpty()) return;
        for (VillagerEntity v : adults(world)) {
            Rank r = rank(v);
            if ((r == Rank.COMMON || r == Rank.OUTCAST) && countFood(v) == 0) {
                v.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 300, 0, false, false));
                if (RNG.nextInt(100) < 25 && playerNear(world, v, 40)) {
                    say(world, v, Speech.pick(Speech.HUNGRY));
                }
            }
        }
    }

    // ============================================================ раздача еды
    private static int give(VillagerEntity target, Map<Item, Integer> pool, int want) {
        int given = 0;
        Iterator<Map.Entry<Item, Integer>> it = pool.entrySet().iterator();
        while (given < want && it.hasNext()) {
            Map.Entry<Item, Integer> e = it.next();
            int k = Math.min(want - given, e.getValue());
            ItemStack rest = target.getInventory().addStack(new ItemStack(e.getKey(), k));
            int accepted = k - rest.getCount();
            given += accepted;
            int left = e.getValue() - accepted;
            if (left <= 0) it.remove();
            else e.setValue(left);
            if (accepted < k) break; // инвентарь полон
        }
        return given;
    }

    private static void feed(ServerWorld world) {
        if (RANKS.isEmpty()) return;
        List<VillagerEntity> adults = adults(world);
        List<VillagerEntity> babies = babies(world);
        for (List<VillagerEntity> cl : clusters(adults, 16)) {
            boolean ranked = false;
            for (VillagerEntity v : cl) {
                if (rank(v) != Rank.NONE) ranked = true;
            }
            if (!ranked) continue; // пока иерархии нет — еда не делится по правилам

            // 1. собираем всю еду общины в общий котёл
            Map<Item, Integer> pool = new LinkedHashMap<>();
            for (VillagerEntity v : cl) {
                SimpleInventory inv = v.getInventory();
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack s = inv.getStack(i);
                    if (isRation(s)) {
                        pool.merge(s.getItem(), s.getCount(), Integer::sum);
                        inv.setStack(i, ItemStack.EMPTY);
                    }
                }
            }
            if (pool.isEmpty()) continue;

            List<VillagerEntity> sorted = new ArrayList<>(cl);
            sorted.sort((x, y) -> {
                int c = Integer.compare(rank(y).power, rank(x).power);
                return c != 0 ? c : Integer.compare(scoreOf(y), scoreOf(x));
            });

            // 2. малыши — самые важные для выживания: кормятся первыми
            boolean kidsFed = false;
            for (VillagerEntity kid : babies) {
                boolean near = false;
                for (VillagerEntity v : cl) {
                    if (v.squaredDistanceTo(kid) <= 256) {
                        near = true;
                        break;
                    }
                }
                if (!near) continue;
                int need = 4 - countFood(kid);
                if (need > 0 && give(kid, pool, need) > 0) kidsFed = true;
            }

            // 3. взрослые — строго по рангу, сколько положено
            for (VillagerEntity v : sorted) {
                give(v, pool, rank(v).foodQuota);
            }

            // 4. остатки забирают верхние
            for (VillagerEntity v : sorted) {
                if (pool.isEmpty()) break;
                give(v, pool, 64);
            }
            // 5. то, что никуда не влезло, падает на землю у правителя
            VillagerEntity top = sorted.get(0);
            for (Map.Entry<Item, Integer> e : pool.entrySet()) {
                world.spawnEntity(new ItemEntity(world, top.getX(), top.getY(), top.getZ(),
                        new ItemStack(e.getKey(), e.getValue())));
            }

            // реплики
            if (playerNear(world, top, 40)) {
                if (kidsFed && RNG.nextInt(100) < 50) {
                    VillagerEntity speaker = rank(top) != Rank.NONE ? top : sorted.get(0);
                    say(world, speaker, Speech.pick(Speech.FEED_KIDS));
                } else if (rank(top) == Rank.RULER && RNG.nextInt(100) < 40) {
                    say(world, top, Speech.pick(Speech.FEED_RULER));
                }
            }
        }
    }

    // ============================================================ приказы правителя
    private static ItemStack takeOne(VillagerEntity v) {
        SimpleInventory inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (isRation(s)) return inv.removeStack(i, 1);
        }
        return ItemStack.EMPTY;
    }

    private static void giveOrders(ServerWorld world) {
        if (RANKS.isEmpty()) return;
        for (List<VillagerEntity> cl : clusters(adults(world), 20)) {
            VillagerEntity ruler = null;
            for (VillagerEntity v : cl) {
                if (rank(v) == Rank.RULER) ruler = v;
            }
            if (ruler == null || RNG.nextInt(100) >= 60) continue;
            List<VillagerEntity> subjects = new ArrayList<>();
            for (VillagerEntity v : cl) {
                Rank r = rank(v);
                if ((r == Rank.COMMON || r == Rank.OUTCAST) && v.squaredDistanceTo(ruler) <= 256
                        && !ORDERS.containsKey(v.getUuid())) {
                    subjects.add(v);
                }
            }
            if (subjects.isEmpty()) continue;
            VillagerEntity s = subjects.get(RNG.nextInt(subjects.size()));
            ORDERS.put(s.getUuid(), new Order(world, s.getUuid(), ruler.getUuid(), world.getTime() + 400));
            if (playerNear(world, ruler, 40)) {
                say(world, ruler, fmt(Speech.pick(Speech.ORDER), nameOf(s)));
                final UUID sid = s.getUuid();
                later(world, 40, () -> {
                    VillagerEntity ss = byId(world, sid);
                    if (ss != null) say(world, ss, Speech.pick(Speech.ORDER_ACK));
                });
            }
        }
    }

    private static void processOrders(ServerWorld world) {
        if (ORDERS.isEmpty()) return;
        Iterator<Order> it = ORDERS.values().iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (o.world() != world) continue;
            VillagerEntity sub = byId(world, o.subject());
            VillagerEntity giver = byId(world, o.giver());
            if (sub == null || giver == null || world.getTime() > o.deadline()) {
                it.remove();
                continue;
            }
            sub.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(giver, 0.7f, 1));
            if (sub.squaredDistanceTo(giver) < 6.0) {
                it.remove();
                ItemStack food = takeOne(sub);
                boolean talk = playerNear(world, giver, 40);
                if (!food.isEmpty()) {
                    ItemStack rest = giver.getInventory().addStack(food);
                    if (!rest.isEmpty()) {
                        world.spawnEntity(new ItemEntity(world, giver.getX(), giver.getY(), giver.getZ(), rest));
                    }
                    if (talk) {
                        say(world, sub, Speech.pick(Speech.TRIBUTE_OK));
                        say(world, giver, Speech.pick(Speech.TRIBUTE_TAKEN));
                    }
                } else {
                    if (talk) {
                        say(world, sub, Speech.pick(Speech.TRIBUTE_EMPTY));
                        say(world, giver, Speech.pick(Speech.TRIBUTE_ANGRY));
                    }
                    sub.takeKnockback(0.5, giver.getX() - sub.getX(), giver.getZ() - sub.getZ());
                }
            }
        }
    }

    // ============================================================ грубость верхов (малышей не трогают)
    private static void shove(ServerWorld world) {
        if (RANKS.isEmpty()) return;
        List<VillagerEntity> adults = adults(world); // только взрослые: малыши под защитой
        for (VillagerEntity boss : adults) {
            if (rank(boss).power < Rank.NOBLE.power || RNG.nextInt(100) >= 50) continue;
            for (VillagerEntity low : adults) {
                Rank r = rank(low);
                if ((r == Rank.COMMON || r == Rank.OUTCAST) && boss.squaredDistanceTo(low) <= 9) {
                    low.takeKnockback(0.4, boss.getX() - low.getX(), boss.getZ() - low.getZ());
                    world.playSound(null, low.getX(), low.getY(), low.getZ(), SoundEvents.ENTITY_VILLAGER_NO,
                            SoundCategory.NEUTRAL, 0.8f, 1.0f);
                    if (playerNear(world, boss, 40)) {
                        say(world, boss, fmt(Speech.pick(Speech.SHOVE), nameOf(low)));
                    }
                    break;
                }
            }
        }
    }
}
