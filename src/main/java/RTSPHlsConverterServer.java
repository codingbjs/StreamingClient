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
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPHlsConverterServer {

    // --- Configuration ---
    // 입력받을 원본 RTSP 스트림 주소
    private static final String SOURCE_RTSP_URL = "rtsp://localhost:8554/live"; // 예: "rtsp://192.168.0.100:554/cam/realmonitor?channel=1&subtype=0"

    // HLS 파일(m3u8, ts)들이 저장될 로컬 디렉토리 경로
    private static final String HLS_OUTPUT_DIRECTORY = "hls_output";
    // 생성될 메인 M3U8 재생목록 파일 이름
    private static final String HLS_M3U8_NAME = "stream.m3u8";

    // 내장 HTTP 서버가 리슨할 포트
    private static final int HTTP_SERVER_PORT = 8888;
    // HLS 세그먼트 하나의 길이 (초 단위)
    private static final String HLS_SEGMENT_DURATION = "4"; // 예: 4초
    // M3U8 재생목록에 유지할 최대 세그먼트 파일 수 (0이면 모든 세그먼트 유지)
    private static final String HLS_LIST_SIZE = "5"; // 예: 최신 5개 유지
    // --- End Configuration ---

    private static FFmpegFrameGrabber grabber;
    private static FFmpegFrameRecorder recorder;
    private static HttpServer httpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static ExecutorService processingExecutor;


    public static void main(String[] args) {
        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG); // 상세 로그 확인

        // HLS 출력 디렉토리 생성
        File hlsDir = new File(HLS_OUTPUT_DIRECTORY);
        if (!hlsDir.exists()) {
            if (hlsDir.mkdirs()) {
                System.out.println("HLS output directory created: " + hlsDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create HLS output directory: " + hlsDir.getAbsolutePath());
                return;
            }
        } else {
            System.out.println("HLS output directory already exists: " + hlsDir.getAbsolutePath());
        }
        // 기존 HLS 파일 정리 (선택 사항)
        // clearHlsOutputDirectory(hlsDir);


        // 종료 훅(Shutdown Hook) 등록: Ctrl+C 등으로 종료 시 리소스 정리
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received. Stopping services...");
            running.set(false);
            stopStreaming();
            stopHttpServer();
            if (processingExecutor != null && !processingExecutor.isShutdown()) {
                processingExecutor.shutdownNow();
            }
            System.out.println("All services stopped. Exiting.");
        }));

        processingExecutor = Executors.newSingleThreadExecutor();

        try {
            // 1. HTTP 서버 시작 (HLS 파일 서비스용)
            startHttpServer(HLS_OUTPUT_DIRECTORY, HTTP_SERVER_PORT);

            // 2. RTSP 수신 및 HLS 변환 시작 (별도 스레드에서 실행 고려 가능)
            processingExecutor.submit(() -> {
                try {
                    startRtspToHlsConversion(SOURCE_RTSP_URL, hlsDir.getAbsolutePath() + File.separator + HLS_M3U8_NAME);
                } catch (Exception e) {
                    System.err.println("Error in RTSP to HLS conversion thread: " + e.getMessage());
                    e.printStackTrace();
                    running.set(false); // 오류 발생 시 주 루프 중단 유도
                }
            });

            System.out.println("RTSP to HLS Converter Server started.");
            System.out.println("Source RTSP URL: " + SOURCE_RTSP_URL);
            System.out.println("HLS files will be served from: " + hlsDir.getAbsolutePath());
            System.out.println("Access HLS stream at: http://<YOUR_IP_ADDRESS>:" + HTTP_SERVER_PORT + "/" + HLS_M3U8_NAME);
            System.out.println("Press Ctrl+C to stop.");

            // 메인 스레드는 running 플래그를 확인하며 대기 (또는 다른 작업 수행)
            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (IOException e) {
            System.err.println("Could not start HTTP server: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            // 실제 종료는 Shutdown Hook에서 처리하지만, 만약을 대비
            if (running.get()) { // running이 true인데 여기까지 왔다면 예외로 인한 종료
                running.set(false);
                stopStreaming();
                stopHttpServer();
                if (processingExecutor != null && !processingExecutor.isShutdown()) {
                    processingExecutor.shutdownNow();
                }
            }
        }
    }

    private static void startRtspToHlsConversion(String rtspUrl, String hlsM3u8Path) throws FrameGrabber.Exception, FrameRecorder.Exception {
        System.out.println("Initializing RTSP grabber for: " + rtspUrl);
        grabber = new FFmpegFrameGrabber(rtspUrl);
        grabber.setOption("rtsp_transport", "tcp"); // TCP 사용 권장
        grabber.setOption("stimeout", "5000000");   // 5초 연결 타임아웃
        grabber.start(); // RTSP 스트림 연결 시작
        System.out.println("RTSP Grabber started. Resolution: " + grabber.getImageWidth() + "x" + grabber.getImageHeight() +
                           ", Audio Channels: " + grabber.getAudioChannels());

        System.out.println("Initializing HLS recorder. Output: " + hlsM3u8Path);
        recorder = new FFmpegFrameRecorder(hlsM3u8Path, grabber.getImageWidth(), grabber.getImageHeight(), grabber.getAudioChannels());

        recorder.setFormat("hls");
        // 코덱 설정: 원본 코덱을 그대로 사용하거나 특정 코덱으로 재인코딩
        // 원본 코덱 사용 (스트림 복사 - re-muxing only, less CPU intensive)
        if (grabber.getVideoCodec() != avcodec.AV_CODEC_ID_NONE) {
            recorder.setVideoCodec(grabber.getVideoCodec());
        } else { // 원본 비디오 코덱 정보가 없으면 H.264로 재인코딩 시도
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P); // H.264 호환
        }
        if (grabber.getAudioChannels() > 0 && grabber.getAudioCodec() != avcodec.AV_CODEC_ID_NONE) {
            recorder.setAudioCodec(grabber.getAudioCodec());
        } else if (grabber.getAudioChannels() > 0) { // 오디오 채널은 있는데 코덱 정보가 없으면 AAC로 재인코딩 시도
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
        }

        // 프레임률 및 비트레이트는 원본을 따르도록 시도 (재인코딩 시 명시적 설정 필요)
        if (grabber.getFrameRate() > 0) recorder.setFrameRate(grabber.getFrameRate());
        if (grabber.getVideoBitrate() > 0) recorder.setVideoBitrate(grabber.getVideoBitrate());
        if (grabber.getAudioChannels() > 0 && grabber.getAudioBitrate() > 0) recorder.setAudioBitrate(grabber.getAudioBitrate());


        // HLS 옵션 설정
        recorder.setOption("hls_time", HLS_SEGMENT_DURATION);         // 각 세그먼트 파일의 길이 (초)
        recorder.setOption("hls_list_size", HLS_LIST_SIZE);           // m3u8 재생목록에 유지할 최대 세그먼트 수
        recorder.setOption("hls_flags", "delete_segments+omit_endlist"); // 오래된 세그먼트 삭제 + 라이브 스트림을 위해 endlist 생략
        // 세그먼트 파일 이름 형식 (m3u8 파일과 같은 디렉토리에 생성됨)
        recorder.setOption("hls_segment_filename", Paths.get(hlsM3u8Path).getParent().resolve("segment%05d.ts").toString());


        System.out.println("Starting HLS recorder...");
        recorder.start(); // HLS 파일 생성 시작
        System.out.println("HLS recorder started. Converting stream...");

        Frame frame;
        while (running.get() && (frame = grabber.grab()) != null) { // 실행 중이고 프레임이 있으면 계속
            try {
                recorder.record(frame); // 프레임을 HLS로 기록
            } catch (FrameRecorder.Exception e) {
                System.err.println("Error recording frame for HLS: " + e.getMessage());
                // 특정 프레임 오류 시 계속 진행할지, 중단할지 결정 필요
            }
        }
        System.out.println("Exiting HLS recording loop.");
    }

    private static void startHttpServer(String basePath, int port) throws IOException {
        Path hlsPath = Paths.get(basePath);
        if (!Files.exists(hlsPath) || !Files.isDirectory(hlsPath)) {
            System.err.println("HLS base path does not exist or is not a directory: " + basePath);
            // 여기서 디렉토리를 생성하거나, 오류를 던져야 합니다. main에서 이미 생성하므로 여기선 확인만.
            if (!new File(basePath).mkdirs()) {
                throw new IOException("Could not create HLS directory: " + basePath);
            }
        }

        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        // HLS 파일들을 서비스할 컨텍스트 경로 (예: "/hls" 또는 루트 "/")
        // 여기서는 루트 컨텍스트에 핸들러를 등록하여 http://localhost:8888/stream.m3u8 형태로 접근
        httpServer.createContext("/", new SimpleFileHttpHandler(basePath));
        httpServer.setExecutor(Executors.newFixedThreadPool(10)); // 스레드 풀 설정
        httpServer.start();
        System.out.println("HTTP server started on port " + port + ", serving files from " + basePath);
    }

    private static void stopStreaming() {
        System.out.println("Attempting to stop HLS recorder...");
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
                System.out.println("HLS recorder stopped and released.");
            } catch (FrameRecorder.Exception e) {
                System.err.println("Error stopping HLS recorder: " + e.getMessage());
            }
            recorder = null;
        }

        System.out.println("Attempting to stop RTSP grabber...");
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
                System.out.println("RTSP grabber stopped and released.");
            } catch (FrameGrabber.Exception e) {
                System.err.println("Error stopping RTSP grabber: " + e.getMessage());
            }
            grabber = null;
        }
    }

    private static void stopHttpServer() {
        if (httpServer != null) {
            System.out.println("Stopping HTTP server...");
            httpServer.stop(0); // 0초 내에 즉시 종료
            System.out.println("HTTP server stopped.");
            httpServer = null;
        }
    }

    // HLS 출력 디렉토리 초기화 (선택적)
    private static void clearHlsOutputDirectory(File hlsDir) {
        if (hlsDir.exists() && hlsDir.isDirectory()) {
            System.out.println("Clearing HLS output directory: " + hlsDir.getAbsolutePath());
            for (File file : hlsDir.listFiles()) {
                if (!file.isDirectory()) { // 하위 디렉토리는 삭제 안 함
                    file.delete();
                }
            }
        }
    }


    // 간단한 파일 서빙 HTTP 핸들러
    static class SimpleFileHttpHandler implements HttpHandler {
        private final Path basePath;

        public SimpleFileHttpHandler(String basePath) {
            this.basePath = Paths.get(basePath).toAbsolutePath();
            System.out.println("HTTP Handler serving files from: " + this.basePath);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            // 기본 경로가 "/" 이므로, requestPath는 "/stream.m3u8" 또는 "/segment001.ts" 등이 됩니다.
            // requestPath가 "/"로 시작하면 첫 번째 문자를 제거하여 basePath에 상대적인 경로로 만듭니다.
            String relativePath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

            File file = new File(basePath.toFile(), relativePath);

            // System.out.println("HTTP request for: " + requestPath + " -> Trying to serve file: " + file.getAbsolutePath());


            if (file.exists() && !file.isDirectory() && file.getCanonicalPath().startsWith(basePath.toString())) { // 보안: 경로 조작 방지
                String contentType = "application/octet-stream"; // 기본 콘텐츠 타입
                if (requestPath.endsWith(".m3u8")) {
                    contentType = "application/vnd.apple.mpegurl"; // 또는 "application/x-mpegURL"
                } else if (requestPath.endsWith(".ts")) {
                    contentType = "video/MP2T";
                }

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // CORS 허용 (필요시)
                exchange.sendResponseHeaders(200, file.length()); // HTTP 200 OK, 파일 길이

                try (OutputStream os = exchange.getResponseBody();
                     FileInputStream fs = new FileInputStream(file)) {
                    final byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fs.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                // System.err.println("File not found or invalid path: " + file.getAbsolutePath());
                String response = "404 (Not Found)\n";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }
}