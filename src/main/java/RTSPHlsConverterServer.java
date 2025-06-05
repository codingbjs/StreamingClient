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
import java.util.Timer; // 메모리 로깅용
import java.util.TimerTask; // 메모리 로깅용
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPHlsConverterServer {

    // --- Configuration ---
    private static final String SOURCE_RTSP_URL = "rtsp://192.168.0.152:8554/live";
    private static final String HLS_OUTPUT_DIRECTORY = "hls_output";
    private static final String HLS_M3U8_NAME = "stream.m3u8";
    private static final int HTTP_SERVER_PORT = 8989;
    private static final String HLS_SEGMENT_DURATION = "4";
    private static final String HLS_LIST_SIZE = "5";
    private static final long MEMORY_LOG_INTERVAL_MS = 60000; // 1분에 한 번 메모리 로깅

    private static FFmpegFrameGrabber grabber;
    private static FFmpegFrameRecorder recorder;
    private static HttpServer httpServer;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static ExecutorService processingExecutor;
    private static Timer memoryLogTimer;

    public static void main(String[] args) {
        System.out.println("현재 작업 디렉토리 (CWD): " + Paths.get("").toAbsolutePath().toString());
        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_DEBUG);

        File hlsDir = new File(HLS_OUTPUT_DIRECTORY);
        if (!hlsDir.exists()) {
            if (!hlsDir.mkdirs()) {
                System.err.println("Failed to create HLS output directory: " + hlsDir.getAbsolutePath());
                return;
            }
        }
        System.out.println("HLS output directory: " + hlsDir.getAbsolutePath());

        // 주기적 메모리 로깅 시작
        startMemoryLogging();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received. Stopping services...");
            running.set(false);
            stopMemoryLogging();
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
            startHttpServer(hlsDir.getAbsolutePath(), HTTP_SERVER_PORT);
            String hlsM3u8AbsolutePath = hlsDir.getAbsolutePath() + File.separator + HLS_M3U8_NAME;
            System.out.println("Calculated absolute M3U8 path for recorder: " + hlsM3u8AbsolutePath);

            processingExecutor.submit(() -> {
                try {
                    startRtspToHlsConversion(SOURCE_RTSP_URL, hlsM3u8AbsolutePath);
                } catch (Exception e) {
                    System.err.println("FATAL: RTSP to HLS conversion thread failed: " + e.getMessage());
                    e.printStackTrace();
                    running.set(false); // 전체 애플리케이션 중단 유도
                }
            });

            System.out.println("RTSP to HLS Converter Server started."); /* ... (기존 로그 메시지) ... */
            System.out.println("Press Ctrl+C to stop the server.");

            while (running.get()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) { // IOException, InterruptedException 등 포괄
            System.err.println("Main thread error: " + e.getMessage());
            e.printStackTrace();
            running.set(false); // 예외 발생 시에도 종료 플래그 설정
        } finally {
            System.out.println("Main thread is ending. Initiating final cleanup...");
            if (running.get()) { running.set(false); } // 만약을 위해
            // Shutdown hook에서 대부분 처리하지만, 여기서도 호출해볼 수 있음 (중복 호출 방지 로직 필요)
            // stopStreamingInternal();
            // stopHttpServerInternal();
            // ...
            System.out.println("Exiting main method.");
        }
    }

    private static void startRtspToHlsConversion(String rtspUrl, String hlsM3u8AbsolutePath) throws FrameGrabber.Exception, FrameRecorder.Exception {
        System.out.println("Initializing RTSP grabber for: " + rtspUrl);
        grabber = new FFmpegFrameGrabber(rtspUrl);
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("stimeout", "10000000"); // 연결 타임아웃 10초로 증가
        grabber.start();
        System.out.println("RTSP Grabber started. Source Info:");
        System.out.println("  Resolution: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
        System.out.println("  Frame Rate: " + grabber.getFrameRate());
        System.out.println("  Video Codec: " + avcodec.avcodec_get_name(grabber.getVideoCodec()).getString() + " (ID: " + grabber.getVideoCodec() + ")");
        System.out.println("  Pixel Format: " + avutil.av_get_pix_fmt_name(grabber.getPixelFormat()).getString());
        System.out.println("  Audio Channels: " + grabber.getAudioChannels());
        if (grabber.getAudioChannels() > 0) {
            System.out.println("  Audio Codec: " + avcodec.avcodec_get_name(grabber.getAudioCodec()).getString() + " (ID: " + grabber.getAudioCodec() + ")");
            System.out.println("  Sample Rate: " + grabber.getSampleRate());
        }

        System.out.println("Initializing HLS recorder. Output M3U8: " + hlsM3u8AbsolutePath);
        recorder = new FFmpegFrameRecorder(hlsM3u8AbsolutePath, grabber.getImageWidth(), grabber.getImageHeight(), grabber.getAudioChannels());

        recorder.setFormat("hls");

        // --- 코덱 설정 시작 ---
        boolean reencodeVideo = false;
        boolean reencodeAudio = false;

        // 비디오 코덱: H.264가 아니거나, 문제가 있는 경우 재인코딩 (HLS는 H.264를 선호)
        if (grabber.getVideoCodec() == avcodec.AV_CODEC_ID_H264) {
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264); // 코덱 복사 시도
            // recorder.setVideoCodecName("h264_videotoolbox"); // macOS에서 하드웨어 가속 시도 (옵션)
            System.out.println("Attempting to copy video codec (H.264) from source.");
        } else {
            reencodeVideo = true;
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P); // H.264 재인코딩 시 표준 픽셀 포맷
            if (grabber.getVideoBitrate() > 0) recorder.setVideoBitrate(grabber.getVideoBitrate()); else recorder.setVideoBitrate(2000000); // 2Mbps
            System.out.println("Source video codec is not H.264 (" + avcodec.avcodec_get_name(grabber.getVideoCodec()).getString() + "). Re-encoding to H.264.");
        }

        if (grabber.getFrameRate() > 0 && grabber.getFrameRate() < 200) {
            recorder.setFrameRate(grabber.getFrameRate());
        } else if (reencodeVideo) { // 재인코딩 시에는 프레임률 명시
            recorder.setFrameRate(30);
        }


        // 오디오 코덱: AAC가 아니거나, 문제가 있는 경우 재인코딩 (HLS는 AAC를 선호)
        if (grabber.getAudioChannels() > 0) {
            if (grabber.getAudioCodec() == avcodec.AV_CODEC_ID_AAC) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC); // 코덱 복사 시도
                System.out.println("Attempting to copy audio codec (AAC) from source.");
            } else {
                reencodeAudio = true;
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                if (grabber.getAudioBitrate() > 0) recorder.setAudioBitrate(grabber.getAudioBitrate()); else recorder.setAudioBitrate(128000); // 128kbps
                System.out.println("Source audio codec is not AAC (" + avcodec.avcodec_get_name(grabber.getAudioCodec()).getString() + "). Re-encoding to AAC.");
            }
            if (grabber.getSampleRate() > 0) recorder.setSampleRate(grabber.getSampleRate());
            recorder.setAudioChannels(grabber.getAudioChannels());
        } else {
            recorder.setAudioChannels(0); // 오디오 없음
        }
        // --- 코덱 설정 끝 ---

        recorder.setOption("hls_time", HLS_SEGMENT_DURATION);
        recorder.setOption("hls_list_size", HLS_LIST_SIZE);
        recorder.setOption("hls_flags", "delete_segments+omit_endlist");
        String segmentPathPattern = Paths.get(hlsM3u8AbsolutePath).getParent().resolve("segment%05d.ts").toString();
        System.out.println("HLS segment path pattern for recorder: " + segmentPathPattern);
        recorder.setOption("hls_segment_filename", segmentPathPattern);

        System.out.println("Starting HLS recorder (FFmpeg)...");
        recorder.start(); // 여기서 네이티브 리소스 할당 시작
        System.out.println("HLS recorder (FFmpeg) started. Converting stream...");

        Frame frame;
        long frameCount = 0;
        while (running.get() && (frame = grabber.grab()) != null) {
            if (frame.image == null && frame.samples == null) { // 빈 프레임 스킵
                continue;
            }
            try {
                // HLS는 일반적으로 레코더가 타임스탬프를 관리. setTimestamp 불필요할 수 있음.
                // 그러나 grab()이 반환하는 프레임에 이미 타임스탬프가 있다면 사용하는 것이 좋음.
                if (frame.timestamp != 0) { // FFmpegFrameGrabber는 보통 timestamp를 채워줌
                    recorder.setTimestamp(frame.timestamp);
                }
                recorder.record(frame);
                frameCount++;
            } catch (FrameRecorder.Exception e) {
                // System.err.println("Frame " + frameCount + ": Error recording frame for HLS: " + e.getMessage());
            }
            // 수동으로 Frame 객체 내 네이티브 버퍼 해제 (주의해서 사용, recorder.record가 소유권을 가져갈 수 있음)
            // JavaCV의 Frame은 네이티브 메모리를 가리키므로, record 후 명시적 해제가 필요없을 수도 있지만,
            // 매우 긴 실행에서 누수를 의심한다면 고려. 단, record가 비동기 처리 시 문제될 수 있음.
            // 현재로서는 record()가 프레임 처리를 완료한다고 가정.
        }
        System.out.println("Exiting HLS recording loop. Total frames processed: " + frameCount + " (running flag: " + running.get() + ").");
    }

    private static void startHttpServer(String hlsOutputBasePath, int port) throws IOException {
        // HTTP 서버 시작 로직은 이전과 동일
        Path hlsPath = Paths.get(hlsOutputBasePath).toAbsolutePath();
        System.out.println("HTTP server will serve files from absolute path: " + hlsPath);
        if (!Files.exists(hlsPath)) {
            if (!new File(hlsOutputBasePath).mkdirs()) {
                throw new IOException("Could not create HLS directory for HTTP server: " + hlsOutputBasePath);
            }
            System.out.println("Created HLS directory for HTTP server: " + hlsOutputBasePath);
        }
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", new SimpleFileHttpHandler(hlsPath.toString()));
        httpServer.setExecutor(Executors.newFixedThreadPool(10));
        httpServer.start();
        System.out.println("HTTP server started on port " + port + ".");
    }

    private static synchronized void stopStreamingInternal() {
        System.out.println("Internal: Attempting to stop HLS recorder...");
        if (recorder != null) {
            try {
                recorder.stop();    // 내부 버퍼 플러시 및 파일 마무리
                recorder.release(); // 네이티브 리소스 해제
                System.out.println("Internal: HLS recorder stopped and released.");
            } catch (FrameRecorder.Exception e) {
                System.err.println("Internal: Error stopping HLS recorder: " + e.getMessage());
            } finally {
                recorder = null; // GC 대상이 되도록 명시적 null 할당
            }
        }

        System.out.println("Internal: Attempting to stop RTSP grabber...");
        if (grabber != null) {
            try {
                grabber.stop();     // 그래버 중지
                grabber.release();  // 네이티브 리소스 해제
                System.out.println("Internal: RTSP grabber stopped and released.");
            } catch (FrameGrabber.Exception e) {
                System.err.println("Internal: Error stopping RTSP grabber: " + e.getMessage());
            } finally {
                grabber = null; // GC 대상이 되도록 명시적 null 할당
            }
        }
    }

    private static synchronized void stopHttpServerInternal() {
        if (httpServer != null) {
            System.out.println("Internal: Stopping HTTP server...");
            httpServer.stop(0); // 즉시 종료
            System.out.println("Internal: HTTP server stopped.");
            httpServer = null;
        }
    }

    private static void startMemoryLogging() {
        memoryLogTimer = new Timer("MemoryLogTimer", true); // 데몬 스레드로 설정
        memoryLogTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Runtime rt = Runtime.getRuntime();
                long totalMem = rt.totalMemory();
                long freeMem = rt.freeMemory();
                long usedMem = totalMem - freeMem;
                long maxMem = rt.maxMemory();
                System.out.printf("[Memory Usage] Used: %d MB, Free: %d MB, Total: %d MB, Max: %d MB%n",
                        usedMem / (1024 * 1024),
                        freeMem / (1024 * 1024),
                        totalMem / (1024 * 1024),
                        maxMem / (1024 * 1024));
            }
        }, MEMORY_LOG_INTERVAL_MS, MEMORY_LOG_INTERVAL_MS);
        System.out.println("Memory logging started. Interval: " + MEMORY_LOG_INTERVAL_MS / 1000 + " seconds.");
    }

    private static void stopMemoryLogging() {
        if (memoryLogTimer != null) {
            memoryLogTimer.cancel();
            memoryLogTimer.purge();
            System.out.println("Memory logging stopped.");
        }
    }

    // SimpleFileHttpHandler 클래스는 이전과 동일하게 유지
    static class SimpleFileHttpHandler implements HttpHandler { /* ... 이전 디버깅 로그 포함된 코드 ... */
        private final Path basePath;
        public SimpleFileHttpHandler(String basePathString) {
            this.basePath = Paths.get(basePathString).toAbsolutePath();
            System.out.println("[HTTP Handler] Initialized. Serving files from base path: " + this.basePath);
        }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            String relativePath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
            if (relativePath.isEmpty() || relativePath.equals("/")) {
                relativePath = HLS_M3U8_NAME;
            }
            File file = new File(basePath.toFile(), relativePath);
            // 디버깅 로그 (이전 코드에서 가져옴)
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
                if (relativePath.endsWith(".m3u8")) contentType = "application/vnd.apple.mpegurl";
                else if (relativePath.endsWith(".ts")) contentType = "video/MP2T";
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody(); FileInputStream fs = new FileInputStream(file)) {
                    final byte[] buffer = new byte[4096]; int bytesRead;
                    while ((bytesRead = fs.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
                }
            } else {
                String responseBody = "404 (Not Found)\nRequested file: " + file.getAbsolutePath() + "\nExists: " + file.exists() + "\nIs Directory: " + file.isDirectory() + "\n";
                exchange.sendResponseHeaders(404, responseBody.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(responseBody.getBytes()); }
            }
        }
    }
}