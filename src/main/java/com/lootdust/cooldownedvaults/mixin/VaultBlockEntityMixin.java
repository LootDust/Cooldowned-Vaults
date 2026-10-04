package com.lootdust.cooldownedvaults.mixin;

import com.lootdust.cooldownedvaults.VaultBlockEntityMixinAccessor;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.vault.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(VaultBlockEntity.class)
public class VaultBlockEntityMixin implements VaultBlockEntityMixinAccessor {
    @Unique
    private final Map<UUID, Long> playerCooldownEndsAt = new HashMap<>(128);

    @Override
    public Map<UUID, Long> cooldownedvaults$getPlayerCooldownEndsAt() {
        return this.playerCooldownEndsAt;
    }

    public record PlayerCooldowns(UUID player, long cooldownEndsAt) {
        public static final Codec<PlayerCooldowns> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(PlayerCooldowns::player),
                Codec.LONG.fieldOf("cooldownEndsAt").forGetter(PlayerCooldowns::cooldownEndsAt)
            ).apply(instance, PlayerCooldowns::new));
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void saveAdditional(ValueOutput output, CallbackInfo ci) {
        var cooldowns = output.child("server_data").list("player_cooldowns", PlayerCooldowns.CODEC);
        playerCooldownEndsAt.forEach((uuid, cooldown) -> {
            cooldowns.add(new PlayerCooldowns(uuid, cooldown));
        });
    }

    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void loadAdditional(ValueInput input, CallbackInfo ci) {
        var data = input.child("server_data");
        if (data.isEmpty()) return;
        var list = data.get().list("player_cooldowns", PlayerCooldowns.CODEC);
        if (list.isEmpty()) return;
        var cooldowns = list.get();
        playerCooldownEndsAt.clear();
        cooldowns.stream().forEach(playerCooldowns -> playerCooldownEndsAt.put(playerCooldowns.player, playerCooldowns.cooldownEndsAt));
    }

    @Mixin(VaultBlockEntity.Server.class)
    public static class ServerMixin {
        @Shadow
        private static void unlock(final ServerLevel serverLevel, final BlockState blockState, final BlockPos pos, final VaultConfig config, final VaultServerData serverData, final VaultSharedData sharedData, final List<ItemStack> itemsToEject) {}

        @Shadow
        private static List<ItemStack> resolveItemsToEject(final ServerLevel serverLevel, final VaultConfig config, final BlockPos pos, final Player player, final ItemInstance insertedStack) {
            return null;
        }

        @Shadow
        private static boolean canEjectReward(final VaultConfig config, final VaultState vaultState) {
            return true;
        }

        @Shadow
        private static boolean isValidToInsert(VaultConfig config, ItemStack stackToInsert) {
            return true;
        }

        @Shadow
        private static void playInsertFailSound(final ServerLevel serverLevel, final VaultServerData serverData, final BlockPos pos, final SoundEvent sound) {}

        @Inject(method = "tick", at = @At("TAIL"))
        private static void tick(ServerLevel serverLevel, BlockPos pos, BlockState blockState, VaultConfig config, VaultServerData serverData, VaultSharedData sharedData, CallbackInfo ci) {
            BlockEntity entity = serverLevel.getBlockEntity(pos);
            if (!(entity instanceof VaultBlockEntity)) return;
            var cooldowns = ((VaultBlockEntityMixinAccessor)entity).cooldownedvaults$getPlayerCooldownEndsAt();
            long now = serverLevel.getGameTime();
            cooldowns.forEach(((uuid, cooldown) -> {
                if (cooldown == now) serverData.rewardedPlayers.remove(uuid);
            }));
        }

        /**
         * @author LootDust
         * @reason Redirect rewarded players check to modded one
         */
        @Overwrite()
        public static void tryInsertKey(ServerLevel serverLevel, BlockPos pos, BlockState blockState, VaultConfig config, VaultServerData serverData, VaultSharedData sharedData, Player player, ItemStack stackToInsert) {
            VaultState vaultState = (VaultState)blockState.getValue(VaultBlock.STATE);
            BlockEntity entity = serverLevel.getBlockEntity(pos);
            if (!(entity instanceof VaultBlockEntity)) return;
            var cooldowns = ((VaultBlockEntityMixinAccessor)entity).cooldownedvaults$getPlayerCooldownEndsAt();
            long now = serverLevel.getGameTime();
            Long endsAt = cooldowns.get(player.getUUID());
            boolean onCooldown = endsAt != null && now < endsAt;
            if (canEjectReward(config, vaultState)) {
                if (!isValidToInsert(config, stackToInsert)) {
                    playInsertFailSound(serverLevel, serverData, pos, SoundEvents.VAULT_INSERT_ITEM_FAIL);
                } else if (onCooldown) {
                    playInsertFailSound(serverLevel, serverData, pos, SoundEvents.VAULT_REJECT_REWARDED_PLAYER);
                } else {
                    List<ItemStack> itemsToEject = resolveItemsToEject(serverLevel, config, pos, player, stackToInsert);
                    if (!itemsToEject.isEmpty()) {
                        player.awardStat(Stats.ITEM_USED.get(stackToInsert.getItem()));
                        stackToInsert.consume(config.keyItem().getCount(), player);
                        unlock(serverLevel, blockState, pos, config, serverData, sharedData, itemsToEject);
                        serverData.addToRewardedPlayers(player);
                        sharedData.updateConnectedPlayersWithinRange(serverLevel, pos, serverData, config, config.deactivationRange());
                        long cooldownTicks = (long) serverLevel.tickRateManager().tickrate() * 30 * 60;
                        cooldowns.put(player.getUUID(), now + cooldownTicks);
                    }
                }
            }
        }
    }
}
