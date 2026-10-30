package redero.abyssaldepths.entity;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The body a player leaves behind while they are in their inner self: looks like them (skin, armour and
 * held items, as copies for show), floats a hand's breadth above the ground, can not be hurt, pushed or
 * moved, and vanishes once its owner is back in its world. It starts looking where the player looked and
 * then turns its head straight ahead.
 */
public class AbsentBody extends LivingEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(AbsentBody.class, EntityDataSerializers.OPTIONAL_UUID);
    /** How far above the ground it floats. */
    public static final double HOVER = 0.12;
    /** Share of the way the head turns towards straight ahead every tick. */
    private static final float HEAD_EASE = 0.12F;

    private final NonNullList<ItemStack> hands = NonNullList.withSize(2, ItemStack.EMPTY);
    private final NonNullList<ItemStack> armour = NonNullList.withSize(4, ItemStack.EMPTY);

    public AbsentBody(EntityType<? extends AbsentBody> type, Level level) {
        super(type, level);
        setInvulnerable(true);
        setNoGravity(true);
    }

    /** Leaves the player's body where they stand. */
    public static void leave(ServerPlayer player) {
        leave(player, player.serverLevel(), player.getX(), player.getY(), player.getZ(), player.yBodyRot, player.getXRot(), player.getYHeadRot());
    }

    /**
     * Leaves the player's body at a place (also when they log in while in their inner self: it went away
     * when they logged out), facing yaw, the head first where they looked.
     */
    public static void leave(ServerPlayer player, net.minecraft.server.level.ServerLevel level, double x, double y, double z, float yaw, float pitch, float headYaw) {
        final AbsentBody body = new AbsentBody(AbyssalDepths.ABSENT_BODY.get(), level);
        body.dressAs(player);
        // facing where the player's body faced, the head still where they looked: it turns straight in tick()
        body.moveTo(x, y + HOVER, z, yaw, pitch);
        body.setYHeadRot(headYaw);
        body.yBodyRot = body.yBodyRotO = yaw;
        level.addFreshEntity(body);
    }

    public Optional<UUID> owner() {
        return entityData.get(OWNER);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        // the head eases from where the player looked to straight ahead, over about a second: only on the
        // clients (the server keeps the first look, so it sends no turns that would jerk it), drawn smoothly
        // between ticks from the previous values
        final float facing = getYRot();
        yBodyRot = yBodyRotO = facing;
        if (level().isClientSide) {
            setYHeadRot(getYHeadRot() + Mth.wrapDegrees(facing - getYHeadRot()) * HEAD_EASE);
            setXRot(getXRot() * (1.0F - HEAD_EASE));
        }
        if (!level().isClientSide) {
            // the owner is back in this world, logged out, or gone for good: the body is no longer needed (it
            // is left again when they go into the inner self anew)
            final Player owner = owner().map(id -> level().getServer().getPlayerList().getPlayer(id)).orElse(null);
            if (shouldVanish(owner)) {
                discard();
            }
        }
    }

    /** The server's word on the head is ignored: it is turned here (see {@link #tick}). */
    @Override
    public void lerpHeadTo(float yaw, int steps) {
    }

    /** It does not move; the server's rotations would undo the turning head. */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        super.lerpTo(x, y, z, getYRot(), getXRot(), steps);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return vulnerableTo(source) && super.hurt(source, amount);
    }

    /** What can hurt it: only /kill and the void. */
    protected boolean vulnerableTo(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /** Whether it is no longer needed, with its owner online (or null): back in its world, or gone. */
    protected boolean shouldVanish(Player owner) {
        return owner == null || owner.level() == level();
    }

    /** Whether it floats with a faint bob (drawn so). */
    public boolean bobs() {
        return true;
    }

    /** Makes it look like the player: their equipment (copies, for show), name and owner. */
    protected void dressAs(Player player) {
        entityData.set(OWNER, Optional.of(player.getUUID()));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ANIMAL_ARMOR) {
                setItemSlot(slot, player.getItemBySlot(slot).copy());
            }
        }
        setCustomName(player.getName());
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armour;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return switch (slot.getType()) {
            case HAND -> hands.get(slot.getIndex());
            case HUMANOID_ARMOR -> armour.get(slot.getIndex());
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        switch (slot.getType()) {
            case HAND -> hands.set(slot.getIndex(), stack);
            case HUMANOID_ARMOR -> armour.set(slot.getIndex(), stack);
            default -> {
            }
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        owner().ifPresent(id -> tag.putUUID("Owner", id));
        tag.put("Hands", saveItems(hands));
        tag.put("Armour", saveItems(armour));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(OWNER, tag.hasUUID("Owner") ? Optional.of(tag.getUUID("Owner")) : Optional.empty());
        loadItems(tag.getList("Hands", 10), hands);
        loadItems(tag.getList("Armour", 10), armour);
    }

    private net.minecraft.nbt.ListTag saveItems(NonNullList<ItemStack> items) {
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : items) {
            list.add(stack.isEmpty() ? new CompoundTag() : stack.save(registryAccess()));
        }
        return list;
    }

    private void loadItems(net.minecraft.nbt.ListTag list, NonNullList<ItemStack> items) {
        for (int i = 0; i < items.size() && i < list.size(); i++) {
            items.set(i, ItemStack.parseOptional(registryAccess(), list.getCompound(i)));
        }
    }
}
