package com.tms.tester.seed;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tms.tester.domain.entity.LogTemplate;
import com.tms.tester.domain.repository.LogTemplateRepository;

/**
 * 예전 버전이 만든 잘못된 시드 템플릿을 기동할 때 고친다.
 *
 * <p>nginx {@code access} 시드는 한때 {@code ${choice(-:80,${dict(usernames)}:20)}} 처럼 함수 인자 안에 {@code ${...}} 를
 * 중첩했다. 파서는 중첩을 지원하지 않아 모든 로그에 {@code -:20)}} 가 섞여 나갔고, 지금은 저장 검증이 이 문법을
 * 거부하므로 화면에서 그 템플릿을 고치거나 켜고 끌 수도 없다(§16.17, §16.18). 이미 만들어진 계정의 템플릿은
 * 시드를 고쳐도 바뀌지 않으므로 여기서 바꾼다.
 *
 * <p><b>정확히 그 문자열만</b> 바꾼다 — 사용자가 손으로 고친 템플릿은 건드리지 않는다. 바꿀 것이 없으면 아무 일도
 * 하지 않으므로 몇 번 기동해도 안전하다. 자동 시작 작업({@code ApplicationReadyEvent})보다 먼저 돈다.
 */
@Component
public class SeedRepair implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedRepair.class);

	static final String BROKEN_NGINX_USER = "${choice(-:80,${dict(usernames)}:20)}";
	static final String FIXED_NGINX_USER = "${choice(-:80,admin:4,jdoe:4,svc_batch:4,monitor:4,guest:4)}";

	private final LogTemplateRepository templateRepository;

	public SeedRepair(LogTemplateRepository templateRepository) {
		this.templateRepository = templateRepository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		List<LogTemplate> broken = templateRepository.findByContentContaining(BROKEN_NGINX_USER);
		for (LogTemplate template : broken) {
			template.setContent(template.getContent().replace(BROKEN_NGINX_USER, FIXED_NGINX_USER));
		}
		if (!broken.isEmpty()) {
			log.info("옛 시드의 깨진 nginx 템플릿 {}개를 고쳤습니다(중첩 ${...} 제거)", broken.size());
		}
	}
}
