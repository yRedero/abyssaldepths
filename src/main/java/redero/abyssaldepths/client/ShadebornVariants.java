package redero.abyssaldepths.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.shadeborn.Shadeborn;

/**
 * How each Shadeborn looks: its skin (the body's texture) and what it wears (accessories, small models of their own
 * drawn on the body's bones, see {@link BbModel#wear}), by its region and its role, from
 * {@code assets/abyssaldepths/shadeborn/variants.json}:
 * <pre>
 * { "regions": { "dunes": { "skins": ["dune_1", "dune_2"], "wear": ["face_wrap"] }, ... },
 *   "roles":   { "stormwatcher": { "wear": ["goggles"] }, "smith": { "skins": ["smith"], "wear": [{"model": "apron", "chance": 0.5}] } } }
 * </pre>
 * A skin is {@code textures/entity/shadeborn/<name>.png}; an accessory is the model
 * {@code models/entity/shadeborn/<name>.json} (from Blockbench by {@code tools/bbmodel_to_model.py}, its groups named
 * after the body's bones they sit on) with the texture {@code textures/entity/shadeborn/<name>.png}. The role's skins go
 * before the region's; what is worn is the region's and the role's together. Each one draws its own from the lists by
 * its id, so it always looks the same (the same on every client). Missing files are skipped: the plain body is drawn.
 * Read again whenever the resources are reloaded (F3+T), with the models.
 */
public final class ShadebornVariants {
    private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shadeborn/variants.json");

    /** What one wears: the model, its texture, and how likely (0 to 1). */
    private record Wear(String name, float chance) {
    }

    private record Entry(List<String> skins, List<Wear> wear) {
        static final Entry NONE = new Entry(List.of(), List.of());
    }

    /** An accessory ready to draw. */
    public record Worn(BbModel model, ResourceLocation texture) {
    }

    /** A Shadeborn's look: its skin (null for the plain one) and what it wears. */
    public record Look(ResourceLocation skin, List<Worn> worn) {
    }

    private static Map<String, Entry> regions, roles;

    private ShadebornVariants() {
    }

    private static final Map<String, Look> LOOKS = new HashMap<>();

    /** The look of one Shadeborn (worked out once for its id, role and region). */
    public static Look of(Shadeborn s) {
        load();
        return LOOKS.computeIfAbsent(s.getUUID() + "/" + s.role().id + "/" + s.region(), k -> work(s));
    }

    private static Look work(Shadeborn s) {
        final Entry region = regions.getOrDefault(s.region(), Entry.NONE), role = roles.getOrDefault(s.role().id, Entry.NONE);
        final long seed = s.getUUID().getLeastSignificantBits() ^ s.getUUID().getMostSignificantBits();
        final List<String> skins = !role.skins().isEmpty() ? role.skins() : region.skins();
        ResourceLocation skin = null;
        if (!skins.isEmpty()) {
            final ResourceLocation at = texture(skins.get((int) Math.floorMod(seed, (long) skins.size())));
            if (exists(at)) {
                skin = at;
            }
        }
        final List<Worn> worn = new ArrayList<>();
        final List<Wear> all = new ArrayList<>(region.wear());
        all.addAll(role.wear());
        for (int i = 0; i < all.size(); i++) {
            final Wear w = all.get(i);
            if (w.chance() < 1 && Math.floorMod(seed * 31 + w.name().hashCode() * 17L + i, 1000) >= w.chance() * 1000) {
                continue;
            }
            final BbModel model = BbModel.find(ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "models/entity/shadeborn/" + w.name() + ".json"));
            if (model != null) {
                worn.add(new Worn(model, texture(w.name())));
            }
        }
        return new Look(skin, worn);
    }

    private static final ResourceLocation ANIMATIONS = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "shadeborn/animations.json");
    private static Map<String, Float> speeds;

    /**
     * How fast an animation plays (1 normal), from {@code assets/abyssaldepths/shadeborn/animations.json}
     * ({@code {"speeds": {"sneaking_body": 0.5}}}), on top of a speed set in Blockbench itself (its Anim Time Update).
     */
    public static float speed(String animation) {
        if (speeds == null) {
            speeds = new HashMap<>();
            Minecraft.getInstance().getResourceManager().getResource(ANIMATIONS).ifPresent(r -> {
                try (Reader reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                    final JsonObject o = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("speeds");
                    for (String k : o.keySet()) {
                        speeds.put(k, o.get(k).getAsFloat());
                    }
                } catch (Exception e) {
                    AbyssalDepths.LOGGER.warn("Could not read {}: {}", ANIMATIONS, e.toString());
                }
            });
        }
        return speeds.getOrDefault(animation, 1.0F);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "textures/entity/shadeborn/" + name + ".png");
    }

    private static final Map<ResourceLocation, Boolean> EXISTS = new HashMap<>();

    private static boolean exists(ResourceLocation at) {
        return EXISTS.computeIfAbsent(at, k -> Minecraft.getInstance().getResourceManager().getResource(k).isPresent());
    }

    private static void load() {
        if (regions != null) {
            return;
        }
        regions = new HashMap<>();
        roles = new HashMap<>();
        final var resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            return;
        }
        try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            read(root.getAsJsonObject("regions"), regions);
            read(root.getAsJsonObject("roles"), roles);
        } catch (Exception e) {
            AbyssalDepths.LOGGER.warn("Could not read the Shadeborn variants {}: {}", FILE, e.toString());
        }
    }

    private static void read(JsonObject from, Map<String, Entry> into) {
        if (from == null) {
            return;
        }
        for (String key : from.keySet()) {
            final JsonObject o = from.getAsJsonObject(key);
            final List<String> skins = new ArrayList<>();
            if (o.has("skins")) {
                for (JsonElement e : o.getAsJsonArray("skins")) {
                    skins.add(e.getAsString());
                }
            }
            final List<Wear> wear = new ArrayList<>();
            if (o.has("wear")) {
                final JsonArray a = o.getAsJsonArray("wear");
                for (JsonElement e : a) {
                    if (e.isJsonObject()) {
                        wear.add(new Wear(e.getAsJsonObject().get("model").getAsString(),
                                e.getAsJsonObject().has("chance") ? e.getAsJsonObject().get("chance").getAsFloat() : 1));
                    } else {
                        wear.add(new Wear(e.getAsString(), 1));
                    }
                }
            }
            into.put(key, new Entry(skins, wear));
        }
    }

    /** On F3+T (or a resource pack change): the variants and the models read again. */
    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                regions = null;
                roles = null;
                EXISTS.clear();
                LOOKS.clear();
                speeds = null;
                BbModel.clear();
            });
        }
    }
}
