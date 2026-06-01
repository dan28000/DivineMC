package org.bxteam.divinemc.async.rct;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.datafixers.DataFixer;
import io.papermc.paper.entity.activation.ActivationRange;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.util.NamedAgnosticThreadFactory;
import org.jetbrains.annotations.NotNull;

public final class RegionizedChunkTicking extends ServerChunkCache {
    public static final Executor REGION_EXECUTOR = Executors.newFixedThreadPool(DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadCount,
        new NamedAgnosticThreadFactory<>("Region Ticking", TickThread::new, DivineConfig.AsyncCategory.regionizedChunkTickingExecutorThreadPriority));
    public final RollingLongBuffer avgTime = new RollingLongBuffer(100);
    private final AvgTimeLogger avgTimeLogger;
    private TickPair currentTick;
    private long chunksTime = 0;
    private int i = 0;

    @SuppressWarnings("unchecked")
    private CompletableFuture<LongOpenHashSet>[] chunkFutures = new CompletableFuture[32];
    private CompletableFuture<?>[] entityFutures = new CompletableFuture[32];
    private final LongOpenHashSet tickedChunkKeys = new LongOpenHashSet(8192);

    public RegionizedChunkTicking(
        ServerLevel level,
        LevelStorageSource.LevelStorageAccess levelStorageAccess,
        DataFixer fixerUpper,
        StructureTemplateManager structureTemplateManager,
        Executor executor,
        ChunkGenerator generator,
        int viewDistance,
        int simulationDistance,
        boolean sync,
        ChunkStatusUpdateListener chunkStatusListener,
        Supplier<SavedDataStorage> overworldDataStorage,
        final SavedDataStorage savedDataStorage
    ) {
        super(level, levelStorageAccess, fixerUpper, structureTemplateManager, executor, generator, viewDistance, simulationDistance, sync, chunkStatusListener, overworldDataStorage, savedDataStorage);
        this.avgTimeLogger = new AvgTimeLogger(level.serverLevelData.getLevelName());
    }

    @Override
    protected void iterateTickingChunksFaster(final @NotNull CompletableFuture<Void> spawns) {
        final long start = System.nanoTime();
        final ServerLevel world = this.level;
        final int randomTickSpeed = world.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        final LevelChunk[] raw = world.moonrise$getEntityTickingChunks().toArray(new LevelChunk[0]);
        currentTick = TickPair.computePlayerRegions(level);
        final RegionData[] regions = currentTick.regions();

        if (chunkFutures.length < regions.length) {
            chunkFutures = new CompletableFuture[Math.max(chunkFutures.length * 2, regions.length)];
        }

        int count = 0;
        for (RegionData region : regions) {
            if (region == null || region.isEmpty()) {
                continue;
            }
            chunkFutures[count++] = tick(region, randomTickSpeed);
        }

        finishTicking(chunkFutures, count, randomTickSpeed, raw);
        spawns.join();
        chunksTime = System.nanoTime() - start;
    }

    private CompletableFuture<LongOpenHashSet> tick(RegionData region, int randomTickSpeed) {
        return CompletableFuture.supplyAsync(() -> {
            final long start = System.nanoTime();
            final LongOpenHashSet regionChunksIDs = new LongOpenHashSet(region.chunks().size());
            for (final long key : region.chunks()) {
                final LevelChunk chunk = fullChunks.get(key);
                if (chunk != null) {
                    level.tickChunk(chunk, randomTickSpeed);
                    regionChunksIDs.add(key);
                }
            }

            final long end = System.nanoTime() - start;
            final int hash = region.hashCode();
            for (ServerPlayer player : region.players()) {
                player.avgTickTimeNanos.tempValue = end;
                player.lastRegionChunkSize = regionChunksIDs.size();
                player.lastRegionEntityAmount = region.entities().size();
                player.regionHash = hash;
            }
            return regionChunksIDs;
        }, REGION_EXECUTOR);
    }

    private void finishTicking(final CompletableFuture<LongOpenHashSet>[] ticked, final int count, final int randomTickSpeed, final LevelChunk[] raw) {
        tickedChunkKeys.clear();
        for (int regionID = 0; regionID < count; regionID++) {
            try {
                LongOpenHashSet result = ticked[regionID].join();
                if (result != null) {
                    tickedChunkKeys.addAll(result);
                }
            } catch (Exception e) {
                LOGGER.error("Exception retrieving region ticking result", e);
            }
        }

        for (LevelChunk chunk : raw) {
            if (!tickedChunkKeys.contains(chunk.coordinateKey)) {
                level.tickChunk(chunk, randomTickSpeed);
            }
        }
    }

    public void tickEntitiesParallel() {
        long start = System.nanoTime();
        final TickPair tickPair = this.currentTick;
        this.currentTick = null;
        if (tickPair == null) return;

        ActivationRange.activateEntities(level); // Paper - EAR

        final RegionData[] regions = tickPair.regions();
        if (entityFutures.length < regions.length) {
            entityFutures = new CompletableFuture[Math.max(entityFutures.length * 2, regions.length)];
        }
        int count = 0;
        for (RegionData region : regions) {
            if (region == null || region.entities().isEmpty()) continue;

            entityFutures[count++] = CompletableFuture.runAsync(() -> {
                final long entityStart = System.nanoTime();
                for (Entity entity : region.entities()) {
                    tickEntity(entity);
                }
                final long end = System.nanoTime() - entityStart;
                for (ServerPlayer player : region.players()) {
                    player.avgTickTimeNanos.add(player.avgTickTimeNanos.tempValue + end);
                }
            }, REGION_EXECUTOR);
        }

        for (int j = 0; j < count; j++) {
            try {
                entityFutures[j].join();
            } catch (Exception ex) {
                LOGGER.error("Error during region entity ticking", ex);
            }
        }

        for (Entity entity : tickPair.entities()) {
            tickEntity(entity);
        }

        long end = System.nanoTime() - start;
        avgTime.add(chunksTime + end);

        if (i++ % 100 == 0 && regions.length > 0) {
            REGION_EXECUTOR.execute(() -> {
                StringBuilder sb = new StringBuilder();
                for (RegionData regionData : tickPair.regions()) {
                    sb.append("Region with ").append(regionData.chunks().size()).append(" chunks and ").append(regionData.entities().size()).append(" entities ticked for Players:\n");
                    for (ServerPlayer player : regionData.players()) {
                        long avgNanos = Math.round(player.avgTickTimeNanos.average().orElse(0));
                        long ms = avgNanos / 1_000_000;
                        long us = (avgNanos % 1_000_000) / 1_000;
                        long ns = avgNanos % 1_000;
                        sb.append("- ").append(player.displayName).append(" avg region tick time: ").append(ms).append(" ms ").append(us).append(" us ").append(ns).append(" ns").append("\n");
                    }
                }
                avgTimeLogger.logTickTime(sb.toString());
            });
        }
    }

    private void tickEntity(Entity entity) {
        entity.activatedPriorityReset = false;
        if (!entity.isRemoved() && !level.tickRateManager().isEntityFrozen(entity)) {
            entity.checkDespawn();
            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                    return;
                }

                entity.stopRiding();
            }

            level.guardEntityTick(level::tickNonPassenger, entity);
        }
    }

    @Override
    public void close() throws IOException {
        avgTimeLogger.close();
        super.close();
    }
}
