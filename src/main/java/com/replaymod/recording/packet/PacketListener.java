package com.replaymod.recording.packet;

import static com.replaymod.core.versions.MCVer.getMinecraft;
import static com.replaymod.replaystudio.util.Utils.writeInt;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.network.chat.TextComponent;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.steveice10.netty.buffer.PooledByteBufAllocator;
import com.github.steveice10.packetlib.tcp.io.ByteBufNetOutput;
import com.google.gson.Gson;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.utils.Restrictions;
import com.replaymod.core.utils.Utils;
import com.replaymod.core.versions.MCVer;
import com.replaymod.editor.gui.MarkerProcessor;
import com.replaymod.gui.container.VanillaGuiScreen;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.Setting;
import com.replaymod.recording.gui.GuiSavingReplay;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.io.ReplayOutputStream;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundResourcePackPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.world.entity.Entity;

//#if MC>=11904
//$$ import net.minecraft.network.PacketBundleHandler;
//$$ import java.util.ArrayList;
//#endif

@ChannelHandler.Sharable // so we can re-order it
public class PacketListener extends ChannelInboundHandlerAdapter {

    public static final String RAW_RECORDER_KEY = "replay_recorder_raw";
    public static final String DECODED_RECORDER_KEY = "replay_recorder_decoded";

    public static final String DECOMPRESS_KEY = "decompress";
    public static final String DECODER_KEY = "decoder";

    private static final Minecraft mc = getMinecraft();
    private static final Logger logger = LogManager.getLogger();

    //#if MC>=11700
    //$$ private static final int PACKET_ID_RESOURCE_PACK_SEND = getPacketId(NetworkState.PLAY, new ResourcePackSendS2CPacket("", "", false, null));
    //$$ private static final int PACKET_ID_LOGIN_COMPRESSION = getPacketId(NetworkState.LOGIN, new LoginCompressionS2CPacket(0));
    //#else
    private static final int PACKET_ID_RESOURCE_PACK_SEND = getPacketId(ConnectionProtocol.PLAY, new ClientboundResourcePackPacket("", "", false, null));
    private static final int PACKET_ID_LOGIN_COMPRESSION = getPacketId(ConnectionProtocol.LOGIN, new ClientboundLoginCompressionPacket(0));
    //#endif
    //#if MC<10904
    //$$ private static final int PACKET_ID_PLAY_COMPRESSION = getPacketId(EnumConnectionState.PLAY, new S46PacketSetCompressionLevel());
    //#endif

    private final ReplayMod core;
    private final Path outputPath;
    private final ReplayFile replayFile;

    private final ResourcePackRecorder resourcePackRecorder;

    private final ExecutorService saveService = Executors.newSingleThreadExecutor();
    private final ReplayOutputStream packetOutputStream;

    private ReplayMetaData metaData;

    private ChannelHandlerContext context = null;

    private final long startTime;
    private long lastSentPacket;
    private long timePassedWhilePaused;
    private volatile boolean serverWasPaused;

    /**
     * Used to keep track of the last metadata save job submitted to the save service and
     * as such prevents unnecessary writes.
     */
    private final AtomicInteger lastSaveMetaDataId = new AtomicInteger();

    public PacketListener(ReplayMod core, Path outputPath, ReplayFile replayFile, ReplayMetaData metaData) throws IOException {
        this.core = core;
        this.outputPath = outputPath;
        this.replayFile = replayFile;
        this.metaData = metaData;
        this.resourcePackRecorder = new ResourcePackRecorder(replayFile);
        this.packetOutputStream = replayFile.writePacketData();
        this.startTime = metaData.getDate();

        saveMetaData();
    }

