import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;

public class CameraViewMain {

    public static void main(String[] args) {
        System.out.println("CCTV 스트리밍 클라이언트 시작...");

        // 카메라 번호 (0은 보통 기본 웹캠)
        int cameraDeviceIndex = 0;
        FrameGrabber grabber = null;

        try {
            System.out.println("카메라 장치 인덱스 " + cameraDeviceIndex + "에 연결 시도 중...");
            grabber = new OpenCVFrameGrabber(cameraDeviceIndex); // 또는 FFmpegFrameGrabber(cameraDeviceIndex);
            grabber.start(); // 카메라 열기
            System.out.println("카메라 연결 성공!");

            // 영상을 보여줄 창 생성
            CanvasFrame canvas = new CanvasFrame("CCTV Live - 카메라 테스트");
            canvas.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE); // 창 닫으면 프로그램 종료

            System.out.println("프레임 캡처 및 표시 시작. 창을 닫으면 종료됩니다.");
            while (canvas.isDisplayable()) { // 창이 표시되어 있는 동안 반복
                Frame frame = grabber.grab(); // 프레임 캡처
                if (frame != null) {
                    canvas.showImage(frame); // 창에 프레임 표시
                }
                // CPU 사용량을 줄이기 위해 짧은 지연시간을 줄 수 있습니다.
                // Thread.sleep(10); // 예: 약 10ms (100 FPS에 해당) - 필요에 따라 조절
            }

            System.out.println("표시 창이 닫혔습니다.");

        } catch (FrameGrabber.Exception e) {
            System.err.println("카메라 프레임 캡처 중 오류 발생:");
            e.printStackTrace();
        } finally {
            if (grabber != null) {
                try {
                    System.out.println("카메라 리소스 해제 중...");
                    grabber.stop();
                    grabber.release();
                    System.out.println("카메라 리소스 해제 완료.");
                } catch (FrameGrabber.Exception e) {
                    System.err.println("카메라 중지 중 오류 발생:");
                    e.printStackTrace();
                }
            }
            if (CanvasFrame.getFrames() != null && CanvasFrame.getFrames().length > 0) {
                CanvasFrame.getFrames()[0].dispose(); // 남아있는 CanvasFrame 명시적 해제
            }
            System.out.println("스트리밍 클라이언트 종료.");
        }
    }
}