import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil; // 로그 레벨 설정을 위해 추가
import org.bytedeco.javacv.*;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter; // WindowListener 사용을 위해 추가
import java.awt.event.WindowEvent;  // WindowListener 사용을 위해 추가
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LocalVideoFileRecorder {

    public static void main(String[] args) {
        // FFmpeg 내부 로그 활성화 (디버깅에 매우 중요)
        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG); // DEBUG 레벨로 상세 로그 확인

        System.out.println("Starting webcam capture to local file...");

        // --- Configuration ---
        final int CAMERA_DEVICE_INDEX = 0;
        final String OUTPUT_FILE = "output.mp4";
        final int VIDEO_BITRATE = 2000000; // 2 Mbps
        final double FRAME_RATE = 30.0;    // 목표 프레임률 (FPS)

        AtomicReference<FrameGrabber> grabberRef = new AtomicReference<>();
        AtomicReference<FFmpegFrameRecorder> recorderRef = new AtomicReference<>();
        AtomicReference<CanvasFrame> canvasRef = new AtomicReference<>();
        AtomicBoolean recorderStartFailed = new AtomicBoolean(false);
        CountDownLatch recorderStartedLatch = new CountDownLatch(1);
        AtomicBoolean recorderProperlyStopped = new AtomicBoolean(false); // 레코더가 정상적으로 stop/release 되었는지 추적

        // 녹화 루프 제어 플래그
        final AtomicBoolean recordingActive = new AtomicBoolean(true);

        try {
            // 1. Initialize FrameGrabber (Webcam)
            System.out.println("Initializing camera grabber for device: " + CAMERA_DEVICE_INDEX);
            FrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_DEVICE_INDEX);
            grabberRef.set(grabber);
            // 해상도 설정 시도 (카메라 지원 여부에 따라 다름)
            // grabber.setImageWidth(1280);
            // grabber.setImageHeight(720);
            grabber.start();
            System.out.println("Camera grabber started.");

            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();

            if (imageWidth <= 0 || imageHeight <= 0) {
                System.err.println("Error: Camera resolution (" + imageWidth + "x" + imageHeight + ") is invalid. Attempting fallback 640x480.");
                grabber.stop(); // 일단 중지
                grabber.setImageWidth(640);
                grabber.setImageHeight(480);
                grabber.start(); // 새 해상도로 다시 시작
                imageWidth = grabber.getImageWidth();
                imageHeight = grabber.getImageHeight();
                if (imageWidth <= 0 || imageHeight <= 0) {
                    throw new FrameGrabber.Exception("Fatal: Could not get a valid resolution from camera even with fallback.");
                }
                System.out.println("Using fallback resolution: " + imageWidth + "x" + imageHeight);
            }
            System.out.println("Capture resolution for recorder: " + imageWidth + "x" + imageHeight);

            // 3. Live preview 창 (on EDT) - 종료 방식 변경
            System.out.println("Requesting CanvasFrame creation on EDT...");
            CountDownLatch canvasReadyLatch = new CountDownLatch(1);
            AtomicBoolean canvasCreationFailed = new AtomicBoolean(false);
            final int finalImageWidth = imageWidth;
            final int finalImageHeight = imageHeight;

            SwingUtilities.invokeAndWait(() -> {
                try {
                    CanvasFrame canvas = new CanvasFrame("Webcam Preview & Recording - Close to Stop");
                    canvasRef.set(canvas);
                    // JFrame.EXIT_ON_CLOSE 대신 DO_NOTHING_ON_CLOSE 사용
                    canvas.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    canvas.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent e) {
                            System.out.println("CanvasFrame closing event received. Signaling recording loop to stop.");
                            recordingActive.set(false); // 녹화 루프 중단 신호
                        }
                    });
                    canvas.setResizable(false);
                    canvas.setCanvasSize(finalImageWidth, finalImageHeight);
                    canvas.setVisible(true); // setVisible은 마지막에 호출
                    System.out.println("CanvasFrame.setVisible(true) called on EDT.");
                } catch (Exception e) {
                    System.err.println("Error creating CanvasFrame on EDT:");
                    e.printStackTrace();
                    canvasCreationFailed.set(true);
                } finally {
                    canvasReadyLatch.countDown();
                }
            });

            canvasReadyLatch.await(5, TimeUnit.SECONDS); // CanvasFrame 생성 대기
            if (canvasCreationFailed.get() || canvasRef.get() == null || !canvasRef.get().isShowing()) {
                System.err.println("CanvasFrame creation/visibility failed or timed out. Exiting.");
                return;
            }
            System.out.println("CanvasFrame is visible. isShowing(): " + canvasRef.get().isShowing());


            // 2. Initialize FFmpegFrameRecorder
            System.out.println("Initializing video recorder for output file: " + OUTPUT_FILE);
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(OUTPUT_FILE, imageWidth, imageHeight, 0); // 오디오 채널 0
            recorderRef.set(recorder);

            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setVideoBitrate(VIDEO_BITRATE);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(FRAME_RATE);
            recorder.setGopSize((int)FRAME_RATE * 2); // 2초 간격으로 키프레임
            recorder.setVideoOption("movflags", "faststart"); // MOOV 아톰을 파일 앞으로
            // recorder.setVideoOption("profile", "baseline"); // (선택 사항) H.264 프로파일 명시

            // Start recorder in a separate thread
            new Thread(() -> {
                try {
                    System.out.println("Starting recorder in a new thread...");
                    recorder.start();
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
            if (!recorderStartedLatch.await(10, TimeUnit.SECONDS)) {
                System.err.println("Timeout waiting for recorder to start.");
                recorderStartFailed.set(true); // 실패로 간주
                return;
            }
            if (recorderStartFailed.get()) {
                System.err.println("Recorder failed to start. Exiting.");
                return;
            }
            System.out.println("Recorder has started. Proceeding with recording loop.");

            System.out.println("Recording... Close the preview window (or wait for loop end) to stop.");
            long frameCount = 0;
            CanvasFrame currentCanvas = canvasRef.get();

            // 4. Main loop: recordingActive 플래그로 제어
            while (recordingActive.get()) {
                if (currentCanvas == null || !currentCanvas.isShowing()) { // 창이 닫혔는지 추가 확인
                    System.out.println("Canvas became non-showing. Stopping loop.");
                    recordingActive.set(false); // 루프 중단
                    break;
                }

                Frame capturedFrame = grabber.grab();
                if (capturedFrame == null) {
                    System.out.println("Warning: Null frame grabbed. End of stream or error.");
                    recordingActive.set(false); // 루프 중단
                    break;
                }

                // 프레임 해상도 일치 확인 (중요)
                if (capturedFrame.imageWidth != imageWidth || capturedFrame.imageHeight != imageHeight) {
                    System.err.println("Critical Error: Frame dimension mismatch! Recorder expected " + imageWidth + "x" + imageHeight +
                                       ", but captured " + capturedFrame.imageWidth + "x" + capturedFrame.imageHeight +
                                       ". This will likely corrupt the video. Stopping recording.");
                    recordingActive.set(false); // 루프 중단
                    break;
                }


                currentCanvas.showImage(capturedFrame); // 미리보기 업데이트

                long timestamp = frameCount * (1000000L / (long)FRAME_RATE);
                recorder.setTimestamp(timestamp);

                try {
                    recorder.record(capturedFrame);
                } catch (FrameRecorder.Exception fre) {
                    System.err.println("Exception during recorder.record() for frame " + frameCount + ":");
                    fre.printStackTrace();
                    recordingActive.set(false); // 루프 중단
                    break;
                }
                frameCount++;
            }
            System.out.println("Exited recording loop. Processed frames: " + frameCount);

            // --- 녹화 루프 종료 후 명시적 리소스 해제 ---
            // 이 부분이 정상적인 파일 마무리를 위해 매우 중요합니다.
            if (recorderRef.get() != null && !recorderStartFailed.get() && !recorderProperlyStopped.get()) {
                try {
                    System.out.println("Stopping and releasing recorder (post-loop)...");
                    recorderRef.get().stop();
                    recorderRef.get().release();
                    recorderProperlyStopped.set(true);
                    System.out.println("Recorder stopped and released successfully (post-loop).");
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error stopping/releasing recorder (post-loop):");
                    e.printStackTrace();
                }
            }

            if (frameCount > 0) {
                double actualDurationSec = (double)frameCount / FRAME_RATE;
                System.out.println("Recording duration (based on frame count and FPS): " + String.format("%.2f", actualDurationSec) + " seconds.");
            } else {
                System.out.println("No frames were recorded. The output file might be invalid or empty.");
            }

        } catch (FrameGrabber.Exception e) {
            System.err.println("FrameGrabber.Exception in main try-catch:");
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Thread interrupted in main try-catch:");
            e.printStackTrace();
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            System.err.println("InvocationTargetException (likely from Swing EDT) in main try-catch:");
            e.printStackTrace();
        } catch (Exception e) { // 모든 종류의 예외 포괄
            System.err.println("An unexpected generic Exception in main try-catch:");
            e.printStackTrace();
        } finally {
            System.out.println("Executing finally block: Releasing all resources...");

            // CanvasFrame 정리
            CanvasFrame canvas = canvasRef.get();
            if (canvas != null) {
                // EDT에서 dispose 호출 고려 (이미 닫혔다면 문제 없음)
                SwingUtilities.invokeLater(canvas::dispose);
                System.out.println("CanvasFrame dispose requested from finally block.");
            }

            // FFmpegFrameRecorder 정리 (이미 post-loop에서 처리했다면 중복 호출 방지)
            FFmpegFrameRecorder recorder = recorderRef.get();
            if (recorder != null && !recorderProperlyStopped.get() && !recorderStartFailed.get()) {
                try {
                    System.out.println("Stopping and releasing recorder in finally block (as a fallback)...");
                    // recorderStartedLatch.getCount() == 0는 레코더가 시작 시도는 했다는 의미
                    if (recorderStartedLatch.getCount() == 0) {
                        recorder.stop();
                        recorder.release();
                        System.out.println("Recorder stopped and released in finally block.");
                    } else {
                        System.out.println("Recorder start was not confirmed in finally, skipping stop/release.");
                    }
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error stopping/releasing recorder in finally block:");
                    e.printStackTrace();
                }
            } else if (recorderProperlyStopped.get()){
                System.out.println("Recorder already properly stopped, skipping in finally block.");
            } else {
                System.out.println("Recorder is null or failed to start, skipping stop/release in finally block.");
            }

            // FrameGrabber 정리
            FrameGrabber grabber = grabberRef.get();
            if (grabber != null) {
                try {
                    System.out.println("Stopping and releasing grabber in finally block...");
                    grabber.stop();
                    grabber.release();
                    System.out.println("Grabber stopped and released in finally block.");
                } catch (FrameGrabber.Exception e) {
                    System.err.println("Error stopping/releasing grabber in finally block:");
                    e.printStackTrace();
                }
            }
            System.out.println("Application finished all cleanup.");
            // JFrame.EXIT_ON_CLOSE를 사용하지 않았으므로, 모든 작업 완료 후 명시적 종료
            // System.exit(0); // 필요에 따라 주석 해제 (메인 스레드가 여기서 끝나면 자동 종료됨)
        }
    }
}