    private void saveMetaData() {
        int id = lastSaveMetaDataId.incrementAndGet();
        saveService.submit(() -> {
            if (lastSaveMetaDataId.get() != id) {
                return; // Another job has been scheduled, it will do the hard work.
            }
            try {
                synchronized (replayFile) {
                    if (ReplayMod.isMinimalMode()) {
                        metaData.setFileFormat("MCPR");
                        metaData.setFileFormatVersion(ReplayMetaData.CURRENT_FILE_FORMAT_VERSION);
                        metaData.setProtocolVersion(MCVer.getProtocolVersion());
                        metaData.setGenerator("ReplayMod in Minimal Mode");

                        try (OutputStream out = replayFile.write("metaData.json")) {
                            String json = (new Gson()).toJson(metaData);
                            out.write(json.getBytes());
                        }
                    } else {
                        replayFile.writeMetaData(MCVer.getPacketTypeRegistry(true), metaData);
                    }
                }
            } catch (IOException e) {
                logger.error("Writing metadata:", e);
            }
        });
    }

    public void save(net.minecraft.network.protocol.Packet packet) {
        Packet encoded;
        try {
            encoded = encodeMcPacket(getConnectionState(), packet);
        } catch (Exception e) {
            logger.error("Encoding packet:", e);
            return;
        }
        save(encoded);
    }

