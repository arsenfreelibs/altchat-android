package org.thoughtcrime.securesms.components.audioplay;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Extracts and downsamples audio PCM data to produce a waveform suitable for display.
 *
 * <p>The resulting float[] contains {@link #BUCKET_COUNT} values in the range [0.0, 1.0],
 * where each value represents the peak amplitude of the corresponding segment.
 */
public final class AudioWaveformHelper {

  private static final String TAG = AudioWaveformHelper.class.getSimpleName();

  public static final int BUCKET_COUNT = 40;
  private static final int MAX_FRAMES = 2_000_000;
  private static final long TIMEOUT_US = 10_000;

  private AudioWaveformHelper() {}

  /**
   * Synchronously extracts waveform data from an audio file.
   *
   * <p>Must NOT be called on the main thread.
   *
   * @return float[] of length {@link #BUCKET_COUNT} with values in [0, 1.0], or an empty array on
   *     failure.
   */
  @NonNull
  public static float[] extractWaveform(@NonNull Context context, @NonNull Uri audioUri) {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try {
      extractor.setDataSource(context, audioUri, null);

      int audioTrackIndex = selectAudioTrack(extractor);
      if (audioTrackIndex < 0) {
        return new float[0];
      }
      extractor.selectTrack(audioTrackIndex);
      MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

      String mime = format.getString(MediaFormat.KEY_MIME);
      int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
          ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(format, null, null, 0);
      codec.start();

      float[] monoSamples = decodeMono(extractor, codec, channelCount);
      if (monoSamples == null || monoSamples.length == 0) {
        return new float[0];
      }

      float[] buckets = downsample(monoSamples);
      normalize(buckets);
      return buckets;

    } catch (IOException | IllegalStateException e) {
      Log.w(TAG, "Failed to extract waveform", e);
      return new float[0];
    } finally {
      if (codec != null) {
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
      }
      try { extractor.release(); } catch (Exception ignored) {}
    }
  }

  private static int selectAudioTrack(@NonNull MediaExtractor extractor) {
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith("audio/")) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Decodes the selected audio track and collapses all channels to a mono float[] (mean of abs
   * values per frame), capped at {@link #MAX_FRAMES} mono samples.
   */
  private static float[] decodeMono(
      @NonNull MediaExtractor extractor,
      @NonNull MediaCodec codec,
      int channelCount) {

    float[] accumulator = new float[MAX_FRAMES];
    int sampleCount = 0;
    boolean inputDone = false;
    boolean outputDone = false;

    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    while (!outputDone && sampleCount < MAX_FRAMES) {
      if (!inputDone) {
        int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex >= 0) {
          ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
          if (inputBuffer == null) continue;
          int sampleSize = extractor.readSampleData(inputBuffer, 0);
          if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputDone = true;
          } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                extractor.getSampleTime(), 0);
            extractor.advance();
          }
        }
      }

      int outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
      if (outputIndex >= 0) {
        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
        if (outputBuffer != null && bufferInfo.size > 0) {
          // Output is 16-bit PCM (interleaved channels)
          outputBuffer.order(ByteOrder.nativeOrder());
          ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
          int totalShorts = shortBuffer.remaining();
          int frames = totalShorts / channelCount;

          for (int f = 0; f < frames && sampleCount < MAX_FRAMES; f++) {
            float monoSample = 0f;
            for (int c = 0; c < channelCount; c++) {
              monoSample += Math.abs(shortBuffer.get(f * channelCount + c));
            }
            monoSample /= (channelCount * 32768f); // normalize to [0, 1]
            accumulator[sampleCount++] = monoSample;
          }
        }
        codec.releaseOutputBuffer(outputIndex, false);

        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          outputDone = true;
        }
      }
    }

    if (sampleCount == 0) {
      return null;
    }

    float[] result = new float[sampleCount];
    System.arraycopy(accumulator, 0, result, 0, sampleCount);
    return result;
  }

  /** Downsamples {@code samples} to exactly {@link #BUCKET_COUNT} values using max-per-chunk. */
  @NonNull
  private static float[] downsample(@NonNull float[] samples) {
    float[] buckets = new float[BUCKET_COUNT];
    int chunkSize = Math.max(1, samples.length / BUCKET_COUNT);

    for (int i = 0; i < BUCKET_COUNT; i++) {
      int start = i * chunkSize;
      int end = (i == BUCKET_COUNT - 1) ? samples.length : start + chunkSize;
      float max = 0f;
      for (int j = start; j < end; j++) {
        if (samples[j] > max) max = samples[j];
      }
      buckets[i] = max;
    }
    return buckets;
  }

  /** Normalizes {@code buckets} in-place so the maximum value becomes 1.0. */
  private static void normalize(@NonNull float[] buckets) {
    float max = 0f;
    for (float v : buckets) {
      if (v > max) max = v;
    }
    if (max > 0f) {
      for (int i = 0; i < buckets.length; i++) {
        buckets[i] /= max;
      }
    }
  }
}
