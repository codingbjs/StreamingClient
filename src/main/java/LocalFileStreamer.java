
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File; // 파일 존재 여부 확인용
import java.io.IOException;
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

public class LocalFileStreamer {

    // --- Configuration ---
    // 스트리밍할 로컬 MP4 파일 경로를 지정하세요.
    // 예: "C:/videos/myvideo.mp4" (윈도우), "/Users/username/Movies/myvideo.mp4" (macOS)
    final static String INPUT_MP4_FILE_PATH = "/Users/won-yongseok/Downloads/2025-03-14 08-36-37.mp4"; // <<--- 이 부분을 실제 파일 경로로 수정하세요!

    // MediaMTX로 보낼 RTMP 설정
    final static String RTMP_PUBLISH_URL = "rtmp://localhost:1935/file_stream"; // MediaMTX 수신 경로 (예: /file_stream)
    // MediaMTX가 제공할 RTSP URL (클라이언트 접속 주소) - 이 주소를 서버에 등록
    final static String RTSP_ACCESS_PATH = "/file_stream"; // RTMP와 동일한 경로 사용
    final static int MEDIAMTX_RTSP_PORT = 8554;   // MediaMTX의 기본 RTSP 포트

    // VIDEO_BITRATE와 FRAME_RATE는 원본 파일의 것을 따르거나, 재인코딩 시 목표 값으로 설정 가능
    // 여기서는 원본을 따르도록 시도하고, 문제가 생기면 고정값 사용을 고려합니다.
    // final static int VIDEO_BITRATE = 2000000; // 2 Mbps (재인코딩 시)
    // final static double FRAME_RATE = 30.0;    // Target FPS (재인코딩 시)

    final static String STREAM_NAME_ON_SERVER = "Local MP4 File Stream (via MediaMTX)";
    final static String STREAM_DESCRIPTION_ON_SERVER = "Streaming a local MP4 file, served by MediaMTX";
    final static String SPRING_BOOT_SERVER_URL = "http://localhost:8080";

    public static void main(String[] args) {
        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG);

        System.out.println("Starting MP4 File Streaming Client (to MediaMTX)...");
        System.out.println("Input MP4 file: " + INPUT_MP4_FILE_PATH);

        File inputFile = new File(INPUT_MP4_FILE_PATH);
        if (!inputFile.exists() || !inputFile.isFile()) {
            System.err.println("Error: Input MP4 file not found or is not a file: " + INPUT_MP4_FILE_PATH);
            return;
        }

        AtomicReference<FFmpegFrameGrabber> grabberRef = new AtomicReference<>(); // 파일용 FFmpegFrameGrabber
        AtomicReference<FFmpegFrameRecorder> recorderRef = new AtomicReference<>();
        AtomicReference<CanvasFrame> canvasRef = new AtomicReference<>();
        AtomicBoolean recorderStartFailed = new AtomicBoolean(false);
        CountDownLatch recorderStartedLatch = new CountDownLatch(1);
        AtomicBoolean recorderProperlyStopped = new AtomicBoolean(false);
        final AtomicBoolean streamingActive = new AtomicBoolean(true);