    public void save(Packet packet) {
        if (!mc.isSameThread()) {
            mc.tell(() -> save(packet));
            return;
        }
        try {
            //#if MC>=11800
            if (packet.getRegistry().getState() == State.LOGIN && packet.getId() == PACKET_ID_LOGIN_COMPRESSION) {
                return; // Replay data is never compressed on the packet level
            }

            long now = System.currentTimeMillis();
            if (serverWasPaused) {
                timePassedWhilePaused = now - startTime - lastSentPacket;
                serverWasPaused = false;
            }
            int timestamp = (int) (now - startTime - timePassedWhilePaused);
            lastSentPacket = timestamp;
            PacketData packetData = new PacketData(timestamp, packet);
            saveService.submit(() -> {
                try {
                    if (ReplayMod.isMinimalMode()) {
                        // Minimal mode, ReplayStudio might not know our packet ids, so we cannot use it
                        com.github.steveice10.netty.buffer.ByteBuf packetIdBuf = PooledByteBufAllocator.DEFAULT.buffer();
                        com.github.steveice10.netty.buffer.ByteBuf packetBuf = packetData.getPacket().getBuf();
                        try {
                            new ByteBufNetOutput(packetIdBuf).writeVarInt(packetData.getPacket().getId());

                            int packetIdLen = packetIdBuf.readableBytes();
                            int packetBufLen = packetBuf.readableBytes();
                            writeInt(packetOutputStream, (int) packetData.getTime());
                            writeInt(packetOutputStream, packetIdLen + packetBufLen);
                            packetIdBuf.readBytes(packetOutputStream, packetIdLen);
                            packetBuf.getBytes(packetBuf.readerIndex(), packetOutputStream, packetBufLen);
                        } finally {
                            packetIdBuf.release();
                            packetBuf.release();
                        }
                    } else {
                        packetOutputStream.write(packetData);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch(Exception e) {
            logger.error("Writing packet:", e);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);

        if (ctx.pipeline().get(DECODED_RECORDER_KEY) == null) {
            if (ctx.pipeline().get(PacketListener.DECODER_KEY) != null) {
                // Regular channel, we'll inject our decoded recorder directly after the decoder
                ctx.pipeline().addAfter(DECODER_KEY, DECODED_RECORDER_KEY, new DecodedPacketListener());
            } else {
                // Integrated server passes packets directly, there's no splitting, decompression or decoding
                // The decoded packet handler can just go directly behind this hand
                ctx.pipeline().addAfter(RAW_RECORDER_KEY, DECODED_RECORDER_KEY, new DecodedPacketListener());
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        metaData.setDuration((int) lastSentPacket);
        saveMetaData();

        core.runLater(() -> {
            ConnectionEventHandler connectionEventHandler = ReplayModRecording.instance.getConnectionEventHandler();
            if (connectionEventHandler.getPacketListener() == this) {
                connectionEventHandler.reset();
            }
        });

        GuiSavingReplay guiSavingReplay = new GuiSavingReplay(core);
        new Thread(() -> {
            core.runLater(guiSavingReplay::open);

            saveService.shutdown();
            try {
                saveService.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Waiting for save service termination:", e);
            }
            try {
                packetOutputStream.close();
            } catch (IOException e) {
                logger.error("Failed to close packet output stream:", e);
            }

            List<Pair<Path, ReplayMetaData>> outputPaths;
            synchronized (replayFile) {
                try {
                    if (!MarkerProcessor.producesAnyOutput(replayFile)) {
                        // Immediately close the saving popup, the user doesn't care about it
                        core.runLater(guiSavingReplay::close);

                        // If we crash right here, on the next start we'll prompt the user for recovery
                        // but we don't really want that, so drop a marker file to skip recovery for this replay.
                        Path noRecoverMarker = outputPath.resolveSibling(outputPath.getFileName() + ".no_recover");
                        Files.createFile(noRecoverMarker);

                        // We still have the replay, so we just save it (at least for a few weeks) in case they change their mind
                        String replayName = FilenameUtils.getBaseName(outputPath.getFileName().toString());
                        Path rawFolder = ReplayMod.instance.getRawReplayFolder();
                        Path rawPath = rawFolder.resolve(outputPath.getFileName());
                        for (int i = 1; Files.exists(rawPath); i++) {
                            rawPath = rawPath.resolveSibling(replayName + "." + i + ".mcpr");
                        }
                        replayFile.saveTo(rawPath.toFile());
                        replayFile.close();

                        // Done, clean up the marker
                        Files.delete(noRecoverMarker);
                        return;
                    }

                    replayFile.save();
                    replayFile.close();

                    if (core.getSettingsRegistry().get(Setting.AUTO_POST_PROCESS) && !ReplayMod.isMinimalMode()) {
                        outputPaths = MarkerProcessor.apply(outputPath, guiSavingReplay.getProgressBar()::setProgress);
                    } else {
                        outputPaths = Collections.singletonList(Pair.of(outputPath, metaData));
                    }
                } catch (Exception e) {
                    logger.error("Saving replay file:", e);
                    CrashReport crashReport = CrashReport.forThrowable(e, "Saving replay file");
                    core.runLater(() -> Utils.error(logger, VanillaGuiScreen.wrap(mc.screen), crashReport, guiSavingReplay::close));
                    return;
                }
            }

            core.runLater(() -> guiSavingReplay.presentRenameDialog(outputPaths));
        }).start();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(ctx == null) {
            if(context == null) {
                return;
            } else {
                ctx = context;
            }
        }
        this.context = ctx;

        ConnectionProtocol connectionState = getConnectionState();

        Packet packet = null;
        if (msg instanceof ByteBuf) {
            // for regular connections, we're expecting to observe `ByteBuf`s here
            ByteBuf buf = (ByteBuf) msg;
            if (buf.readableBytes() > 0) {
                packet = decodePacket(connectionState, buf);
            }
        } else if (msg instanceof net.minecraft.network.protocol.Packet) {
            packet = encodeMcPacket(connectionState, (net.minecraft.network.protocol.Packet) msg);
        }

        if (packet != null) {
            if (connectionState == ConnectionProtocol.PLAY && packet.getId() == PACKET_ID_RESOURCE_PACK_SEND) {
            	Connection connection = ctx.pipeline().get(Connection.class);
                save(resourcePackRecorder.handleResourcePack(connection, (ClientboundResourcePackPacket) decodeMcPacket(packet)));
                return;
            }

            save(packet);
        }

        super.channelRead(ctx, msg);
    }

    private ConnectionProtocol getConnectionState() {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return ConnectionProtocol.LOGIN;
        }
        AttributeKey<ConnectionProtocol> key = Connection.ATTRIBUTE_PROTOCOL;
        return ctx.channel().attr(key).get();
    }

    private static Packet encodeMcPacket(ConnectionProtocol connectionState, net.minecraft.network.protocol.Packet packet) throws Exception {
        //#if MC>=10800
        Integer packetId = connectionState.getPacketId(PacketFlow.CLIENTBOUND, packet);
        //#else
        //$$ Integer packetId = (Integer) connectionState.func_150755_b().inverse().get(packet.getClass());
        //#endif
        if (packetId == null) {
            throw new IOException("Unknown packet type:" + packet.getClass());
        }
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            packet.write(new FriendlyByteBuf(byteBuf));
            return new Packet(
                    MCVer.getPacketTypeRegistry(connectionState == ConnectionProtocol.LOGIN),
                    packetId,
                    com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(
                            byteBuf.array(),
                            byteBuf.arrayOffset(),
                            byteBuf.readableBytes()
                    )
            );
        } finally {
            byteBuf.release();
        }
    }

    private static net.minecraft.network.protocol.Packet decodeMcPacket(Packet packet) throws IOException, IllegalAccessException, InstantiationException {
    	ConnectionProtocol connectionState = packet.getRegistry().getState() == State.LOGIN ? ConnectionProtocol.LOGIN : ConnectionProtocol.PLAY;
        int packetId = packet.getId();
        FriendlyByteBuf packetBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.getBuf().nioBuffer()));
        return connectionState.createPacket(PacketFlow.CLIENTBOUND, packetId, packetBuf);
    }

    private static Packet decodePacket(ConnectionProtocol connectionState, ByteBuf buf) {
        FriendlyByteBuf packetBuf = new FriendlyByteBuf(buf.slice());
        int packetId = packetBuf.readVarInt();
        byte[] bytes = new byte[packetBuf.readableBytes()];
        packetBuf.readBytes(bytes);
        return new Packet(
                MCVer.getPacketTypeRegistry(connectionState == ConnectionProtocol.LOGIN),
                packetId,
                com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(bytes)
        );
    }

    private static int getPacketId(ConnectionProtocol networkState, net.minecraft.network.protocol.Packet packet) {
        try {
            return requireNonNull(networkState.getPacketId(PacketFlow.CLIENTBOUND, packet));
        } catch (Exception e) {
            throw new RuntimeException("Failed to determine packet id for " + packet.getClass(), e);
        }
    }

    public void addMarker(String name) {
        addMarker(name, (int) getCurrentDuration());
    }

    public void addMarker(String name, int timestamp) {
        Entity view = mc.getCameraEntity();

        Marker marker = new Marker();
        marker.setName(name);
        marker.setTime(timestamp);
        if (view != null) {
            marker.setX(view.getX());
            marker.setY(view.getY());
            marker.setZ(view.getZ());
            marker.setYaw(view.getYRot());
            marker.setPitch(view.getXRot());
        }
        // Roll is always 0
        saveService.submit(() -> {
            synchronized (replayFile) {
                try {
                    Set<Marker> markers = replayFile.getMarkers().or(HashSet::new);
                    markers.add(marker);
                    replayFile.writeMarkers(markers);
                } catch (IOException e) {
                    logger.error("Writing markers:", e);
                }
            }
        });
    }

    public long getCurrentDuration() {
        return lastSentPacket;
    }

    public void setServerWasPaused() {
        this.serverWasPaused = true;
    }

    private class DecodedPacketListener extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			
            if (msg instanceof ClientboundCustomPayloadPacket) {
            	ClientboundCustomPayloadPacket packet = (ClientboundCustomPayloadPacket) msg;
                if (Restrictions.PLUGIN_CHANNEL.equals(packet.getName())) {
                    save(new ClientboundDisconnectPacket(new TextComponent("Please update to view this replay.")));
                }
            }

            if (msg instanceof ClientboundAddPlayerPacket) {
                //#if MC>=10800
                UUID uuid = ((ClientboundAddPlayerPacket) msg).getPlayerId();
                //#else
                //$$ UUID uuid = ((S0CPacketSpawnPlayer) msg).func_148948_e().getId();
                //#endif
                Set<String> uuids = new HashSet<>(Arrays.asList(metaData.getPlayers()));
                uuids.add(uuid.toString());
                metaData.setPlayers(uuids.toArray(new String[uuids.size()]));
                saveMetaData();
            }

            super.channelRead(ctx, msg);
        }
    }
}