/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

import java.io.File;
import java.nio.ShortBuffer;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

/**
 * Loads the FFmpeg the app ships and runs it both ways: two seconds of a tone are encoded to AAC
 * and decoded back. Run with the app's own Java and jars, so it checks the libraries in the
 * build, not the machine's. Exits 1 if nothing comes back.
 */
public class FfmpegCheck {
    public static void main(String[] args) throws Exception {
        File file = new File(args.length > 0 ? args[0] : "ffmpeg-check.m4a");
        int rate = 44100;
        int seconds = 2;
        short[] samples = new short[rate * seconds * 2];
        for (int i = 0; i < rate * seconds; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12000);
            samples[2 * i] = value;
            samples[2 * i + 1] = value;
        }
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file, 2)) {
            recorder.setFormat("mp4");
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setSampleRate(rate);
            recorder.setAudioBitrate(128_000);
            recorder.start();
            recorder.recordSamples(rate, 2, ShortBuffer.wrap(samples));
            recorder.stop();
        }
        long frames = 0;
        long decoded = 0;
        String codec;
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            codec = grabber.getAudioCodecName();
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (frame.samples == null) continue;
                frames++;
                decoded += frame.samples[0].limit();
            }
            grabber.stop();
        }
        int version = avformat.avformat_version();
        System.out.printf("FFmpeg libavformat %d.%d.%d: wrote %d bytes of AAC, decoded %d frames (%d samples, codec %s)%n",
            version >> 16, (version >> 8) & 0xff, version & 0xff, file.length(), frames, decoded, codec);
        if (frames == 0 || decoded < rate) {
            System.out.println("FFmpeg decoded nothing");
            System.exit(1);
        }
    }
}
