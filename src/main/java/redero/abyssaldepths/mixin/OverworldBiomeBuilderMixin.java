package redero.abyssaldepths.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.util.Pair;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.worldgen.VanillaBootstrap;

/**
 * Adds the thalan layer biomes to the vanilla overworld biome list (used by all overworld world
 * types). The noise router sets the climate depth to {@link AbyssalDepths#THALAN_DEPTH} below y=-64,
 * a value no vanilla biome uses, so there only these biomes can win and above they never do.
 * <p>
 * The layer is split by temperature and humidity, the same 2D climate as the surface, without gaps
 * or overlaps:
 * <pre>
 *                  humidity &lt; -0.1   -0.1 .. 0.1    &gt;= 0.1
 * temperature &gt;= 0.45  silt dunes    shale flats   thermal reaches
 * temperature 0.2..0.45 silt dunes    shale flats   thalan depths
 * temperature &lt; 0.2    shale flats   shale flats   thalan depths
 * </pre>
 * Silt dunes so tend to lie below dry, warm surfaces like deserts and savannas, thermal reaches
 * below warm and humid ones like jungles.
 */
@Mixin(OverworldBiomeBuilder.class)
public abstract class OverworldBiomeBuilderMixin {
    @Inject(method = "addBiomes", at = @At("TAIL"))
    private void abyssaldepths$addThalanLayer(Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer, CallbackInfo ci) {
        if (VanillaBootstrap.running()) {
            return; // Minecraft's own registry self-check, our biomes do not exist there
        }
        final float lo = -2.0F, hi = 2.0F; // beyond any climate value
        add(consumer, AbyssalDepths.THALAN_DEPTHS, lo, 0.45F, 0.1F, hi);
        add(consumer, AbyssalDepths.THERMAL_REACHES, 0.45F, hi, 0.1F, hi);
        add(consumer, AbyssalDepths.SILT_DUNES, 0.2F, hi, lo, -0.1F);
        add(consumer, AbyssalDepths.SHALE_FLATS, 0.2F, hi, -0.1F, 0.1F);
        add(consumer, AbyssalDepths.SHALE_FLATS, lo, 0.2F, lo, 0.1F);
    }

    private static void add(Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer, ResourceKey<Biome> biome,
            float tempMin, float tempMax, float humidityMin, float humidityMax) {
        final Climate.Parameter all = Climate.Parameter.span(-2.0F, 2.0F);
        consumer.accept(Pair.of(Climate.parameters(Climate.Parameter.span(tempMin, tempMax), Climate.Parameter.span(humidityMin, humidityMax),
                all, all, Climate.Parameter.point(AbyssalDepths.THALAN_DEPTH), all, 0.0F), biome));
    }
}
