package org.teacon.eldbook;

import net.minecraft.Util;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.maven.artifact.versioning.ArtifactVersion;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

@Mod(EntityLifeDeathBook.ID)
public final class EntityLifeDeathBook {
    public static final String ID = "eldbook";
    public static final boolean ENABLED = !Boolean.parseBoolean(System.getenv("ELDBOOK_BYPASS"));

    private static final Path dir = Path.of("eldbook");
    private static final ConcurrentMap<LocalDateTime, BufferedWriter> writers = new ConcurrentHashMap<>();

    private final ArtifactVersion version;

    public EntityLifeDeathBook(ModContainer container, IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, this::on);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, this::on);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, this::on);
        modBus.addListener(FMLCommonSetupEvent.class, this::on);
        this.version = container.getModInfo().getVersion();
    }

    private void on(ServerAboutToStartEvent event) {
        // noinspection resource
        writers.computeIfAbsent(LocalDateTime.now().truncatedTo(ChronoUnit.HOURS), EntityLifeDeathBook::create);
    }

    private void on(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 19) {
            var now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            writers.entrySet().removeIf(entry -> {
                var entryTime = entry.getKey();
                final var logName = name(entryTime);
                try {
                    if (entryTime.isBefore(now)) {
                        entry.getValue().close();
                        gzip(entryTime);
                        return true;
                    } else {
                        entry.getValue().flush();
                        return false;
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to flush log file: " + logName + ".log", e);
                }
            });
        }
    }

    private void on(ServerStoppedEvent event) {
        var now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        writers.entrySet().removeIf(entry -> {
            var entryTime = entry.getKey();
            final var logName = name(entryTime);
            try {
                if (entryTime.isBefore(now)) {
                    entry.getValue().close();
                    gzip(entryTime);
                    return true;
                } else {
                    entry.getValue().close();
                    return true;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush log file: " + logName + ".log", e);
            }
        });
    }

    private void on(FMLCommonSetupEvent event) {
        try {
            Files.createDirectories(dir);
            LogManager.getLogger().info("EntityLifeDeathBook Version: {}", this.version);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String name(LocalDateTime entryTime) {
        return entryTime.toLocalDate() + "T" + entryTime.getHour() / 10 + entryTime.getHour() % 10;
    }

    private static BufferedWriter create(LocalDateTime time) {
        final var logName = name(time);
        try {
            return Files.newBufferedWriter(dir.resolve(logName + ".log"), UTF_8, CREATE, APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open log file: " + logName + ".log", e);
        }
    }

    private static void gzip(LocalDateTime time) {
        // noinspection resource
        Util.ioPool().execute(() -> {
            var logName = name(time);
            try {
                try (var in = Files.newInputStream(dir.resolve(logName + ".log"))) {
                    var tempGZipFile = Files.createTempFile(dir, logName, ".log.gz");
                    try (var out = new GZIPOutputStream(Files.newOutputStream(tempGZipFile))) {
                        in.transferTo(out);
                    }
                    try {
                        Files.move(tempGZipFile, dir.resolve(logName + ".log.gz"), ATOMIC_MOVE, REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tempGZipFile, dir.resolve(logName + ".log.gz"), REPLACE_EXISTING);
                    }
                }
                Files.delete(dir.resolve(logName + ".log"));
            } catch (IOException e) {
                throw new RuntimeException("Failed to gzip log file: " + logName + ".log", e);
            }
        });
    }

    @SuppressWarnings("deprecation")
    public static void log(OffsetDateTime time, Type type, UUID uuid,
                           EntityType<?> entity, ResourceKey<Level> dim, Vec3 pos, String[] stacktrace) {
        try {
            var sb = new StringBuilder();
            sb.append("time=").append(time.truncatedTo(ChronoUnit.MILLIS));
            sb.append(" type=").append(type);
            sb.append(" entity=").append(BuiltInRegistries.ENTITY_TYPE.getKey(entity));
            sb.append(" uuid=").append(uuid);
            sb.append(" dimension=").append(dim.location());
            var sectionX = Mth.floor(pos.x) >> 4;
            var sectionY = Mth.floor(pos.y) >> 4;
            var sectionZ = Mth.floor(pos.z) >> 4;
            sb.append(" section.x=").append(sectionX);
            sb.append(" section.y=").append(sectionY);
            sb.append(" section.z=").append(sectionZ);
            sb.append(" offset.x=").append(String.format("%.6f", pos.x - (sectionX << 4)));
            sb.append(" offset.y=").append(String.format("%.6f", pos.y - (sectionY << 4)));
            sb.append(" offset.z=").append(String.format("%.6f", pos.z - (sectionZ << 4)));
            try (var sbw = new StringBuilderWriter(sb)) {
                sb.append(" stacktrace=\"");
                for (int i = 2; i < stacktrace.length; ++i) {
                    var elem = stacktrace[i];
                    StringEscapeUtils.ESCAPE_ECMASCRIPT.translate(elem, sbw);
                    sbw.append("\\n");
                }
                sb.append("\"\n");
            }
            var key = time.toLocalDateTime().truncatedTo(ChronoUnit.HOURS);
            // noinspection resource
            writers.computeIfAbsent(key, EntityLifeDeathBook::create).write(sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write log file: " + time.toLocalDate() + ".log", e);
        }
    }

    public static final class WrappedCallback implements EntityInLevelCallback {
        private Vec3 pos;
        private long section;
        private final Entity entity;
        private final EntityInLevelCallback parent;

        public WrappedCallback(Entity entity, EntityInLevelCallback parent) {
            this.entity = entity;
            this.parent = parent;
            this.pos = entity.position();
            this.section = SectionPos.of(this.pos).asLong();
        }

        @Override
        public void onMove() {
            this.parent.onMove();
            var pos = this.entity.position();
            var section = SectionPos.of(pos).asLong();
            if (section != this.section) {
                var time = OffsetDateTime.now();
                var uuid = this.entity.getUUID();
                // noinspection resource
                var dim = this.entity.level().dimension();
                var stacktrace = ExceptionUtils.getStackFrames(new Throwable());
                EntityLifeDeathBook.log(time, Type.LEAVE, uuid, this.entity.getType(), dim, this.pos, stacktrace);
                EntityLifeDeathBook.log(time, Type.ENTER, uuid, this.entity.getType(), dim, pos, stacktrace);
                this.section = section;
            }
            this.pos = pos;
        }

        @Override
        public void onRemove(@Nonnull Entity.RemovalReason reason) {
            var entity = this.entity;
            this.parent.onRemove(reason);
            var time = OffsetDateTime.now();
            // noinspection resource
            var dim = entity.level().dimension();
            var type = reason.shouldSave() ? Type.UNLOAD : Type.DROP;
            var stacktrace = ExceptionUtils.getStackFrames(new Throwable());
            EntityLifeDeathBook.log(time, type, entity.getUUID(), entity.getType(), dim, entity.position(), stacktrace);
        }
    }

    public enum Type {
        CREATE, DROP, LOAD, UNLOAD, ENTER, LEAVE
    }
}
