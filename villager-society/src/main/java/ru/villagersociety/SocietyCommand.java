package ru.villagersociety;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** /society — показать иерархию жителей в радиусе 32 блоков. */
public final class SocietyCommand {
    private SocietyCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("society").executes(ctx -> {
            ServerCommandSource src = ctx.getSource();
            ServerPlayerEntity player = src.getPlayerOrThrow();
            ServerWorld world = src.getWorld();

            List<VillagerEntity> list = new ArrayList<>(world.getEntitiesByType(EntityType.VILLAGER,
                    v -> v.isAlive() && v.squaredDistanceTo(player) < 32 * 32));
            if (list.isEmpty()) {
                src.sendFeedback(() -> Text.literal("Рядом нет жителей."), false);
                return 0;
            }
            list.sort((a, b) -> {
                int c = Integer.compare(SocietyManager.rankOf(b).power, SocietyManager.rankOf(a).power);
                return c != 0 ? c : Integer.compare(SocietyManager.scoreOf(b), SocietyManager.scoreOf(a));
            });
            src.sendFeedback(() -> Text.literal("— Общество жителей рядом (" + list.size() + ") —"), false);
            for (VillagerEntity v : list) {
                String line;
                String name = SocietyManager.nameOf(v);
                if (v.isBaby()) {
                    line = "Малыш " + name + " — под защитой, иерархия его не касается";
                } else {
                    Rank r = SocietyManager.rankOf(v);
                    String title = r == Rank.NONE ? "без ранга" : r.title;
                    line = "[" + title + "] " + name + " — очки " + SocietyManager.scoreOf(v)
                            + ", еды: " + SocietyManager.countFood(v);
                }
                final String out = line;
                src.sendFeedback(() -> Text.literal(out), false);
            }
            return list.size();
        }));
    }
}
