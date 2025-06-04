
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacv.*;
import org.bytedeco.opencv.opencv_core.Mat;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    final static int CAMERA_DEVICE_INDEX = 0; // 사용할 카메라 장치 번호
    final static String RTSP_PATH = "/live";  // RTSP 스트림 경로 (예: /live, /cam1 등)
    final static int RTSP_PORT = 8554;       // RTSP 서버가 리슨할 포트
    final static int VIDEO_BITRATE = 2000000; // 비디오 비트레이트 (bps), 예: 2 Mbps
    final static double FRAME_RATE = 30.0;     // 목표 초당 프레임 수 (FPS)

    // 시스템 PATH에 ffmpeg이 있거나, 애플리케이션과 함께 배포된 ffmpeg 실행파일의 전체/상대 경로
    final static String FFMPEG_PATH = "ffmpeg";

    final static String STREAM_NAME_ON_SERVER = "My PC Camera (FFmpeg CLI)"; // 서버에 등록될 스트림 이름
    final static String STREAM_DESCRIPTION_ON_SERVER = "Live stream from PC via FFmpeg CLI"; // 스트림 설명
    final static String SPRING_BOOT_SERVER_URL = "http://localhost:8080"; // 스프링 부트 서버 주소

    private static Process ffmpegProcess;        // FFmpeg 프로세스 참조
    private static OutputStream ffmpegStdin;     // FFmpeg 프로세스의 표준 입력 스트림
    private static Thread ffmpegLogReaderThread; // FFmpeg 로그 리더 스레드

    public static void main(String[] args) {
        System.out.println("Starting RTSP Streaming Client (using FFmpeg CLI)...");

        AtomicReference<FrameGrabber> grabberRef = new AtomicReference<>();
        AtomicReference<CanvasFrame> canvasRef = new AtomicReference<>();
        final AtomicBoolean streamingActive = new AtomicBoolean(true); // 스트리밍 루프 제어

        OpenCVFrameConverter.ToMat frameToMatConverter = new OpenCVFrameConverter.ToMat();

        try {
            // 1. 카메라 그래버 초기화
            System.out.println("Initializing camera grabber for device: " + CAMERA_DEVICE_INDEX);
            FrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_DEVICE_INDEX);
            grabberRef.set(grabber);
            // grabber.setImageWidth(1280); // 필요시 해상도 설정
            // grabber.setImageHeight(720);
            grabber.start();
            System.out.println("Camera grabber started.");

            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();

            if (imageWidth <= 0 || imageHeight <= 0) {
                System.err.println("Error: Camera resolution (" + imageWidth + "x" + imageHeight + ") is invalid. Attempting fallback 640x480.");
                grabber.stop();
                grabber.setImageWidth(640);
                grabber.setImageHeight(480);
                grabber.start();
                imageWidth = grabber.getImageWidth();
                imageHeight = grabber.getImageHeight();
                if (imageWidth <= 0 || imageHeight <= 0) {
                    throw new FrameGrabber.Exception("Fatal: Could not get a valid resolution from camera.");
                }
                System.out.println("Using fallback resolution: " + imageWidth + "x" + imageHeight);
            }
            System.out.println("Capture resolution for FFmpeg: " + imageWidth + "x" + imageHeight);

            // 2. 실시간 미리보기 창 생성 (Swing EDT에서 실행)
            System.out.println("Requesting CanvasFrame creation on EDT...");
            CountDownLatch canvasReadyLatch = new CountDownLatch(1);
            AtomicBoolean canvasCreationFailed = new AtomicBoolean(false);
            final int finalImageWidth = imageWidth; // Effectively final for lambda
            final int finalImageHeight = imageHeight; // Effectively final for lambda

            SwingUtilities.invokeAndWait(() -> {
                try {
                    CanvasFrame canvas = new CanvasFrame("RTSP Streamer (FFmpeg CLI) - Close to Stop");
                    canvasRef.set(canvas);
                    canvas.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // 직접 종료 처리
                    canvas.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent e) {
                            System.out.println("CanvasFrame closing event. Signaling streaming loop to stop.");
                            streamingActive.set(false); // 스트리밍 루프 중단 신호
                        }
                    });
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

            canvasReadyLatch.await(5, TimeUnit.SECONDS); // CanvasFrame 생성 대기
            if (canvasCreationFailed.get() || canvasRef.get() == null || !canvasRef.get().isShowing()) {
                System.err.println("CanvasFrame creation/visibility failed. Exiting.");
                if (grabberRef.get() != null) grabberRef.get().stop(); // 그래버 중지
                return;
            }
            System.out.println("CanvasFrame is visible.");

            // 3. FFmpeg CLI 프로세스 시작 (RTSP 서버 역할)
            String rtspListenUrl = "rtsp://0.0.0.0:" + RTSP_PORT + RTSP_PATH;
            System.out.println("Starting FFmpeg CLI for RTSP. Listening on: " + rtspListenUrl);

            List<String> ffmpegCommand = new ArrayList<>();
            ffmpegCommand.add(FFMPEG_PATH);
            ffmpegCommand.add("-f"); ffmpegCommand.add("rawvideo");
            ffmpegCommand.add("-pixel_format"); ffmpegCommand.add("bgr24"); // OpenCV Mat.data() 기본 순서
            ffmpegCommand.add("-video_size"); ffmpegCommand.add(imageWidth + "x" + imageHeight);
            ffmpegCommand.add("-framerate"); ffmpegCommand.add(String.valueOf(FRAME_RATE));
            ffmpegCommand.add("-i"); ffmpegCommand.add("pipe:0"); // 표준 입력으로부터 비디오 데이터 읽기
            ffmpegCommand.add("-c:v"); ffmpegCommand.add("libx264");
            ffmpegCommand.add("-preset"); ffmpegCommand.add("ultrafast"); // 실시간 우선
            ffmpegCommand.add("-tune"); ffmpegCommand.add("zerolatency");  // 지연 최소화
            ffmpegCommand.add("-pix_fmt"); ffmpegCommand.add("yuv420p");   // H.264 호환 픽셀 포맷
            ffmpegCommand.add("-g"); ffmpegCommand.add(String.valueOf((int)FRAME_RATE * 2)); // GOP 크기 (2초 간격 키프레임)
            ffmpegCommand.add("-b:v"); ffmpegCommand.add(String.valueOf(VIDEO_BITRATE));
            ffmpegCommand.add("-an"); // 오디오 없음 (오디오 추가 시 이 옵션 제거 및 관련 옵션 추가)
            ffmpegCommand.add("-f"); ffmpegCommand.add("rtsp");
            ffmpegCommand.add("-rtsp_flags"); ffmpegCommand.add("listen"); // RTSP 서버 모드
            // ffmpegCommand.add("-rtsp_transport"); ffmpegCommand.add("tcp"); // 필요시 TCP 전송 강제
            ffmpegCommand.add(rtspListenUrl);

            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegCommand);
            System.out.println("Executing FFmpeg command: " + String.join(" ", ffmpegCommand));
            ffmpegProcess = processBuilder.start();
            ffmpegStdin = ffmpegProcess.getOutputStream();

            // FFmpeg의 stderr 로그를 읽어 처리하는 스레드 (FFmpeg이 멈추는 것을 방지하고 로그 확인)
            ffmpegLogReaderThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getErrorStream()))) {
                    String line;
                    System.out.println("FFmpeg Log Reader Thread started.");
                    while ((line = reader.readLine()) != null) {
                        System.err.println("[FFmpeg]: " + line); // FFmpeg 로그를 표준 오류로 출력
                    }
                } catch (IOException e) {
                    // System.err.println("Error reading FFmpeg stderr: " + e.getMessage());
                }
                System.out.println("FFmpeg Log Reader Thread finished.");
            });
            ffmpegLogReaderThread.setDaemon(true); // 메인 스레드 종료 시 함께 종료
            ffmpegLogReaderThread.start();

            // FFmpeg이 시작되고 리슨 상태가 될 때까지 잠시 대기
            // 더 나은 방법은 FFmpeg 로그에서 "Ready to accept connections" 와 같은 메시지를 확인하는 것입니다.
            System.out.println("Waiting for FFmpeg to initialize RTSP server (approx 2-3 seconds)...");
            Thread.sleep(3000); // 3초 대기 (환경에 따라 조절 필요)

            if (!ffmpegProcess.isAlive()) {
                System.err.println("FFmpeg process terminated unexpectedly after start. Check FFmpeg logs above for errors (e.g., port in use, command error).");
                if (grabberRef.get() != null) grabberRef.get().stop();
                if (canvasRef.get() != null) canvasRef.get().dispose();
                return;
            }
            System.out.println("FFmpeg process started. Assuming RTSP server is listening.");

            // 4. 스트림 정보 서버에 등록
            String actualHostIp = getSuitableLocalIpAddress();
            if (actualHostIp == null || actualHostIp.equals("localhost")) {
                System.err.println("Could not determine a suitable non-loopback local IP for RTSP URL registration. Using 'localhost'. Other devices on LAN may not connect.");
                actualHostIp = "localhost"; // 또는 수동으로 설정된 IP
            }
            String registerableRtspUrl = "rtsp://" + actualHostIp + ":" + RTSP_PORT + RTSP_PATH;
            System.out.println("Registering stream with Spring Boot server. RTSP URL for clients: " + registerableRtspUrl);
            registerStreamWithServer(STREAM_NAME_ON_SERVER, registerableRtspUrl, STREAM_DESCRIPTION_ON_SERVER);


            // 5. 메인 스트리밍 루프
            System.out.println("Streaming frames to FFmpeg... Close the preview window to stop.");
            long frameCount = 0;
            CanvasFrame currentCanvas = canvasRef.get();

            while (streamingActive.get()) {
                if (currentCanvas == null || !currentCanvas.isShowing()) { // 창 상태 재확인
                    System.out.println("Canvas became non-showing. Stopping streaming loop.");
                    streamingActive.set(false); // 루프 중단
                    break;
                }

                Frame capturedFrame = grabber.grab();
                if (capturedFrame == null) {
                    System.out.println("Warning: Null frame grabbed. Possibly end of camera stream or error.");
                    streamingActive.set(false); // 루프 중단
                    break;
                }

                // 캡처된 프레임과 FFmpeg 입력 해상도 일치 여부 확인
                if (capturedFrame.imageWidth != imageWidth || capturedFrame.imageHeight != imageHeight) {
                    System.err.println("Critical Error: Captured frame dimension ("+ capturedFrame.imageWidth + "x" + capturedFrame.imageHeight +
                                       ") mismatch with FFmpeg input expectation (" + imageWidth + "x" + imageHeight +
                                       "). Stopping streaming to prevent corruption.");
                    streamingActive.set(false); // 루프 중단
                    break;
                }

                currentCanvas.showImage(capturedFrame); // 로컬 미리보기

                Mat mat = frameToMatConverter.convert(capturedFrame); // Frame -> Mat
                if (mat != null && mat.data() != null && !mat.empty()) {
                    int bufferSize = mat.cols() * mat.rows() * mat.channels(); // BGR 경우 channels = 3
                    byte[] bgrBytes = new byte[bufferSize];
                    mat.data().get(bgrBytes); // Mat 데이터에서 byte 배열로 복사

                    try {
                        if (ffmpegProcess.isAlive()) {
                            ffmpegStdin.write(bgrBytes); // FFmpeg의 표준 입력으로 바이트 데이터 쓰기
                        } else {
                            System.err.println("FFmpeg process is not alive. Cannot write frame " + frameCount);
                            streamingActive.set(false); // 루프 중단
                            break;
                        }
                    } catch (IOException e) {
                        System.err.println("Error writing frame " + frameCount + " to FFmpeg stdin: " + e.getMessage());
                        if (ffmpegProcess.isAlive()) { // FFmpeg이 살아있는데 파이프가 깨졌다면
                            // System.err.println("FFmpeg is alive but pipe may be broken.");
                        } else { // FFmpeg이 이미 종료되었다면
                            System.err.println("FFmpeg process has terminated. Stopping streaming.");
                        }
                        streamingActive.set(false); // 루프 중단
                        break;
                    }
                    mat.release(); // Mat 리소스 명시적 해제
                }
                frameCount++;
            }
            System.out.println("Exited streaming loop. Total frames processed: " + frameCount);

        } catch (FrameGrabber.Exception e) {
            System.err.println("FrameGrabber.Exception in main try-catch: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Thread interrupted in main try-catch: " + e.getMessage());
            e.printStackTrace();
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
        } catch (InvocationTargetException e) {
            System.err.println("InvocationTargetException (likely from Swing EDT) in main try-catch: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) { // ProcessBuilder.start() 또는 ffmpegStdin 관련 IO 예외
            System.err.println("IOException in main try-catch (likely FFmpeg process related): " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) { // 그 외 모든 예외
            System.err.println("An unexpected generic Exception in main try-catch: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("Executing finally block: Releasing all resources...");

            // 1. FFmpeg 프로세스의 표준 입력 스트림을 먼저 닫아 FFmpeg에 입력 종료를 알림
            if (ffmpegStdin != null) {
                try {
                    System.out.println("Closing FFmpeg stdin...");
                    ffmpegStdin.flush(); // 버퍼 내용 비우기
                    ffmpegStdin.close(); // 스트림 닫기
                    System.out.println("FFmpeg stdin closed.");
                } catch (IOException e) {
                    System.err.println("Error closing FFmpeg stdin: " + e.getMessage());
                }
            }

            // 2. FFmpeg 프로세스 종료 대기 및 강제 종료
            if (ffmpegProcess != null) {
                try {
                    System.out.println("Waiting for FFmpeg process to terminate...");
                    // FFmpeg 로그 리더 스레드가 종료될 시간도 고려
                    if (ffmpegLogReaderThread != null && ffmpegLogReaderThread.isAlive()) {
                        // System.out.println("Waiting for FFmpeg log reader thread to finish...");
                        // ffmpegLogReaderThread.join(1000); // 최대 1초 대기
                    }

                    boolean exited = ffmpegProcess.waitFor(5, TimeUnit.SECONDS); // 최대 5초 대기
                    if (exited) {
                        System.out.println("FFmpeg process terminated with exit code: " + ffmpegProcess.exitValue());
                    } else {
                        System.err.println("FFmpeg process did not terminate in time. Forcibly destroying.");
                        ffmpegProcess.destroyForcibly(); // 강제 종료
                        // 강제 종료 후 잠시 대기
                        // ffmpegProcess.waitFor(1, TimeUnit.SECONDS); 
                        // if (ffmpegProcess.isAlive()) {
                        //     System.err.println("FFmpeg process still alive after destroyForcibly.");
                        // }
                    }
                } catch (InterruptedException e) {
                    System.err.println("Interrupted while waiting for FFmpeg process: " + e.getMessage());
                    if(ffmpegProcess.isAlive()) ffmpegProcess.destroyForcibly(); // 인터럽트 시 강제 종료
                    Thread.currentThread().interrupt(); // 인터럽트 상태 복원
                }
            }

            // 3. CanvasFrame 해제 (Swing EDT에서 안전하게)
            CanvasFrame canvas = canvasRef.get();
            if (canvas != null) {
                SwingUtilities.invokeLater(canvas::dispose);
                System.out.println("CanvasFrame dispose requested from finally block.");
            }

            // 4. FrameGrabber 해제
            FrameGrabber currentGrabber = grabberRef.get();
            if (currentGrabber != null) {
                try {
                    System.out.println("Stopping and releasing grabber in finally block...");
                    currentGrabber.stop();
                    currentGrabber.release();
                    System.out.println("Grabber stopped and released in finally block.");
                } catch (FrameGrabber.Exception e) {
                    System.err.println("Error stopping/releasing grabber in finally block: " + e.getMessage());
                }
            }
            System.out.println("Application finished all cleanup phases.");
        }
    }

    // Stream 등록 메소드 (이전과 동일)
    private static void registerStreamWithServer(String name, String rtspUrl, String description) {
        StreamCreationRequestDto requestDto = new StreamCreationRequestDto(name, rtspUrl, description);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String requestBody = objectMapper.writeValueAsString(requestDto);
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPRING_BOOT_SERVER_URL + "/api/streams"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

            System.out.println("Sending registration request to server: " + requestBody);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Server registration response status code: " + response.statusCode());
            System.out.println("Server registration response body: " + response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Stream successfully registered with the server.");
            } else {
                System.err.println("Failed to register stream. Server responded with error: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error during stream registration with server: " + e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 로컬 IP 주소 찾는 메소드 (이전과 동일)
    private static String getSuitableLocalIpAddress() {
        try {
            List<String> candidateIps = new ArrayList<>();
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(networkInterfaces)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                // 일반적인 유선/무선 인터페이스 이름 선호 (macOS/Linux: en, eth, wlan)
                boolean preferredInterface = ni.getDisplayName().startsWith("en") ||
                                             ni.getDisplayName().startsWith("eth") ||
                                             ni.getDisplayName().startsWith("wlan");

                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                    if (!inetAddress.isLoopbackAddress() &&
                        inetAddress.isSiteLocalAddress() && // 사설 IP 대역
                        inetAddress.getHostAddress().matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) { // IPv4 형식
                        if (preferredInterface) {
                            return inetAddress.getHostAddress(); // 선호 인터페이스의 첫 번째 IP 반환
                        }
                        candidateIps.add(inetAddress.getHostAddress());
                    }
                }
            }
            if (!candidateIps.isEmpty()) {
                return candidateIps.get(0); // 선호 인터페이스 없으면 후보 중 첫 번째 반환
            }
            // 최후의 수단
            return InetAddress.getLocalHost().getHostAddress();
        } catch (SocketException | java.net.UnknownHostException e) {
            System.err.println("Could not determine local IP address: " + e.getMessage());
            return "localhost"; // 실패 시 localhost 반환
        }
    }
}