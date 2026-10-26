package com.tms.tester.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tms.tester.common.LogFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class TemplateCompilerTest {

	private final TemplateCompiler compiler = new TemplateCompiler();

	private String render(String template) {
		return compiler.compile(template).renderToString(RenderContext.standalone());
	}

	@Test
	void 고정문자열은_그대로_출력된다() {
		assertThat(render("hello world")).isEqualTo("hello world");
	}

	@Test
	void ip는_IPv4_형식이다() {
		assertThat(render("${ip}")).matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
	}

	@Test
	void cidr_범위를_벗어나지_않는다() {
		for (int i = 0; i < 200; i++) {
			assertThat(render("${ip(10.1.0.0/16)}")).startsWith("10.1.");
		}
	}

	@Test
	void int는_지정범위_안에서_생성된다() {
		for (int i = 0; i < 500; i++) {
			int value = Integer.parseInt(render("${int(5,9)}"));
			assertThat(value).isBetween(5, 9);
		}
	}

	@Test
	void step_옵션이_적용된다() {
		for (int i = 0; i < 100; i++) {
			int value = Integer.parseInt(render("${int(100,500,step=100)}"));
			assertThat(value % 100).isZero();
		}
	}

	@Test
	void choice_가중치가_반영된다() {
		int hits = 0;
		for (int i = 0; i < 2000; i++) {
			if (render("${choice(A:90,B:10)}").equals("A")) {
				hits++;
			}
		}
		assertThat(hits).isBetween(1650, 1950); // 기대값 1800
	}

	@Test
	void str_길이가_정확하다() {
		assertThat(render("${str(12)}")).hasSize(12);
		assertThat(render("${str(3,5)}").length()).isBetween(3, 5);
	}

	@Test
	void uuid_형식이_맞다() {
		assertThat(render("${uuid}"))
				.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
	}

	@Test
	void float_소수점_자리수가_지정대로다() {
		String value = render("${float(0,1,3)}");
		assertThat(value).matches("0\\.\\d{3}|1\\.000");
	}

	@Test
	void 바인딩한_값을_ref로_재사용한다() {
		String rendered = render("${ip as cip} - ${ref(cip)}");
		String[] parts = rendered.split(" - ");
		assertThat(parts[0]).isEqualTo(parts[1]);
	}

	@Test
	void seq는_증가한다() {
		CompiledTemplate template = compiler.compile("${seq}");
		RenderContext ctx = RenderContext.standalone();
		long first = Long.parseLong(template.renderToString(ctx));
		long second = Long.parseLong(template.renderToString(ctx));
		assertThat(second).isEqualTo(first + 1);
	}

	@Test
	void 시각_패턴이_적용된다() {
		assertThat(render("${ts(yyyy-MM-dd HH:mm:ss)}")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
	}

	@Test
	void 이스케이프한_달러중괄호는_리터럴이다() {
		assertThat(render("price is \\${100}")).isEqualTo("price is ${100}");
	}

	@Test
	void 실전_톰캣_액세스로그가_렌더링된다() {
		String template = "${ip as cip} - - [${ts(dd/MMM/yyyy:HH:mm:ss Z,en)}] \"${choice(GET:70,POST:30)} "
				+ "/api/v1/${choice(users,orders)}/${int(1,99999)} HTTP/1.1\" ${choice(200:85,404:15)} ${int(120,48000)}";
		String rendered = render(template);
		assertThat(rendered).contains("HTTP/1.1").contains("/api/v1/");
		assertThat(rendered.split(" ")[0]).matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
	}

	@Test
	void syslog_포맷은_헤더를_붙인다() {
		CompiledTemplate template = compiler.compile(1L, "t", LogFormat.SYSLOG_RFC3164,
				StandardCharsets.UTF_8, "user login failed");
		String rendered = template.renderToString(RenderContext.standalone());
		assertThat(rendered).startsWith("<").contains("user login failed");
	}

	@Test
	void 알수없는_함수는_오류를_알려준다() {
		assertThatThrownBy(() -> compiler.validate("${nosuchfunc}"))
				.isInstanceOf(TemplateSyntaxException.class)
				.hasMessageContaining("알 수 없는 함수");
	}

	@Test
	void 닫히지_않은_플레이스홀더는_오류다() {
		assertThatThrownBy(() -> compiler.validate("abc ${ip"))
				.isInstanceOf(TemplateSyntaxException.class)
				.hasMessageContaining("닫히지 않은");
	}

	@Test
	void 정의되지_않은_ref는_오류다() {
		assertThatThrownBy(() -> compiler.validate("${ref(unknown)}"))
				.isInstanceOf(TemplateSyntaxException.class)
				.hasMessageContaining("정의되지 않은 변수");
	}

	@Test
	void 렌더링은_버퍼에_누적된다() {
		CompiledTemplate template = compiler.compile("line");
		RenderContext ctx = RenderContext.standalone();
		ByteBuf buf = Unpooled.buffer();
		try {
			template.render(buf, ctx);
			template.render(buf, ctx);
			assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo("lineline");
		}
		finally {
			buf.release();
		}
	}

	@Test
	void strict_모드는_중첩_플레이스홀더를_거부한다() {
		TemplateCompiler strict = new TemplateCompiler(ValueWriterRegistry.standard(), true);
		assertThatThrownBy(() -> strict.validate("user=${choice(-:80,${dict(usernames)}:20)} end"))
				.isInstanceOf(TemplateSyntaxException.class)
				.hasMessageContaining("넣을 수 없습니다")
				.extracting(e -> ((TemplateSyntaxException) e).getPosition()).isEqualTo(5);
	}

	@Test
	void 기본_모드는_이미_저장된_중첩_템플릿을_예전처럼_통과시킨다() {
		// 엔진이 기존 템플릿 때문에 갑자기 기동에 실패하지 않아야 한다(잘못 렌더되는 건 그대로).
		assertThat(render("${choice(a,${dict(usernames)}:20)}")).endsWith(":20)}");
	}

	@Test
	void 조각별_렌더는_출처를_알려준다() {
		CompiledTemplate template = compiler.compile("src=${ip as cip} n=${int(5,5)} again=${ref(cip)}");
		assertThat(template.placeholders()).extracting(CompiledTemplate.Placeholder::function)
				.containsExactly("ip", "int", "ref");
		assertThat(template.placeholders().get(1).expression()).isEqualTo("${int(5,5)}");
		assertThat(template.placeholders().get(1).start()).isEqualTo(19);

		List<CompiledTemplate.Part> parts = template.renderParts(RenderContext.standalone());
		assertThat(parts).extracting(CompiledTemplate.Part::source).containsExactly(-1, 0, -1, 1, -1, 2);
		assertThat(parts.get(3).text()).isEqualTo("5");
		assertThat(parts.get(5).text()).isEqualTo(parts.get(1).text());
	}

	@Test
	void 포맷_헤더는_한_조각으로_합쳐진다() {
		CompiledTemplate template = compiler.compile(1L, "t", LogFormat.SYSLOG_RFC5424,
				StandardCharsets.UTF_8, "msg ${int(1,1)}");
		List<CompiledTemplate.Part> parts = template.renderParts(RenderContext.standalone());
		assertThat(parts).extracting(CompiledTemplate.Part::source).containsExactly(-2, -1, 0);
		assertThat(parts.get(0).text()).startsWith("<");
		assertThat(template.placeholders()).hasSize(1);
	}
}
