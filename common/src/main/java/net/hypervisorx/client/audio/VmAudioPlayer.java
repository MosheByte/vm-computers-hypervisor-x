package net.hypervisorx.client.audio;

import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.network.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Plays VM audio as positional OpenAL streams at the computer (or its speakers), so the sound
 * actually comes from the machine in the room. Laptop audio follows the player. Runs on the
 * render thread using Minecraft's own OpenAL context.
 */
public final class VmAudioPlayer {
    private static final int RATE = 22050;
    private static final int MAX_QUEUED = 10;
    private static final Map<UUID, Stream> STREAMS = new ConcurrentHashMap<>();

    private static final class Stream {
        final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
        final ArrayDeque<Integer> freeBuffers = new ArrayDeque<>();
        volatile BlockPos origin;
        volatile long lastPacket = System.currentTimeMillis();
        int source;
        int queued;
    }

    private VmAudioPlayer() {
    }

    public static void onAudio(S2C.Audio packet) {
        if (!HvxClientConfig.get().audioEnabled) return;
        Stream s = STREAMS.computeIfAbsent(packet.vm(), k -> new Stream());
        s.origin = packet.origin();
        s.lastPacket = System.currentTimeMillis();
        s.pending.add(packet.pcm());
        while (s.pending.size() > MAX_QUEUED * 2) s.pending.poll(); // toss the backlog so latency stays low
    }

    /** Render thread, once a frame. */
    public static void tick() {
        long now = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.BLOCKS);
        float gain = Math.max(0f, HvxClientConfig.get().audioVolume * master);
        for (Iterator<Map.Entry<UUID, Stream>> it = STREAMS.entrySet().iterator(); it.hasNext(); ) {
            Stream s = it.next().getValue();
            if (now - s.lastPacket > 5000) {
                destroy(s);
                it.remove();
                continue;
            }
            try {
                pump(s, gain);
            } catch (RuntimeException e) {
                destroy(s);
                it.remove();
            }
        }
    }

    private static void pump(Stream s, float gain) {
        if (s.source == 0 || !AL10.alIsSource(s.source)) {
            s.source = AL10.alGenSources();
            s.queued = 0;
            s.freeBuffers.clear();
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                s.source = 0;
                return;
            }
            AL10.alSourcef(s.source, AL10.AL_ROLLOFF_FACTOR, 1f);
            AL10.alSourcef(s.source, AL10.AL_REFERENCE_DISTANCE, 0f);
        }
        BlockPos o = s.origin;
        if (o != null) {
            AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(s.source, AL10.AL_POSITION, o.getX() + 0.5f, o.getY() + 0.5f, o.getZ() + 0.5f);
            AL10.alSourcef(s.source, AL10.AL_MAX_DISTANCE, 32f);
        } else {
            AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s.source, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcef(s.source, AL10.AL_MAX_DISTANCE, 1000f);
        }
        AL10.alSourcef(s.source, AL10.AL_GAIN, gain);

        int processed = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            int b = AL10.alSourceUnqueueBuffers(s.source);
            s.freeBuffers.add(b);
            s.queued--;
        }
        byte[] chunk;
        while (s.queued < MAX_QUEUED && (chunk = s.pending.poll()) != null) {
            if (chunk.length < 2) continue;
            int buffer = s.freeBuffers.isEmpty() ? AL10.alGenBuffers() : s.freeBuffers.poll();
            ByteBuffer data = MemoryUtil.memAlloc(chunk.length & ~1);
            try {
                data.put(chunk, 0, chunk.length & ~1).flip();
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, RATE);
            } finally {
                MemoryUtil.memFree(data);
            }
            AL10.alSourceQueueBuffers(s.source, buffer);
            s.queued++;
        }
        int state = AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING && s.queued >= 2) AL10.alSourcePlay(s.source);
    }

    private static void destroy(Stream s) {
        if (s.source != 0 && AL10.alIsSource(s.source)) {
            AL10.alSourceStop(s.source);
            int queued = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_QUEUED);
            while (queued-- > 0) s.freeBuffers.add(AL10.alSourceUnqueueBuffers(s.source));
            AL10.alDeleteSources(s.source);
        }
        for (int b : s.freeBuffers) AL10.alDeleteBuffers(b);
        s.freeBuffers.clear();
        s.source = 0;
        AL11.alGetError();
    }

    public static void clear() {
        for (Stream s : STREAMS.values()) destroy(s);
        STREAMS.clear();
    }
}
