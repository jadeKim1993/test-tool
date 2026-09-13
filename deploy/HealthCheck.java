/**
 * 컨테이너 HEALTHCHECK 전용 최소 프로그램.
 *
 * <p>JRE 베이스 이미지에는 curl/wget 이 없다. 폐쇄망에서는 이걸 받으려고 apt 미러까지
 * 준비해야 하므로, 빌드 단계에서 이 클래스를 컴파일해 넣고 JDK 내장 HttpClient 로 확인한다
 * (tools/log-sink 의 --healthcheck 와 같은 접근).
 *
 * <p>종료코드 0 = 정상, 1 = 비정상. 인자로 URL 을 주지 않으면 로컬 actuator 를 본다.
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthCheck {

	public static void main(String[] args) {
		String url = args.length > 0 ? args[0] : "http://127.0.0.1:8080/actuator/health";
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(2))
					.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			boolean up = response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
			System.exit(up ? 0 : 1);
		}
		catch (Exception e) {
			System.exit(1);
		}
	}
}
