package ru.villagersociety;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillagerSocietyMod implements ModInitializer {
    public static final String MOD_ID = "villagersociety";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Личные данные жителя: имя + его собственное мнение о других (у каждого своя иерархия). Сохраняется в мире. */
    public static final AttachmentType<SocietyData> SOCIETY = AttachmentRegistry.create(
            Identifier.of(MOD_ID, "society"),
            builder -> builder.initializer(() -> SocietyData.EMPTY).persistent(SocietyData.CODEC)
    );

    @Override
    public void onInitialize() {
        ServerTickEvents.END_WORLD_TICK.register(SocietyManager::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SocietyCommand.register(dispatcher));
        LOGGER.info("Общество жителей загружено: иерархия, голосования и разговоры включены.");
    }
}
