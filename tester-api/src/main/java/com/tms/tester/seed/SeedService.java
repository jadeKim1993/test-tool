package com.tms.tester.seed;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.common.LogFormat;
import com.tms.tester.common.Protocol;
import com.tms.tester.domain.entity.Category;
import com.tms.tester.domain.entity.LogTemplate;
import com.tms.tester.domain.entity.SendTarget;
import com.tms.tester.domain.entity.User;
import com.tms.tester.domain.repository.CategoryRepository;
import com.tms.tester.domain.repository.SendTargetRepository;

/**
 * 신규 사용자에게 바로 쓸 수 있는 카테고리/템플릿/대상을 넣어준다.
 * <p>빈 화면에서 시작하면 템플릿 문법부터 배워야 하므로, 실제 서비스 로그 형태를
 * 그대로 담은 예제를 제공해 "복사해서 고쳐 쓰는" 경로를 만든다.
 */
@Service
public class SeedService {

	private static final Logger log = LoggerFactory.getLogger(SeedService.class);

	private final CategoryRepository categoryRepository;
	private final SendTargetRepository targetRepository;

	public SeedService(CategoryRepository categoryRepository, SendTargetRepository targetRepository) {
		this.categoryRepository = categoryRepository;
		this.targetRepository = targetRepository;
	}

	@Transactional
	public void seedForNewUser(User user) {
		categoryRepository.save(tomcat(user));
		categoryRepository.save(nginx(user));
		categoryRepository.save(apache(user));
		categoryRepository.save(syslog(user));
		categoryRepository.save(firewall(user));
		categoryRepository.save(json(user));

		for (String host : defaultTargetHosts()) {
			targetRepository.save(new SendTarget(user, Protocol.UDP, host, SYSLOG_PORT));
		}
		log.info("신규 사용자 기본 데이터 생성: {}", user.getUsername());
	}

	/** 기본 전송 대상 포트. syslog 표준 포트라 수집 서버가 대개 여기로 받는다. */
	private static final int SYSLOG_PORT = 514;

	/**
	 * 기본 전송 대상 호스트(예제). 실제 운영에서 마주칠 법한 사설 대역을 보여 주기 위한 값이라
	 * 루프백이 아니다 — 로컬 {@code tools/log-sink} 로 시험하려면 대상을 직접 추가해야 한다.
	 */
	public static List<String> defaultTargetHosts() {
		List<String> hosts = new ArrayList<>();
		for (int last = 110; last <= 114; last++) {
			hosts.add("192.168.70." + last);
		}
		for (int last = 140; last <= 145; last++) {
			hosts.add("192.168.70." + last);
		}
		return hosts;
	}

	private Category tomcat(User user) {
		Category category = new Category(user, "tomcat", "Tomcat 액세스 로그 및 애플리케이션 로그");
		add(category, "access-combined", 70,
				"${ip as cip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] \"${choice(GET:70,POST:22,PUT:5,DELETE:3)} "
						+ "/api/v1/${choice(users,orders,items,payments,carts)}/${int(1,99999)} HTTP/1.1\" "
						+ "${choice(200:85,201:4,400:3,404:5,500:3)} ${int(120,48000)} "
						+ "\"-\" \"${dict(user_agents)}\" ${gauss(120,80)}");
		add(category, "catalina-info", 20,
				"${ts(dd-MMM-yyyy HH:mm:ss.SSS,en)} INFO [http-nio-8080-exec-${int(1,50)}] "
						+ "org.apache.catalina.core.StandardService - ${sentence(6)} (${int(1,900)} ms)");
		add(category, "catalina-error", 8,
				"${ts(dd-MMM-yyyy HH:mm:ss.SSS,en)} ERROR [http-nio-8080-exec-${int(1,50)}] "
						+ "o.a.c.c.C.[.[.[/].[dispatcherServlet] - Servlet.service() threw exception\n"
						+ "${dict(exceptions)}: ${sentence(5)}\n"
						+ "\tat com.tms.${dict(services)}.Handler.process(Handler.java:${int(30,400)})\n"
						+ "\tat com.tms.web.Controller.handle(Controller.java:${int(20,200)})");
		add(category, "gc-log", 2,
				"[${float(0,86400,3)}s][info][gc] GC(${seq}) Pause Young (Normal) "
						+ "${int(200,4000)}M->${int(80,1200)}M(${int(2048,8192)}M) ${float(1,90,3)}ms");
		return category;
	}

