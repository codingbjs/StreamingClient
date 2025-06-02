import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LocalVideoFileRecorder {

    public static void main(String[] args) {
        System.out.println("Starting webcam capture to local file...");

        // --- Configuration ---
        final int CAMERA_DEVICE_INDEX = 0;
        final String OUTPUT_FILE = "output.mp4";
        final int VIDEO_BITRATE = 2000000;
        final double FRAME_RATE = 30.0; // 목표 프레임률 (FPS)

        AtomicReference<FrameGrabber> grabberRef = new AtomicReference<>();
        AtomicReference<FFmpegFrameRecorder> recorderRef = new AtomicReference<>();
        AtomicReference<CanvasFrame> canvasRef = new AtomicReference<>();

        // FFmpegLogCallback.set(); // 필요시 주석 해제하여 FFmpeg 내부 로그 확인

        // recorderStartFailed와 recorderStartedLatch를 try 블록 외부로 이동
        AtomicBoolean recorderStartFailed = new AtomicBoolean(false); // 초기값 false
        CountDownLatch recorderStartedLatch = new CountDownLatch(1); // 초기값 1
        AtomicBoolean recorderAlreadyStopped = new AtomicBoolean(false); // Track if recorder has been explicitly stopped

        try {
            // 1. Initialize FrameGrabber (Webcam)
            System.out.println("Initializing camera grabber for device: " + CAMERA_DEVICE_INDEX);
            FrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_DEVICE_INDEX);
            grabberRef.set(grabber);
            grabber.start();
            System.out.println("Camera grabber started.");

            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();

            if (imageWidth <= 0 || imageHeight <= 0) {
                System.err.println("Error: Camera resolution (" + imageWidth + "x" + imageHeight + ") is invalid after starting grabber.");
                System.err.println("Attempting to set a fallback resolution (640x480). This might not be supported by your camera.");
                try {
                    grabber.stop();
                    grabber.setImageWidth(640);
                    grabber.setImageHeight(480);
                    grabber.start();
                    imageWidth = grabber.getImageWidth();
                    imageHeight = grabber.getImageHeight();
                    if (imageWidth <= 0 || imageHeight <= 0) {
                        throw new FrameGrabber.Exception("Failed to set a valid resolution (640x480) for the camera.");
                    }
                    System.out.println("Using fallback resolution: " + imageWidth + "x" + imageHeight);
                } catch (FrameGrabber.Exception ex) {
                    System.err.println("Fatal: Could not get a valid resolution from camera. " + ex.getMessage());
                    return;
                }
            }
            System.out.println("Capture resolution for recorder: " + imageWidth + "x" + imageHeight);

            // 3. Live preview 창 (on EDT)
            System.out.println("Requesting CanvasFrame creation on EDT...");
            CountDownLatch canvasReadyLatch = new CountDownLatch(1);
            AtomicBoolean canvasCreationFailed = new AtomicBoolean(false);
            final int finalImageWidth = imageWidth;
            final int finalImageHeight = imageHeight;

            SwingUtilities.invokeAndWait(() -> {
                try {
                    CanvasFrame canvas = new CanvasFrame("Webcam Preview & Recording - Close to Stop");
                    canvasRef.set(canvas);
                    canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    canvas.setResizable(false);
                    canvas.setCanvasSize(finalImageWidth, finalImageHeight);
                    canvas.setVisible(true);
                    System.out.println("CanvasFrame.setVisible(true) called on EDT.");
                } catch (Exception e) {
                    System.err.println("Error creating CanvasFrame on EDT:");
                    e.printStackTrace();
                    canvasCreationFailed.set(true);
                } finally {
                    canvasReadyLatch.countDown();
                }
            });

            canvasReadyLatch.await(5, TimeUnit.SECONDS);
            if (canvasCreationFailed.get() || canvasRef.get() == null) {
                System.err.println("CanvasFrame creation failed or timed out. Exiting.");
                return;
            }
            if (!canvasRef.get().isShowing() || !canvasRef.get().isDisplayable()) {
                System.err.println("CanvasFrame created but not showing/displayable. isShowing(): " + canvasRef.get().isShowing() + ", isDisplayable(): " + canvasRef.get().isDisplayable());
                Thread.sleep(1000); // Give it a bit more time
                if (!canvasRef.get().isShowing()) {
                    System.err.println("CanvasFrame still not showing after extra wait. Exiting.");
                    return;
                }
            }
            System.out.println("CanvasFrame should be visible now. isShowing(): " + canvasRef.get().isShowing() + ", isDisplayable(): " + canvasRef.get().isDisplayable());

            // 2. Initialize FFmpegFrameRecorder
            System.out.println("Initializing video recorder for output file: " + OUTPUT_FILE);
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(OUTPUT_FILE, imageWidth, imageHeight, 0); // 0 audio channels
            recorderRef.set(recorder);

            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setVideoBitrate(VIDEO_BITRATE);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(FRAME_RATE); // 목표 프레임률 설정!
            recorder.setOption("movflags", "faststart"); // Add faststart flag for better compatibility

            // Start recorder in a separate thread
            // AtomicBoolean recorderStartFailed = new AtomicBoolean(false); // 이미 외부에서 선언됨
            // CountDownLatch recorderStartedLatch = new CountDownLatch(1); // 이미 외부에서 선언됨
            new Thread(() -> {
                try {
                    System.out.println("Starting recorder in a new thread...");
                    recorder.start(); // FFmpeg 초기화 및 헤더 작성
                    System.out.println("Video recorder started successfully in a new thread.");
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error starting recorder in a new thread:");
                    e.printStackTrace();
                    recorderStartFailed.set(true);
                } finally {
                    recorderStartedLatch.countDown();
                }
            }).start();

            System.out.println("Waiting for recorder to start...");
            if (!recorderStartedLatch.await(10, TimeUnit.SECONDS)) { // Wait up to 10 seconds
                System.err.println("Timeout waiting for recorder to start. Recorder might be stuck or taking too long.");
                // recorderStartFailed를 true로 설정하여 finally 블록에서 stop/release를 건너뛰도록 할 수 있습니다.
                recorderStartFailed.set(true);
                return;
            }
            if (recorderStartFailed.get()) {
                System.err.println("Recorder failed to start. Exiting.");
                return;
            }
            System.out.println("Recorder has started. Proceeding with recording loop.");

            System.out.println("Recording... Close the preview window to stop.");
            long frameCount = 0;

            CanvasFrame currentCanvas = canvasRef.get();

            // 4. Main loop
            while (currentCanvas != null && currentCanvas.isDisplayable() && currentCanvas.isShowing()) {
                Frame capturedFrame = grabber.grab();

                if (capturedFrame == null) {
                    System.out.println("Warning: Null frame grabbed. End of stream or error.");
                    break;
                }

                if (capturedFrame.imageWidth != imageWidth || capturedFrame.imageHeight != imageHeight) {
                    System.err.println("Warning: Frame dimension mismatch! Recorder: " + imageWidth + "x" + imageHeight +
                                       ", Captured: " + capturedFrame.imageWidth + "x" + capturedFrame.imageHeight +
                                       ". This can lead to corrupted video. Stopping.");
                    break;
                }

                currentCanvas.showImage(capturedFrame);

                // --- 타임스탬프 설정 (프레임 카운트 기반) ---
                long timestamp = frameCount * (1000000L / (long)FRAME_RATE);
                recorder.setTimestamp(timestamp);
                // --- 타임스탬프 설정 끝 ---

                System.out.println("Attempting to record frame: " + frameCount + " with timestamp: " + timestamp);
                try {
                    recorder.record(capturedFrame); // 이 호출 전에 timestamp가 설정되어야 함
                    System.out.println("Successfully recorded frame: " + frameCount);
                } catch (FrameRecorder.Exception fre) {
                    System.err.println("Exception during recorder.record() for frame " + frameCount + ":");
                    fre.printStackTrace();
                    break; // 녹화 중 예외 발생 시 루프 중단
                }
                frameCount++;
            }
            System.out.println("Exited recording loop."); // 루프 종료 확인 로그 추가

            // Explicitly stop and release the recorder here to ensure proper finalization
            if (recorder != null && !recorderStartFailed.get()) {
                try {
                    System.out.println("Stopping recorder to finalize the video file...");
                    recorder.stop();
                    recorder.release();
                    // Set recorderRef to null to indicate it's already been stopped and released
                    recorderRef.set(null);
                    System.out.println("Recorder stopped and released successfully.");
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error stopping/releasing recorder:");
                    e.printStackTrace();
                }
            }

            System.out.println("Recording finished. Total frames recorded: " + frameCount);
            if (frameCount > 0) {
                double actualDurationSec = (double)frameCount / FRAME_RATE;
                System.out.println("Recording duration (based on frame count and FPS): " + String.format("%.2f", actualDurationSec) + " seconds.");
            } else {
                System.out.println("No frames were recorded. The output file might be invalid.");
            }

        } catch (FrameGrabber.Exception e) {
            System.err.println("Error during frame grabbing:");
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("A thread was interrupted:");
            e.printStackTrace();
            Thread.currentThread().interrupt(); // Preserve interrupt status
        } catch (InvocationTargetException e) {
            System.err.println("Error during SwingUtilities.invokeAndWait:");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred:");
            e.printStackTrace();
        } finally {
            System.out.println("Releasing resources...");
            CanvasFrame canvas = canvasRef.get();
            if (canvas != null) {
                canvas.dispose();
            }

            FFmpegFrameRecorder recorder = recorderRef.get();
            if (recorder != null) {
                try {
                    System.out.println("Stopping and releasing recorder in finally block...");
                    // Check if recorder was started before trying to stop
                    if (!recorderStartFailed.get() && recorderStartedLatch.getCount() == 0) { // Ensure it was started
                        recorder.stop();
                        recorder.release();
                        System.out.println("Recorder stopped and released in finally block.");
                    } else {
                        System.out.println("Recorder was not started or failed to start, or timed out, skipping stop/release.");
                    }
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error stopping/releasing recorder:");
                    e.printStackTrace();
                }
            } else {
                System.out.println("Recorder is null, it may have been already stopped and released.");
            }

            FrameGrabber grabber = grabberRef.get();
            if (grabber != null) {
                try {
                    System.out.println("Stopping and releasing grabber...");
                    grabber.stop();
                    grabber.release();
                    System.out.println("Grabber stopped and released.");
                } catch (FrameGrabber.Exception e) {
                    System.err.println("Error stopping/releasing grabber:");
                    e.printStackTrace();
                }
            }
            System.out.println("Application finished.");
        }
    }
}
