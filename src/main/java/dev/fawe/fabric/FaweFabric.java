package dev.fawe.fabric;

import com.sk89q.worldedit.fabric.FabricWorldEdit;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FaweFabric implements ModInitializer {
    public static final String MOD_ID = "fawe";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        try {
            // Full FabricAsyncWorldEdit-derived platform layer.
            new FabricWorldEdit().onInitialize();
        } catch (Throwable throwable) {
            // The base jar is not a fat jar yet. Keep the mod loadable when
            // FAWE core is not present on the runtime classpath.
            LOGGER.warn("FAWE platform is not available in this runtime: {}", throwable.toString());
        }
    }
}
