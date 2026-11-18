package redero.abyssaldepths;

import net.neoforged.neoforge.common.ModConfigSpec;

/** World settings (serverconfig/abyssaldepths-server.toml in each world): the rules of the game, the same for everyone in it. */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue PRESSURE_IN_CREATIVE = BUILDER
            .comment("Whether players in creative or spectator mode feel the Deep Pressure of the thalan layer too.",
                    "Off (the default): they do not; a pressure they had eases as if they had gone up.")
            .translation("abyssaldepths.config.pressure_in_creative")
            .define("pressureInCreative", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {
    }

    public static boolean pressureInCreative() {
        return SPEC.isLoaded() ? PRESSURE_IN_CREATIVE.get() : false;
    }
}
