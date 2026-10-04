package com.lootdust.cooldownedvaults;

import java.util.Map;
import java.util.UUID;

public interface VaultBlockEntityMixinAccessor {
    Map<UUID, Long> cooldownedvaults$getPlayerCooldownEndsAt();
}
