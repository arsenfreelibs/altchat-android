package org.thoughtcrime.securesms.video;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import java.io.File;
import java.util.concurrent.ExecutionException;

public class VideoNoteRecorder {

  private static final String TAG = VideoNoteRecorder.class.getSimpleName();
  private static final int MAX_DURATION_MS = 30_000;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private @Nullable ProcessCameraProvider cameraProvider;
  private @Nullable Recording activeRecording;
  private @Nullable Runnable autoStopRunnable;
  private @Nullable File outputFile;
  private @Nullable Callback callback;
  private long startTimeMs;
  private boolean cancelled;

  public interface Callback {
    void onRecordingStarted();

    void onRecordingFinished(long durationMs);

    void onAutoStopped();

    void onError(Exception e);
  }

  @MainThread
  public void start(
      @NonNull Context context,
      @NonNull LifecycleOwner owner,
      @NonNull PreviewView previewView,
      @NonNull File outputFile,
      @NonNull Callback callback) {
    this.outputFile = outputFile;
    this.callback = callback;
    this.cancelled = false;
    this.startTimeMs = 0;

    ProcessCameraProvider.getInstance(context)
        .addListener(
            () -> {
              try {
                cameraProvider = ProcessCameraProvider.getInstance(context).get();
                bindAndRecord(context, owner, previewView);
              } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProvider unavailable", e);
                if (callback != null) callback.onError(e);
              }
            },
            ContextCompat.getMainExecutor(context));
  }

  private void bindAndRecord(
      @NonNull Context context,
      @NonNull LifecycleOwner owner,
      @NonNull PreviewView previewView) {
    if (cameraProvider == null) return;

    CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;

    Preview preview = new Preview.Builder().build();
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    Recorder recorder =
        new Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.SD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)))
            .build();
    VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(recorder);

    cameraProvider.unbindAll();
    cameraProvider.bindToLifecycle(owner, selector, preview, videoCapture);

    FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();

    activeRecording =
        videoCapture
            .getOutput()
            .prepareRecording(context, options)
            .withAudioEnabled()
            .start(
                ContextCompat.getMainExecutor(context),
                event -> {
                  if (event instanceof VideoRecordEvent.Start) {
                    startTimeMs = System.currentTimeMillis();
                    if (callback != null) callback.onRecordingStarted();
                    scheduleAutoStop();
                  } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalizeEvent =
                        (VideoRecordEvent.Finalize) event;
                    long durationMs =
                        finalizeEvent.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
                    if (cancelled) {
                      if (outputFile != null) outputFile.delete();
                    } else {
                      if (callback != null) callback.onRecordingFinished(durationMs);
                    }
                  }
                });
  }

  private void scheduleAutoStop() {
    autoStopRunnable = this::stop;
    mainHandler.postDelayed(autoStopRunnable, MAX_DURATION_MS);
  }

  public long getElapsedMs() {
    return startTimeMs > 0 ? System.currentTimeMillis() - startTimeMs : 0;
  }

  @MainThread
  public void stop() {
    if (autoStopRunnable != null) {
      mainHandler.removeCallbacks(autoStopRunnable);
      autoStopRunnable = null;
    }
    if (activeRecording != null) {
      activeRecording.stop();
      activeRecording = null;
    }
    if (cameraProvider != null) {
      cameraProvider.unbindAll();
      cameraProvider = null;
    }
  }

  @MainThread
  public void cancel() {
    cancelled = true;
    stop();
  }
}