	private Category nginx(User user) {
		Category category = new Category(user, "nginx", "nginx 액세스/에러 로그");
		add(category, "access", 85,
				"${ip as cip} - ${choice(-:80,${dict(usernames)}:20)} [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] "
						+ "\"${choice(GET:75,POST:20,HEAD:5)} ${dict(paths)} HTTP/1.1\" "
						+ "${choice(200:88,301:3,404:6,502:3)} ${int(150,90000)} "
						+ "\"${choice(-,https://portal.internal/)}\" \"${dict(user_agents)}\" ${float(0,2,3)}");
		add(category, "error", 15,
				"${ts(yyyy/MM/dd HH:mm:ss)} [${choice(error:70,warn:25,crit:5)}] ${int(100,9999)}#${int(0,20)}: "
						+ "*${seq} ${sentence(6)}, client: ${ip}, server: portal.internal, "
						+ "request: \"GET ${dict(paths)} HTTP/1.1\", upstream: \"http://${ip(10.0.0.0/8)}:8080\"");
		return category;
	}

	private Category apache(User user) {
		Category category = new Category(user, "apache", "Apache httpd 로그");
		add(category, "access-common", 90,
				"${ip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] \"GET ${dict(paths)} HTTP/1.1\" "
						+ "${choice(200:90,404:7,500:3)} ${int(200,60000)}");
		add(category, "error", 10,
				"[${ts(EEE MMM dd HH:mm:ss.SSSSSS yyyy,en)}] [core:${choice(error,warn,notice)}] "
						+ "[pid ${int(1000,9999)}] [client ${ip}:${int(1024,65535)}] ${sentence(7)}");
		return category;
	}

	private Category syslog(User user) {
		Category category = new Category(user, "syslog", "RFC3164 / RFC5424 syslog");
		LogTemplate rfc3164 = add(category, "auth-rfc3164", 60,
				"sshd[${int(1000,30000)}]: ${choice(Accepted:60,Failed:40)} password for "
						+ "${dict(usernames)} from ${ip} port ${int(1024,65535)} ssh2");
		rfc3164.setFormat(LogFormat.SYSLOG_RFC3164);
		LogTemplate rfc5424 = add(category, "app-rfc5424", 40,
				"${dict(log_levels)} ${dict(services)} - ${sentence(8)} trace_id=${hex(16)}");
		rfc5424.setFormat(LogFormat.SYSLOG_RFC5424);
		return category;
	}

	private Category firewall(User user) {
		Category category = new Category(user, "firewall", "방화벽 세션 로그 (SIEM 테스트용)");
		add(category, "session", 100,
				"${ts(yyyy-MM-dd HH:mm:ss)} fw01 action=${choice(ALLOW:80,DENY:18,DROP:2)} "
						+ "proto=${choice(TCP:75,UDP:23,ICMP:2)} "
						+ "src=${ip(10.0.0.0/8)} spt=${int(1024,65535)} "
						+ "dst=${ip} dpt=${choice(443:50,80:25,22:10,3389:5,8080:10)} "
						+ "bytes=${int(60,1500000)} pkts=${int(1,900)} "
						+ "geo=${dict(countries)} rule=${int(1,250)}");
		return category;
	}

	private Category json(User user) {
		Category category = new Category(user, "json-app", "JSON 구조화 로그 (Fluent-bit/OpenSearch 테스트)");
		add(category, "app-event", 100,
				"{\"@timestamp\":\"${ts}\",\"level\":\"${choice(INFO:75,WARN:15,ERROR:10)}\","
						+ "\"service\":\"${dict(services)}\",\"host\":\"${host(node,12)}\","
						+ "\"trace_id\":\"${hex(32)}\",\"user\":\"${dict(usernames)}\","
						+ "\"client_ip\":\"${ip as cip}\",\"path\":\"${dict(paths)}\","
						+ "\"status\":${choice(200:85,404:8,500:7)},\"duration_ms\":${gauss(85,45)},"
						+ "\"bytes\":${int(120,90000)},\"message\":\"${sentence(6)}\"}");
		return category;
	}

	private LogTemplate add(Category category, String name, int weight, String content) {
		LogTemplate template = new LogTemplate(name, content);
		template.setWeight(weight);
		template.setSortOrder(category.getTemplates().size());
		category.addTemplate(template);
		return template;
	}

	/** 기본 카테고리 이름 목록(문서/테스트용). */
	public static List<String> defaultCategoryNames() {
		return List.of("tomcat", "nginx", "apache", "syslog", "firewall", "json-app");
	}
}
