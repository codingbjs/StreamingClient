import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPHlsConverterServerback {

    // --- Configuration ---
    // 입력받을 원본 RTSP 스트림 주소 (실제 주소로 변경 필요)
    private static final String SOURCE_RTSP_URL = "rtsp://192.168.0.152:8554/live";

    // HLS 파일(m3u8, ts)들이 저장될 로컬 디렉토리 경로
    private static final String HLS_OUTPUT_DIRECTORY = "hls_output";
    // 생성될 메인 M3U8 재생목록 파일 이름
    private static final String HLS_M3U8_NAME = "stream.m3u8";

    // 내장 HTTP 서버가 리슨할 포트
    private static final int HTTP_SERVER_PORT = 8989; // 이전 "Address already in use" 오류로 인해 8889로 변경 가정
    // HLS 세그먼트 하나의 길이 (초 단위)
    private static final String HLS_SEGMENT_DURATION = "4";
    // M3U8 재생목록에 유지할 최대 세그먼트 파일 수
    private static final String HLS_LIST_SIZE = "5";
    // --- End Configuration ---

    private static FFmpegFrameGrabber grabber;
    private static FFmpegFrameRecorder recorder;
    private static HttpServer httpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static ExecutorService processingExecutor;


    public static void main(String[] args) {
        // 0. 현재 작업 디렉토리 출력 (가장 먼저 확인!)
        System.out.println("현재 작업 디렉토리 (CWD): " + Paths.get("").toAbsolutePath().toString());

        FFmpegLogCallback.set(); // FFmpeg 상세 로그 활성화
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG); // FFmpeg 로그 레벨 DEBUG로 설정

        // HLS 출력 디렉토리 준비
        File hlsDir = new File(HLS_OUTPUT_DIRECTORY);
        if (!hlsDir.exists()) {
            if (hlsDir.mkdirs()) {
                System.out.println("HLS output directory created: " + hlsDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create HLS output directory: " + hlsDir.getAbsolutePath() + ". Check permissions or path.");
                return;
            }
        } else {
            System.out.println("HLS output directory already exists: " + hlsDir.getAbsolutePath());
        }
        // clearHlsOutputDirectory(hlsDir); // 필요시 이전 실행 파일 정리


        // 종료 훅 등록
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received. Stopping services...");
            running.set(false); // 루프 중단 신호
            // Shutdown hook에서는 리소스 정리만 빠르게 수행
            stopStreamingInternal();
            stopHttpServerInternal();
            if (processingExecutor != null && !processingExecutor.isShutdown()) {
                try {
                    processingExecutor.shutdown();
                    if (!processingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        processingExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    processingExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("All services signaled to stop. Exiting.");
        }));

        processingExecutor = Executors.newSingleThreadExecutor();

        try {
            // 1. HTTP 서버 시작
            startHttpServer(hlsDir.getAbsolutePath(), HTTP_SERVER_PORT); // 절대 경로 전달

            // 2. RTSP 수신 및 HLS 변환 시작 (별도 스레드)
            String hlsM3u8AbsolutePath = hlsDir.getAbsolutePath() + File.separator + HLS_M3U8_NAME;
            System.out.println("Calculated absolute M3U8 path for recorder: " + hlsM3u8AbsolutePath);

            processingExecutor.submit(() -> {
                try {
                    startRtspToHlsConversion(SOURCE_RTSP_URL, hlsM3u8AbsolutePath);
                } catch (Exception e) {
                    System.err.println("Error in RTSP to HLS conversion thread: " + e.getMessage());
                    e.printStackTrace();
                    running.set(false);
                }
            });

            System.out.println("RTSP to HLS Converter Server started successfully.");
            System.out.println(" - Source RTSP URL: " + SOURCE_RTSP_URL);
            System.out.println(" - HLS files will be generated in: " + hlsDir.getAbsolutePath());
            System.out.println(" - Access HLS stream at: http://<YOUR_IP_ADDRESS>:" + HTTP_SERVER_PORT + "/" + HLS_M3U8_NAME);
            System.out.println("   (Replace <YOUR_IP_ADDRESS> with localhost or your PC's actual IP)");
            System.out.println("Press Ctrl+C to stop the server.");

            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (IOException e) {
            System.err.println("Could not start HTTP server: " + e.getMessage() + ". Check if port " + HTTP_SERVER_PORT + " is already in use.");
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // Shutdown hook에서 주된 정리를 하지만, 예외 발생 시 여기서도 시도
            System.out.println("Main thread is ending. Initiating final cleanup if not already done by shutdown hook...");
            if (running.get()) { // running이 true인데 여기까지 왔다면 예외로 인한 종료
                running.set(false); // 명시적으로 중단 신호
            }
            // Shutdown hook이 모든 정리를 담당하도록 유도하거나, 여기서도 호출
            // stopStreamingInternal();
            // stopHttpServerInternal();
            // if (processingExecutor != null && !processingExecutor.isShutdown()) {
            //    processingExecutor.shutdownNow();
            // }
            System.out.println("Exiting main method.");
        }
    }

    private static void startRtspToHlsConversion(String rtspUrl, String hlsM3u8AbsolutePath) throws FrameGrabber.Exception, FrameRecorder.Exception {
        System.out.println("Initializing RTSP grabber for: " + rtspUrl);
        grabber = new FFmpegFrameGrabber(rtspUrl);
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("stimeout", "5000000");
        grabber.start();
        System.out.println("RTSP Grabber started. Resolution: " + grabber.getImageWidth() + "x" + grabber.getImageHeight() +
                           ", Audio Channels: " + grabber.getAudioChannels());

        System.out.println("Initializing HLS recorder. Output M3U8: " + hlsM3u8AbsolutePath);
        recorder = new FFmpegFrameRecorder(hlsM3u8AbsolutePath, grabber.getImageWidth(), grabber.getImageHeight(), grabber.getAudioChannels());

        recorder.setFormat("hls");
        // 코덱 설정 (원본 우선, 없으면 H.264/AAC로 재인코딩 시도)
        if (grabber.getVideoCodec() != avcodec.AV_CODEC_ID_NONE && grabber.getVideoCodec() != 0) {
            recorder.setVideoCodec(grabber.getVideoCodec());
            System.out.println("Using source video codec: " + grabber.getVideoCodecName());
        } else {
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            System.out.println("Source video codec unknown or none, attempting H.264 encoding.");
        }
        if (grabber.getAudioChannels() > 0) {
            if (grabber.getAudioCodec() != avcodec.AV_CODEC_ID_NONE && grabber.getAudioCodec() != 0) {
                recorder.setAudioCodec(grabber.getAudioCodec());
                System.out.println("Using source audio codec: " + grabber.getAudioCodecName());
            } else {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                System.out.println("Source audio codec unknown or none, attempting AAC encoding.");
            }
            if (grabber.getSampleRate() > 0) recorder.setSampleRate(grabber.getSampleRate());
            if (grabber.getAudioBitrate() > 0) recorder.setAudioBitrate(grabber.getAudioBitrate());
        } else {
            System.out.println("No audio channels detected in source stream.");
        }

        if (grabber.getFrameRate() > 0 && grabber.getFrameRate() < 200) recorder.setFrameRate(grabber.getFrameRate()); // 비정상적인 framerate 방지
        if (grabber.getVideoBitrate() > 0) recorder.setVideoBitrate(grabber.getVideoBitrate());

        // HLS 옵션 설정
        recorder.setOption("hls_time", HLS_SEGMENT_DURATION);
        recorder.setOption("hls_list_size", HLS_LIST_SIZE);
        recorder.setOption("hls_flags", "delete_segments+omit_endlist");
        // 세그먼트 파일 이름 및 경로 설정 (M3U8 파일과 같은 디렉토리에 생성)
        String segmentPathPattern = Paths.get(hlsM3u8AbsolutePath).getParent().resolve("segment%05d.ts").toString();
        System.out.println("HLS segment path pattern for recorder: " + segmentPathPattern);
        recorder.setOption("hls_segment_filename", segmentPathPattern);

        System.out.println("Starting HLS recorder (FFmpeg)...");
        recorder.start();
        System.out.println("HLS recorder (FFmpeg) started. Converting stream...");

        Frame frame;
        while (running.get() && (frame = grabber.grab()) != null) {
            try {
                recorder.record(frame);
            } catch (FrameRecorder.Exception e) {
                // System.err.println("Error recording frame for HLS: " + e.getMessage());
            }
        }
        System.out.println("Exiting HLS recording loop (running flag: " + running.get() + ").");
    }

    private static void startHttpServer(String hlsOutputBasePath, int port) throws IOException {
        Path hlsPath = Paths.get(hlsOutputBasePath).toAbsolutePath(); // 절대 경로 사용 확실히
        System.out.println("HTTP server will serve files from absolute path: " + hlsPath);
        if (!Files.exists(hlsPath) || !Files.isDirectory(hlsPath)) {
            System.err.println("HLS base path for HTTP server does not exist or is not a directory: " + hlsPath);
            if (!new File(hlsOutputBasePath).mkdirs()) { // 디렉토리 생성 시도
                throw new IOException("Could not create HLS directory for HTTP server: " + hlsOutputBasePath);
            }
            System.out.println("Created HLS directory for HTTP server: " + hlsOutputBasePath);
        }

        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", new SimpleFileHttpHandler(hlsPath.toString())); // 핸들러에 절대경로 전달
        httpServer.setExecutor(Executors.newFixedThreadPool(10));
        httpServer.start();
        System.out.println("HTTP server started on port " + port + ".");
    }

    // Shutdown hook 또는 finally에서 호출될 내부 정리 메소드
    private static synchronized void stopStreamingInternal() {
        System.out.println("Internal: Attempting to stop HLS recorder...");
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
                System.out.println("Internal: HLS recorder stopped and released.");
            } catch (FrameRecorder.Exception e) {
                System.err.println("Internal: Error stopping HLS recorder: " + e.getMessage());
            }
            recorder = null;
        }

        System.out.println("Internal: Attempting to stop RTSP grabber...");
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
                System.out.println("Internal: RTSP grabber stopped and released.");
            } catch (FrameGrabber.Exception e) {
                System.err.println("Internal: Error stopping RTSP grabber: " + e.getMessage());
            }
            grabber = null;
        }
    }

    private static synchronized void stopHttpServerInternal() {
        if (httpServer != null) {
            System.out.println("Internal: Stopping HTTP server...");
            httpServer.stop(0);
            System.out.println("Internal: HTTP server stopped.");
            httpServer = null;
        }
    }

    static class SimpleFileHttpHandler implements HttpHandler {
        private final Path basePath;

        public SimpleFileHttpHandler(String basePathString) {
            this.basePath = Paths.get(basePathString).toAbsolutePath(); // 생성자에서 절대경로로 저장
            System.out.println("[HTTP Handler] Initialized. Serving files from base path: " + this.basePath);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            String relativePath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
            if (relativePath.isEmpty() || relativePath.equals("/")) { // 루트 요청 시 기본 파일 (예: index.html 또는 m3u8)
                relativePath = HLS_M3U8_NAME; // 기본으로 m3u8 파일 요청으로 간주
            }

            File file = new File(basePath.toFile(), relativePath);

            // 디버깅 로그
            System.out.println("-----------------------------------------------------");
            System.out.println("[HTTP Handler] Request URI: " + exchange.getRequestURI());
            System.out.println("[HTTP Handler] Calculated requestPath: " + requestPath);
            System.out.println("[HTTP Handler] Calculated relativePath: " + relativePath);
            System.out.println("[HTTP Handler] Base Path for serving: " + this.basePath);
            System.out.println("[HTTP Handler] Trying to serve absolute file path: " + file.getAbsolutePath());
            System.out.println("[HTTP Handler] Does file exist? " + file.exists());
            System.out.println("[HTTP Handler] Is it a file (not directory)? " + (file.exists() && !file.isDirectory()));
            System.out.println("-----------------------------------------------------");

            if (file.exists() && !file.isDirectory() && file.getCanonicalPath().startsWith(this.basePath.toString())) {
                String contentType = "application/octet-stream";
                if (relativePath.endsWith(".m3u8")) {
                    contentType = "application/vnd.apple.mpegurl";
                } else if (relativePath.endsWith(".ts")) {
                    contentType = "video/MP2T";
                }

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, file.length());

                try (OutputStream os = exchange.getResponseBody();
                     FileInputStream fs = new FileInputStream(file)) {
                    final byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fs.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                String responseBody = "404 (Not Found)\nRequested file: " + file.getAbsolutePath() + "\nExists: " + file.exists() + "\nIs Directory: " + file.isDirectory() + "\n";
                exchange.sendResponseHeaders(404, responseBody.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBody.getBytes());
                }
            }
        }
    }
}