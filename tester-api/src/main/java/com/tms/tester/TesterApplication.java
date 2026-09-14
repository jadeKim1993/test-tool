package com.tms.tester;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TesterApplication {

	public static void main(String[] args) {
		// 컨테이너 HEALTHCHECK 는 이 모드로 자기 자신을 부른다. 이미지에 curl/wget 을 넣지 않기
		// 위해서다(폐쇄망에서 그것 하나 때문에 apt 미러까지 준비해야 했다).
		// Spring 컨텍스트를 띄우지 않고 HTTP 한 번만 찔러 보고 끝낸다.
		if (args.length > 0 && "--healthcheck".equals(args[0])) {
			System.exit(healthy(args.length > 1 ? args[1] : "http://127.0.0.1:8080/actuator/health") ? 0 : 1);
			return;
		}
		SpringApplication.run(TesterApplication.class, args);
	}

	private static boolean healthy(String url) {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
			HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
		}
		catch (Exception e) {
			return false;
		}
	}

}
