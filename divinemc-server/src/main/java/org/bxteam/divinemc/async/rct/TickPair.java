package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.list.IteratorSafeOrderedReferenceSet;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

record TickPair(RegionData[] regions, Set<Entity> entities) {
    static TickPair computePlayerRegions(ServerLevel level) {
        List<ServerPlayer> players = new ObjectArrayList<>(level.players());
        final int defaultTickDist = level.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
        final int defaultAmountOfChunks = (2 * defaultTickDist + 1) * (2 * defaultTickDist + 1);
        final int playerCount = players.size();

        Rectangle[] boundaries = new Rectangle[playerCount];
        int[] playerTickDistances = new int[playerCount];

        for (int i = 0; i < playerCount; i++) {
            ServerPlayer player = players.get(i);
            ChunkPos pos = player.chunkPosition();
            int tickDist = player.moonrise$getViewDistanceHolder().getViewDistances().tickViewDistance();
            if (tickDist == -1) tickDist = defaultTickDist;

            playerTickDistances[i] = tickDist;

            boundaries[i] = new Rectangle(
                pos.x() - tickDist, pos.z() - tickDist,
                pos.x() + tickDist, pos.z() + tickDist
            );
        }

        UnionFind uf = new UnionFind(playerCount);
        for (int i = 0; i < playerCount; i++) {
            for (int j = i + 1; j < playerCount; j++) {
                if (boundaries[i].intersects(boundaries[j])) {
                    uf.union(i, j);
                }
            }
        }

        Int2IntOpenHashMap rootToGroup = new Int2IntOpenHashMap(playerCount);
        rootToGroup.defaultReturnValue(-1);
        ObjectArrayList<IntArrayList> groups = new ObjectArrayList<>();

        for (int i = 0; i < playerCount; i++) {
            int root = uf.find(i);
            int groupIdx = rootToGroup.get(root);
            if (groupIdx == -1) {
                groupIdx = groups.size();
                rootToGroup.put(root, groupIdx);
                groups.add(new IntArrayList(1));
            }
            groups.get(groupIdx).add(i);
        }

        ObjectArrayList<RegionData> regions = new ObjectArrayList<>(groups.size());

        int totalEstimatedChunks = 0;

        for (IntArrayList group : groups) {
            if (group.isEmpty()) continue;

            LongOpenHashSet groupChunks = new LongOpenHashSet(defaultAmountOfChunks);

            for (int i = 0; i < group.size(); i++) {
                int playerIdx = group.getInt(i);
                ServerPlayer player = players.get(playerIdx);
                int centerX = player.chunkPosition().x();
                int centerZ = player.chunkPosition().z();
                int dist = playerTickDistances[playerIdx];

                for (int dx = -dist; dx <= dist; dx++) {
                    int x = centerX + dx;
                    for (int dz = -dist; dz <= dist; dz++) {
                        groupChunks.add(CoordinateUtils.getChunkKey(x, centerZ + dz));
                    }
                }
            }

            regions.add(new RegionData(groupChunks, ConcurrentHashMap.newKeySet(100), ConcurrentHashMap.newKeySet(4)));
            totalEstimatedChunks += groupChunks.size();
        }

        Long2IntOpenHashMap chunkToRegion = new Long2IntOpenHashMap(totalEstimatedChunks);
        chunkToRegion.defaultReturnValue(-1);

        for (int idx = 0; idx < regions.size(); idx++) {
            for (long key : regions.get(idx).chunks()) {
                chunkToRegion.put(key, idx);
            }
        }

        final Set<Entity> firstTick = ConcurrentHashMap.newKeySet();

        IteratorSafeOrderedReferenceSet<Entity> entities;
        synchronized (entities = level.entityTickList.entities) {
            entities.createRawIterator();

            try {
                final Entity[] rawList = entities.getListRaw();
                final int limit = entities.getListSize();
                Arrays.stream(rawList, 0, limit)
                    .parallel()
                    .filter(Objects::nonNull)
                    .forEach(entity -> {
                        long chunkKey = entity.chunkPosition().pack();
                        int regionIndex = chunkToRegion.get(chunkKey);
                        if (regionIndex != -1) {
                            RegionData targetRegion = regions.get(regionIndex);
                            targetRegion.entities().add(entity);
                            if (entity instanceof ServerPlayer player) {
                                targetRegion.players().add(player);
                            }
                        } else {
                            firstTick.add(entity);
                        }
                    });
            } finally {
                entities.finishRawIterator();
            }
        }

        regions.sort(Comparator.<RegionData>comparingDouble(r -> {
            boolean seen = false;
            Double best = null;
            for (ServerPlayer p : r.players()) {
                Double orElse = p.avgTickTimeNanos.average().orElse(-1);
                if (!seen || orElse.compareTo(best) > 0) {
                    seen = true;
                    best = orElse;
                }
            }
            return seen ? best : -1d;
        }).reversed());
        return new TickPair(regions.toArray(new RegionData[0]), firstTick);
    }

    record Rectangle(int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(Rectangle other) {
            return !(this.maxX < other.minX ||
                     this.minX > other.maxX ||
                     this.maxZ < other.minZ ||
                     this.minZ > other.maxZ);
        }
    }
}

