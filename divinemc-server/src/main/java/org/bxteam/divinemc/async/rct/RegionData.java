package org.bxteam.divinemc.async.rct;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Set;

record RegionData(LongOpenHashSet chunks, Set<Entity> entities, Set<ServerPlayer> players) {
    public boolean isEmpty() {
        return chunks.isEmpty();
    }
}