        try {
            // 1. Initialize FrameGrabber (MP4 File)
            System.out.println("Initializing file grabber for: " + INPUT_MP4_FILE_PATH);
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(INPUT_MP4_FILE_PATH);
            grabberRef.set(grabber);
            grabber.start(); // 파일 열기 및 정보 읽기
            System.out.println("File grabber started.");

            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();
            double sourceFrameRate = grabber.getFrameRate(); // 원본 파일의 프레임률
            int audioChannels = grabber.getAudioChannels(); // 원본 파일의 오디오 채널 수

            if (imageWidth <= 0 || imageHeight <= 0) {
                throw new FrameGrabber.Exception("Fatal: Could not get valid video dimensions from input file.");
            }
            if (sourceFrameRate <= 0) { // 유효한 프레임률이 아니면 기본값 사용
                System.out.println("Warning: Could not get valid frame rate from source. Using default 30 FPS.");
                sourceFrameRate = 30.0;
            }
            System.out.println("Input file details - Resolution: " + imageWidth + "x" + imageHeight +
                               ", FrameRate: " + String.format("%.2f", sourceFrameRate) +
                               ", AudioChannels: " + audioChannels);

            // 2. Live preview 창 (선택 사항, 파일 스트리밍 시에도 유용)
            System.out.println("Requesting CanvasFrame creation on EDT...");
            CountDownLatch canvasReadyLatch = new CountDownLatch(1);
            AtomicBoolean canvasCreationFailed = new AtomicBoolean(false);
            final int finalImageWidth = imageWidth;
            final int finalImageHeight = imageHeight;

            SwingUtilities.invokeAndWait(() -> {
                try {
                    CanvasFrame canvas = new CanvasFrame("MP4 File Streamer to MediaMTX - Close to Stop");
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
            if (canvasCreationFailed.get() || canvasRef.get() == null || !canvasRef.get().isShowing()) {
                System.err.println("CanvasFrame creation/visibility failed. Exiting."); return;
            }
            System.out.println("CanvasFrame is visible.");


            // 3. Initialize FFmpegFrameRecorder for RTMP Publishing
            System.out.println("Initializing RTMP recorder. Publishing to: " + RTMP_PUBLISH_URL);
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(RTMP_PUBLISH_URL, imageWidth, imageHeight, audioChannels);
            recorderRef.set(recorder);

            recorder.setFormat("flv"); // RTMP는 주로 FLV 컨테이너 사용

            // Video settings
            // 원본 코덱을 그대로 사용하거나 (copy), 특정 코덱으로 재인코딩할 수 있습니다.
            // 여기서는 H.264로 재인코딩하는 예시를 보여줍니다. 원본을 그대로 쓰려면 grabber에서 코덱 정보를 가져와 설정해야 합니다.
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P); // H.264와 호환되는 픽셀 포맷
            recorder.setFrameRate(sourceFrameRate); // 원본 파일 프레임률 사용
            if(grabber.getVideoBitrate() > 0) { // 원본 비트레이트 사용 시도
                recorder.setVideoBitrate(grabber.getVideoBitrate());
            } else {
                recorder.setVideoBitrate(2000000); // 기본값 2Mbps
            }
            recorder.setGopSize((int)sourceFrameRate * 2); // 2초 간격 키프레임

            // Audio settings (원본 파일에 오디오가 있는 경우)
            if (audioChannels > 0) {
                System.out.println("Audio detected. Configuring audio for RTMP stream.");
                // 원본 오디오 코덱을 그대로 사용하거나, AAC 등으로 재인코딩할 수 있습니다.
                // 여기서는 AAC로 재인코딩하는 예시입니다.
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setSampleRate(grabber.getSampleRate());
                recorder.setAudioChannels(audioChannels);
                if (grabber.getAudioBitrate() > 0) {
                    recorder.setAudioBitrate(grabber.getAudioBitrate());
                } else {
                    recorder.setAudioBitrate(128000); // 기본값 128 kbps
                }
            } else {
                System.out.println("No audio detected in source file.");
            }

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
            if (!recorderStartedLatch.await(10, TimeUnit.SECONDS)) { /* ... 타임아웃 처리 ... */
                System.err.println("Timeout waiting for RTMP recorder to start."); recorderStartFailed.set(true); return;
            }
            if (recorderStartFailed.get()) { /* ... 시작 실패 처리 ... */
                System.err.println("RTMP recorder failed to start. Exiting."); return;
            }
            System.out.println("RTMP recorder has started. Proceeding with streaming.");

            // 4. MediaMTX가 제공할 RTSP URL을 서버에 등록
            String actualHostIp = getSuitableLocalIpAddress();
            if (actualHostIp == null || actualHostIp.equals("localhost")) { /* ... localhost 사용 경고 ... */
                System.err.println("Using 'localhost' for RTSP URL registration."); actualHostIp = "localhost";
            }
            String registerableRtspUrl = "rtsp://" + actualHostIp + ":" + MEDIAMTX_RTSP_PORT + RTSP_ACCESS_PATH;
            System.out.println("Registering stream with Spring Boot server. RTSP URL: " + registerableRtspUrl);
            registerStreamWithServer(STREAM_NAME_ON_SERVER, registerableRtspUrl, STREAM_DESCRIPTION_ON_SERVER);

            // 5. Main streaming loop (파일에서 프레임을 읽어 RTMP로 전송)
            System.out.println("Streaming frames from MP4 file to MediaMTX (RTMP)... Close preview window to stop.");
            CanvasFrame currentCanvas = canvasRef.get();
            Frame capturedFrame;

            // 파일에서 프레임을 읽을 때는 grabber.getTimestamp() 사용 권장
            while (streamingActive.get() && (capturedFrame = grabber.grab()) != null) { // grab()은 비디오/오디오 모두 가져옴
                if (currentCanvas == null || !currentCanvas.isShowing()) { /* ... 루프 중단 ... */
                    System.out.println("Canvas became non-showing. Stopping loop."); streamingActive.set(false); break;
                }

                if (capturedFrame.image != null && currentCanvas.isDisplayable()) { // 이미지가 있는 프레임만 미리보기
                    currentCanvas.showImage(capturedFrame);
                }

                try {
                    if (grabber.getTimestamp() == 0 && capturedFrame.timestamp != 0) {
                        // OpenCVFrameGrabber 등 일부 그래버는 getTimestamp() 대신 frame.timestamp를 채울 수 있음
                        recorder.setTimestamp(capturedFrame.timestamp);
                    } else if (grabber.getTimestamp() != 0) {
                        recorder.setTimestamp(grabber.getTimestamp()); // 파일의 원래 타임스탬프 사용
                    }
                    // 그 외의 경우, 레코더가 타임스탬프를 자체적으로 관리하거나, 프레임률 기반으로 계산해야 할 수 있지만,
                    // 파일 입력 시에는 grabber.getTimestamp()를 우선적으로 사용하는 것이 좋습니다.
                    // 만약 위 조건들이 모두 실패하면, RTMP의 경우 timestamp 없이 record()를 시도해 볼 수도 있습니다.
                    // (FFmpeg이 내부적으로 처리 시도)

                    recorder.record(capturedFrame); // 비디오 및 오디오 프레임 모두 레코딩
                } catch (FrameRecorder.Exception fre) {
                    System.err.println("Exception during recorder.record() (RTMP from file): " + fre.getMessage());
                    // streamingActive.set(false); break; // 필요시 중단
                }
            }
            System.out.println("Finished reading from file or streaming loop was interrupted.");

        } catch (FrameGrabber.Exception | InterruptedException | InvocationTargetException e) {
            System.err.println("Main try-catch error: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            // 리소스 해제 (이전 StreamingClientMain과 유사한 방식으로 처리)
            System.out.println("Executing finally block: Releasing all resources...");
            FFmpegFrameRecorder currentRecorder = recorderRef.get();
            if (currentRecorder != null && !recorderProperlyStopped.get() && !recorderStartFailed.get()) { /* ... 레코더 stop/release ... */
                try {
                    System.out.println("Stopping and releasing RTMP recorder (file streamer)...");
                    if (recorderStartedLatch.getCount() == 0) {
                        currentRecorder.stop(); currentRecorder.release(); recorderProperlyStopped.set(true);
                        System.out.println("RTMP recorder (file streamer) stopped and released.");
                    }
                } catch (FrameRecorder.Exception e) { e.printStackTrace(); }
            }
            CanvasFrame canvas = canvasRef.get();
            if (canvas != null) SwingUtilities.invokeLater(canvas::dispose);
            FFmpegFrameGrabber currentGrabber = grabberRef.get(); // FFmpegFrameGrabber로 변경
            if (currentGrabber != null) { /* ... 그래버 stop/release ... */
                try { currentGrabber.stop(); currentGrabber.release(); }
                catch (FrameGrabber.Exception e) { e.printStackTrace(); }
            }
            System.out.println("File streamer application finished all cleanup phases.");
        }
    }

    // registerStreamWithServer, getSuitableLocalIpAddress 메소드는 이전과 동일하게 사용
    private static void registerStreamWithServer(String name, String rtspUrl, String description) { /* ... 이전 코드 ... */
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

    private static String getSuitableLocalIpAddress() { /* ... 이전 코드 ... */
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