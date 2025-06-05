
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException; // HttpClient 예외 처리를 위해 유지
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class StreamingClientMain {

    // --- Configuration ---
    final static int CAMERA_DEVICE_INDEX = 0;
    // MediaMTX로 보낼 RTMP 설정
    final static String RTMP_PUBLISH_URL = "rtmp://localhost:11935/live"; // MediaMTX가 수신할 RTMP 주소 (경로 'live'는 예시)
    // MediaMTX가 제공할 RTSP URL (클라이언트가 접속할 주소) - 이 주소를 서버에 등록
    final static String RTSP_ACCESS_PATH = "/live"; // RTMP와 동일한 경로 사용
    final static int MEDIAMTX_RTSP_PORT = 8554;   // MediaMTX의 기본 RTSP 포트

    final static int VIDEO_BITRATE = 2000000; // 2 Mbps
    final static double FRAME_RATE = 30.0;    // Target FPS

    final static String STREAM_NAME_ON_SERVER = "My MacBook Camera (via MediaMTX)";
    final static String STREAM_DESCRIPTION_ON_SERVER = "Live stream from MacBook, served by MediaMTX";
    final static String SPRING_BOOT_SERVER_URL = "http://localhost:8080";

    public static void main(String[] args) {
        FFmpegLogCallback.set(); // FFmpegFrameRecorder 내부 로그 확인용
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG);

        System.out.println("Starting RTMP Publishing Client (to MediaMTX)...");

        AtomicReference<FrameGrabber> grabberRef = new AtomicReference<>();
        AtomicReference<FFmpegFrameRecorder> recorderRef = new AtomicReference<>(); // 이제 RTMP 송출용
        AtomicReference<CanvasFrame> canvasRef = new AtomicReference<>();
        AtomicBoolean recorderStartFailed = new AtomicBoolean(false);
        CountDownLatch recorderStartedLatch = new CountDownLatch(1);
        AtomicBoolean recorderProperlyStopped = new AtomicBoolean(false);
        final AtomicBoolean streamingActive = new AtomicBoolean(true);

        try {
            // 1. 카메라 그래버 초기화 (이전과 동일)
            System.out.println("Initializing camera grabber for device: " + CAMERA_DEVICE_INDEX);
            FrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_DEVICE_INDEX);
            grabberRef.set(grabber);
            grabber.start();
            System.out.println("Camera grabber started.");

            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();
            if (imageWidth <= 0 || imageHeight <= 0) { /* ... 해상도 처리 ... */
                System.err.println("Error: Camera resolution (" + imageWidth + "x" + imageHeight + ") is invalid. Attempting fallback 640x480.");
                grabber.stop(); grabber.setImageWidth(640); grabber.setImageHeight(480); grabber.start();
                imageWidth = grabber.getImageWidth(); imageHeight = grabber.getImageHeight();
                if (imageWidth <= 0 || imageHeight <= 0) throw new FrameGrabber.Exception("Fatal: Could not get a valid resolution.");
                System.out.println("Using fallback resolution: " + imageWidth + "x" + imageHeight);
            }
            System.out.println("Capture resolution: " + imageWidth + "x" + imageHeight);

            // 2. 실시간 미리보기 창 생성 (이전과 동일)
            System.out.println("Requesting CanvasFrame creation on EDT...");
            CountDownLatch canvasReadyLatch = new CountDownLatch(1);
            AtomicBoolean canvasCreationFailed = new AtomicBoolean(false);
            final int finalImageWidth = imageWidth;
            final int finalImageHeight = imageHeight;

            SwingUtilities.invokeAndWait(() -> { /* ... CanvasFrame 생성 및 리스너 설정 (DO_NOTHING_ON_CLOSE) ... */
                try {
                    CanvasFrame canvas = new CanvasFrame("RTMP Publisher to MediaMTX - Close to Stop");
                    canvasRef.set(canvas);
                    canvas.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    canvas.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent e) {
                            System.out.println("CanvasFrame closing event. Signaling streaming loop to stop.");
                            streamingActive.set(false);
                        }
                    });
                    canvas.setResizable(false);
                    canvas.setCanvasSize(finalImageWidth, finalImageHeight);
                    canvas.setVisible(true);
                } catch (Exception ex) { canvasCreationFailed.set(true); ex.printStackTrace(); }
                finally { canvasReadyLatch.countDown(); }
            });
            canvasReadyLatch.await(5, TimeUnit.SECONDS);
            if (canvasCreationFailed.get() || canvasRef.get() == null || !canvasRef.get().isShowing()) { /* ... 에러 처리 ... */
                System.err.println("CanvasFrame creation/visibility failed. Exiting."); return;
            }
            System.out.println("CanvasFrame is visible.");


            // 3. Initialize FFmpegFrameRecorder for RTMP Publishing
            System.out.println("Initializing RTMP recorder. Publishing to: " + RTMP_PUBLISH_URL);
            // 오디오 채널은 카메라에 따라 결정 (여기서는 0으로 가정, 필요시 grabber.getAudioChannels() 사용)
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(RTMP_PUBLISH_URL, imageWidth, imageHeight, 0);
            recorderRef.set(recorder);

            recorder.setFormat("flv"); // RTMP는 주로 FLV 컨테이너 사용
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(FRAME_RATE);
            recorder.setVideoBitrate(VIDEO_BITRATE);
            recorder.setGopSize((int) FRAME_RATE * 2);
            // recorder.setVideoOption("preset", "ultrafast");
            // recorder.setVideoOption("tune", "zerolatency");

            // 오디오 설정 (필요한 경우)
            // if (grabber.getAudioChannels() > 0) {
            //     recorder.setAudioChannels(grabber.getAudioChannels());
            //     recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            //     recorder.setSampleRate(grabber.getSampleRate());
            //     recorder.setAudioBitrate(128000); // 예시
            // }

            // Start recorder in a separate thread
            new Thread(() -> {
                try {
                    System.out.println("Starting RTMP recorder in a new thread...");
                    recorder.start(); // FFmpeg 초기화 및 RTMP 연결/헤더 작성
                    System.out.println("RTMP recorder started successfully.");
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error starting RTMP recorder:");
                    e.printStackTrace();
                    recorderStartFailed.set(true);
                } finally {
                    recorderStartedLatch.countDown();
                }
            }).start();

            System.out.println("Waiting for RTMP recorder to start...");
            if (!recorderStartedLatch.await(10, TimeUnit.SECONDS)) {
                System.err.println("Timeout waiting for RTMP recorder to start.");
                recorderStartFailed.set(true); return;
            }
            if (recorderStartFailed.get()) {
                System.err.println("RTMP recorder failed to start. Exiting."); return;
            }
            System.out.println("RTMP recorder has started. Proceeding with streaming.");

            // 4. MediaMTX가 제공할 RTSP URL을 서버에 등록
            // (MediaMTX는 RTMP로 받은 스트림을 자동으로 RTSP로도 제공 가능)
            String actualHostIp = getSuitableLocalIpAddress();
            if (actualHostIp == null || actualHostIp.equals("localhost")) {
                System.err.println("Using 'localhost' for RTSP URL registration.");
                actualHostIp = "localhost";
            }
            String registerableRtspUrl = "rtsp://" + actualHostIp + ":" + MEDIAMTX_RTSP_PORT + RTSP_ACCESS_PATH;
            System.out.println("Registering stream with Spring Boot server. RTSP URL: " + registerableRtspUrl);
            registerStreamWithServer(STREAM_NAME_ON_SERVER, registerableRtspUrl, STREAM_DESCRIPTION_ON_SERVER);

            // 5. Main streaming loop (프레임을 RTMP로 전송)
            System.out.println("Streaming frames to MediaMTX (RTMP)... Close preview window to stop.");
            long frameCount = 0;
            CanvasFrame currentCanvas = canvasRef.get();
            OpenCVFrameConverter.ToMat frameToMatConverter = new OpenCVFrameConverter.ToMat(); // ToMat 컨버터 사용 가능

            while (streamingActive.get()) {
                if (currentCanvas == null || !currentCanvas.isShowing()) { /* ... 루프 중단 ... */
                    System.out.println("Canvas became non-showing. Stopping loop."); streamingActive.set(false); break;
                }
                Frame capturedFrame = grabber.grab();
                if (capturedFrame == null) { /* ... 루프 중단 ... */
                    System.out.println("Null frame grabbed. Stopping loop."); streamingActive.set(false); break;
                }
                // 해상도 불일치 체크 등은 이전과 동일하게 유지 가능

                currentCanvas.showImage(capturedFrame); // 로컬 미리보기

                try {
                    // RTMP는 타임스탬프를 레코더가 내부적으로 관리하거나, setTimestamp로 설정 가능
                    // 파일 녹화와 달리, RTMP 송출 시 setTimestamp가 항상 필수는 아닐 수 있으나,
                    // 일관성을 위해 또는 프레임률 제어를 위해 설정하는 것이 좋을 수 있습니다.
                    recorder.setTimestamp(frameCount * (1000000L / (long) FRAME_RATE)); // 프레임 기반 타임스탬프
                    recorder.record(capturedFrame);
                } catch (FrameRecorder.Exception fre) {
                    System.err.println("Exception during recorder.record() (RTMP): " + fre.getMessage());
                    // 송출 중단 또는 재시도 로직 추가 가능
                    // streamingActive.set(false); break;
                }
                frameCount++;
            }
            System.out.println("Exited streaming loop. Processed frames: " + frameCount);

        } catch (FrameGrabber.Exception | InterruptedException | InvocationTargetException e) {
            System.err.println("Main try-catch error: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            System.out.println("Executing finally block: Releasing resources...");
            // FFmpegFrameRecorder (RTMP) 정리
            FFmpegFrameRecorder currentRecorder = recorderRef.get();
            if (currentRecorder != null && !recorderProperlyStopped.get() && !recorderStartFailed.get()) {
                try {
                    System.out.println("Stopping and releasing RTMP recorder...");
                    if (recorderStartedLatch.getCount() == 0) { // 시작 시도된 경우에만
                        currentRecorder.stop();
                        currentRecorder.release();
                        recorderProperlyStopped.set(true);
                        System.out.println("RTMP recorder stopped and released.");
                    }
                } catch (FrameRecorder.Exception e) {
                    System.err.println("Error stopping/releasing RTMP recorder: " + e.getMessage());
                }
            }
            // CanvasFrame 정리 (이전과 동일)
            CanvasFrame canvas = canvasRef.get();
            if (canvas != null) SwingUtilities.invokeLater(canvas::dispose);

            // FrameGrabber 정리 (이전과 동일)
            FrameGrabber currentGrabber = grabberRef.get();
            if (currentGrabber != null) { /* ... 그래버 stop/release ... */
                try { currentGrabber.stop(); currentGrabber.release(); }
                catch (FrameGrabber.Exception e) { e.printStackTrace(); }
            }
            System.out.println("Application finished all cleanup phases.");
        }
    }

    // registerStreamWithServer 메소드 (이전과 동일하게 사용)
    private static void registerStreamWithServer(String name, String rtspUrl, String description) { /* ... 이전 코드 그대로 ... */
        StreamCreationRequestDto requestDto = new StreamCreationRequestDto(name, rtspUrl, description);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String requestBody = objectMapper.writeValueAsString(requestDto);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(SPRING_BOOT_SERVER_URL + "/api/streams")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(requestBody)).timeout(java.time.Duration.ofSeconds(10)).build();
            System.out.println("Sending registration request to server: " + requestBody);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Server registration response status code: " + response.statusCode());
            System.out.println("Server registration response body: " + response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) System.out.println("Stream successfully registered.");
            else System.err.println("Failed to register stream. Server responded with error: " + response.statusCode());
        } catch (IOException | InterruptedException e) {
            System.err.println("Error during stream registration: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    // getSuitableLocalIpAddress 메소드 (이전과 동일하게 사용)
    private static String getSuitableLocalIpAddress() { /* ... 이전 코드 그대로 ... */
        try {
            List<String> candidateIps = new ArrayList<>();
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(networkInterfaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                boolean preferredInterface = ni.getDisplayName().startsWith("en") || ni.getDisplayName().startsWith("eth") || ni.getDisplayName().startsWith("wlan");
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                    if (!inetAddress.isLoopbackAddress() && inetAddress.isSiteLocalAddress() && inetAddress.getHostAddress().matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                        if (preferredInterface) return inetAddress.getHostAddress();
                        candidateIps.add(inetAddress.getHostAddress());
                    }
                }
            }
            if (!candidateIps.isEmpty()) return candidateIps.get(0);
            return InetAddress.getLocalHost().getHostAddress();
        } catch (SocketException | java.net.UnknownHostException e) {
            System.err.println("Could not determine local IP: " + e.getMessage());
            return "localhost";
        }
    }
}