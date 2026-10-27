package redero.abyssaldepths.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import redero.abyssaldepths.AbyssalDepths;

/** Client entry point: the config screen in the mod list. */
@Mod(value = AbyssalDepths.MODID, dist = Dist.CLIENT)
public final class AbyssalDepthsClient {
    public AbyssalDepthsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
