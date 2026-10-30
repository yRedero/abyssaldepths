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
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;

/**
 * The body a player leaves behind while they are in their inner self: looks like them (skin, armour and
 * held items, as copies for show), floats a hand's breadth above the ground, can not be hurt, pushed or
 * moved, and vanishes once its owner is back in its world.
 */
public class AbsentBody extends LivingEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(AbsentBody.class, EntityDataSerializers.OPTIONAL_UUID);
    /** How far above the ground it floats. */
    public static final double HOVER = 0.12;

    private final NonNullList<ItemStack> hands = NonNullList.withSize(2, ItemStack.EMPTY);
    private final NonNullList<ItemStack> armour = NonNullList.withSize(4, ItemStack.EMPTY);

    public AbsentBody(EntityType<? extends AbsentBody> type, Level level) {
        super(type, level);
        setInvulnerable(true);
        setNoGravity(true);
    }

    /** Leaves the player's body where they stand. */
    public static void leave(ServerPlayer player) {
        final AbsentBody body = new AbsentBody(AbyssalDepths.ABSENT_BODY.get(), player.level());
        body.entityData.set(OWNER, Optional.of(player.getUUID()));
        body.moveTo(player.getX(), player.getY() + HOVER, player.getZ(), player.getYRot(), 0.0F);
        body.setYHeadRot(player.getYHeadRot());
        body.yBodyRot = player.yBodyRot;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ANIMAL_ARMOR) {
                body.setItemSlot(slot, player.getItemBySlot(slot).copy());
            }
        }
        body.setCustomName(player.getName());
        player.level().addFreshEntity(body);
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
        if (!level().isClientSide) {
            // the owner is back in this world (or gone for good): the body is no longer needed
            final Player owner = owner().map(id -> level().getServer().getPlayerList().getPlayer(id)).orElse(null);
            if (owner().isEmpty() || owner != null && owner.level() == level()) {
                discard();
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurt(source, amount); // only /kill and the void
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
