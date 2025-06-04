import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber; // FrameGrabber.Exception을 위해 필요

import javax.swing.JFrame; // JFrame.EXIT_ON_CLOSE 사용을 위해 필요

public class RTSPViewer {

    public static void main(String[] args) {
        // 1. RTSP 스트림 URL 설정
        // "<YOUR_MACBOOK_IP_HERE>" 부분을 실제 RTSP 서버가 실행 중인 맥북의 IP 주소로 변경하세요.
        // 만약 이 뷰어 프로그램을 RTSP 서버와 동일한 컴퓨터에서 실행한다면 "localhost"로 사용할 수 있습니다.
//        String rtspUrl = "rtsp://192.168.0.236:8554/live";
        String rtspUrl = "rtsp://localhost:8554/live";
        // 예시: String rtspUrl = "rtsp://192.168.0.10:8554/live";
        // 예시: String rtspUrl = "rtsp://localhost:8554/live";

        FFmpegFrameGrabber grabber = null;
        CanvasFrame canvas = null;

        try {
            // 2. FFmpegFrameGrabber 초기화
            grabber = new FFmpegFrameGrabber(rtspUrl);

            // RTSP 연결 옵션 설정 (TCP 사용 권장, 타임아웃 설정)
            grabber.setOption("rtsp_transport", "tcp"); // UDP보다 TCP가 안정적일 수 있음
            grabber.setOption("stimeout", "5000000");   // 서버 연결 타임아웃: 5초 (마이크로초 단위)

            System.out.println("RTSP 스트림에 연결 시도 중: " + rtspUrl);
            grabber.start(); // 스트림에 연결하고 그래버 초기화
            System.out.println("스트림 연결 성공!");
            System.out.println("수신 스트림 해상도: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
            System.out.println("수신 스트림 프레임률: " + grabber.getFrameRate());


            // 3. 영상을 표시할 CanvasFrame 생성
            // 타이틀, 감마 값 자동 보정
            canvas = new CanvasFrame("RTSP Viewer - " + rtspUrl, CanvasFrame.getDefaultGamma() / grabber.getGamma());
            // 창을 닫으면 프로그램이 종료되도록 설정
            canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            // 스트림의 해상도에 맞게 캔버스 크기 설정
            if (grabber.getImageWidth() > 0 && grabber.getImageHeight() > 0) {
                canvas.setCanvasSize(grabber.getImageWidth(), grabber.getImageHeight());
            } else {
                System.out.println("경고: 스트림에서 유효한 해상도 정보를 가져오지 못했습니다. 기본 크기로 표시될 수 있습니다.");
            }


            System.out.println("스트림 표시 시작. 미리보기 창을 닫으면 프로그램이 종료됩니다.");

            // 4. 메인 루프: 프레임을 지속적으로 가져와서 CanvasFrame에 표시
            while (canvas.isDisplayable()) { // CanvasFrame 창이 표시되어 있는 동안 반복
                Frame frame = grabber.grabImage(); // 비디오 프레임 가져오기 (grab()은 오디오/비디오 모두)

                if (frame != null) {
                    canvas.showImage(frame); // 가져온 프레임을 창에 표시
                } else {
                    // 프레임을 더 이상 가져올 수 없는 경우 (스트림 종료 또는 심각한 오류)
                    System.out.println("스트림으로부터 더 이상 프레임을 수신할 수 없습니다. 루프를 종료합니다.");
                    break;
                }
            }
            System.out.println("미리보기 창이 닫혔거나 스트림이 종료되었습니다.");

        } catch (FrameGrabber.Exception e) {
            System.err.println("RTSP 스트림 처리 중 오류 발생 (FrameGrabber.Exception):");
            e.printStackTrace();
        } catch (Exception e) { // 그 외 예외 처리
            System.err.println("예상치 못한 오류 발생:");
            e.printStackTrace();
        } finally {
            // 5. 사용한 리소스 해제 (매우 중요)
            System.out.println("리소스 해제 작업을 시작합니다...");

            if (canvas != null) {
                System.out.println("CanvasFrame을 닫습니다.");
                canvas.dispose(); // CanvasFrame 창 닫기 및 관련 리소스 해제
            }

            if (grabber != null) {
                try {
                    System.out.println("FrameGrabber를 중지하고 해제합니다.");
                    grabber.stop();     // 그래버 중지
                    grabber.release();  // 관련 리소스 모두 해제
                    System.out.println("FrameGrabber가 성공적으로 해제되었습니다.");
                } catch (FrameGrabber.Exception e) {
                    System.err.println("FrameGrabber 중지/해제 중 오류 발생:");
                    e.printStackTrace();
                }
            }
            System.out.println("RTSP Viewer 애플리케이션이 종료되었습니다.");
        }
    }
}