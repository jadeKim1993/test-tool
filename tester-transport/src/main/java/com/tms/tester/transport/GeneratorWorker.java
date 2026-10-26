package com.tms.tester.transport;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tms.tester.common.OverflowPolicy;
import com.tms.tester.common.Framing;
import com.tms.tester.common.WireEncoding;
import com.tms.tester.generator.Base64s;
import com.tms.tester.generator.ByteBufs;
import com.tms.tester.generator.CompiledTemplate;
import com.tms.tester.generator.RenderContext;
import com.tms.tester.generator.TemplateCompiler;
import com.tms.tester.generator.XorShiftRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 로그 생성 + 전송 워커 1개.
 *
 * <h2>속도 제어</h2>
 * 건당 sleep 은 커널 타이머 해상도 때문에 수천 EPS 에서 무너진다. 대신 <b>1~10ms 틱</b>마다
 * 깨어나 "이번 틱에 보낼 건수"를 계산해 몰아서 보낸다. 소수점은 credit 에 누적하고,
 * 다음 틱 시각은 절대 기준({@code nextTick += tickNanos})으로 더해 드리프트를 없앤다.
 *
 * <h2>배치</h2>
 * TCP 는 로그를 16KB 버퍼에 이어붙였다가 한 번에 넘긴다. 10만 EPS 에서 건당 write 를 하면
 * syscall 이 초당 10만 번이지만, 배치하면 초당 1~2천 번으로 줄어든다.
 * UDP 는 1건=1패킷이 규약이므로 건별로 버퍼를 만들되 flush 를 틱 단위로 모은다.
 * HTTP 도 건별로 넘긴다 — 본문 형식(JSON 등)에 맞춰 한 건씩 감싸는 일은 송신기가 하므로, 프레이밍 없이
 * 로그 본문만 준다(여러 줄 로그를 줄바꿈으로 자르면 한 건이 여러 건으로 쪼개진다).
 *
 * <h2>스레드 안전</h2>
 * 렌더링 상태(RenderContext, CompiledTemplate, 난수, 배치 버퍼)는 전부 워커 전용이다.
 * 공유되는 것은 LongAdder 카운터와 몇 개의 volatile 설정값뿐이다.
 */
final class GeneratorWorker implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(GeneratorWorker.class);

	/** 무제한 모드에서 한 번에 처리할 건수. 중간에 정지 신호를 확인할 수 있을 만큼 작게 둔다. */
	private static final int UNLIMITED_CHUNK = 512;
	/** 백프레셔로 막혔을 때 재시도 간격. */
	private static final long BLOCK_PARK_NANOS = 200_000L;
	/** 틱당 목표 건수. 이 값에 맞춰 틱 주기를 정한다. */
	private static final int LOGS_PER_TICK_TARGET = 25;

	private final int index;
	private final LoadGenerator generator;
	private final SendEndpoint endpoint;
	private final SendStats stats;
	private final ByteBufAllocator allocator;
	private final Framing framing;
	private final WireEncoding encoding;
	/** 건별로 송신기에 넘기는가(UDP·HTTP). false 면 배치 버퍼에 이어 붙인다(TCP·TLS). */
	private final boolean datagramMode;
	private final boolean udp;
	private final int batchBytes;
	private final int workerCount;
	private final OverflowPolicy overflow;

	private final RenderContext context;
	private final TemplateCompiler compiler = new TemplateCompiler();

	private CompiledTemplate[] templates = new CompiledTemplate[0];
	private int[] cumulativeWeights = new int[0];
	private int totalWeight;
	private int templateGeneration = -1;
	private int roundRobinCursor;

	/** TCP 배치 누적 버퍼. */
	private ByteBuf batch;
	private int batchCount;
	/** 길이 프리픽스 계열 프레이밍에서 길이를 먼저 알아내기 위한 임시 버퍼. */
	private ByteBuf scratch;

	GeneratorWorker(int index, LoadGenerator generator, SendEndpoint endpoint, AtomicLong sequence) {
		this.index = index;
		this.generator = generator;
		this.endpoint = endpoint;
		this.stats = generator.stats();
		this.allocator = generator.allocator();
		RunSpec spec = generator.spec();
		boolean http = spec.target().isHttp();
		this.framing = http ? Framing.NONE : spec.target().framing();
		this.encoding = spec.encoding();
		this.udp = com.tms.tester.common.Protocol.UDP.equalsIgnoreCase(spec.target().protocol());
		this.datagramMode = udp || http;
		this.batchBytes = spec.target().batchBytes();
		this.workerCount = generator.workerCount();
		this.overflow = spec.overflow();
		this.context = new RenderContext(new XorShiftRandom(System.nanoTime() * 31L + index), sequence);
	}

	@Override
	public void run() {
		try {
			if (!datagramMode) {
				batch = allocator.directBuffer(batchBytes * 2);
			}
			loop();
		}
		catch (Throwable t) {
			log.error("워커 {} 비정상 종료", index, t);
			stats.recordError();
			generator.reportFailure(t);
		}
		finally {
			releaseBuffers();
		}
	}

	private void loop() {
		long tickNanos = 1_000_000L;
		int lastEps = -1;
		double credit = 0;
		long nextTick = System.nanoTime();

		while (generator.isRunning()) {
			refreshTemplatesIfChanged();

			int targetEps = generator.targetEps();
			if (targetEps != lastEps) {
				tickNanos = computeTickNanos(targetEps);
				lastEps = targetEps;
				credit = 0;
			}

			context.tick(System.currentTimeMillis());

			int quota;
			if (targetEps == 0) {
				quota = UNLIMITED_CHUNK;
			}
			else {
				credit += (double) targetEps / workerCount * (tickNanos / 1_000_000_000.0);
				quota = (int) credit;
				credit -= quota;
			}

			if (quota > 0) {
				emit(quota);
			}
			flushBatch();
			endpoint.flush();

			if (targetEps == 0) {
				// 무제한 모드는 속도를 늦추지 않되, 청크마다 한 번 양보한다.
				// 양보가 없으면 워커가 코어를 모두 점유해 정작 소켓을 비워줄 I/O 스레드가
				// 굶어버린다(수신측이 같은 장비에 있을 때 전송이 통째로 멈춘다).
				// 512건당 1회이므로 처리량에 미치는 영향은 무시할 수준이다.
				Thread.yield();
				continue;
			}

			nextTick += tickNanos;
			long sleepNanos = nextTick - System.nanoTime();
			if (sleepNanos > 0) {
				LockSupport.parkNanos(sleepNanos);
			}
			else if (sleepNanos < -1_000_000_000L) {
				// 1초 이상 밀렸다면 따라잡기를 포기하고 기준 시각을 다시 맞춘다.
				nextTick = System.nanoTime();
			}
		}

		flushBatch();
		endpoint.flush();
	}

	/** 목표 EPS 에 맞춰 틱 주기를 정한다. 틱당 건수가 너무 적으면 주기를 늘려 오버헤드를 줄인다. */
	private long computeTickNanos(int targetEps) {
		if (targetEps == 0) {
			return 1_000_000L;
		}
		double perWorkerEps = (double) targetEps / workerCount;
		long millis = Math.round(LOGS_PER_TICK_TARGET * 1000.0 / Math.max(perWorkerEps, 1));
		return Math.max(1L, Math.min(millis, 10L)) * 1_000_000L;
	}

	private void emit(int quota) {
		for (int i = 0; i < quota; i++) {
			CompiledTemplate template = selectTemplate();
			if (datagramMode) {
				if (!sendDatagram(template)) {
					return;
				}
			}
			else if (!appendToBatch(template)) {
				return;
			}
		}
	}

	// ---------------- UDP ----------------

	private boolean sendDatagram(CompiledTemplate template) {
		if (!awaitWritable()) {
			stats.recordDropped(1);
			return overflow == OverflowPolicy.DROP;
		}
		ByteBuf buf = allocator.directBuffer(template.estimatedSize() + 8);
		try {
			renderWithFraming(buf, template);
		}
		catch (RuntimeException e) {
			buf.release();
			stats.recordError();
			throw e;
		}
		int size = buf.readableBytes();
		if (udp && size > UdpLogSender.SAFE_DATAGRAM_BYTES) {
			stats.recordOversized();
		}
		if (endpoint.send(buf)) {
			stats.recordSent(1, size);
		}
		else {
			stats.recordDropped(1);
		}
		return true;
	}

	// ---------------- TCP ----------------

	private boolean appendToBatch(CompiledTemplate template) {
		renderWithFraming(batch, template);
		batchCount++;
		if (batch.readableBytes() >= batchBytes) {
			return flushBatch();
		}
		return true;
	}

	/**
	 * 누적된 배치를 소켓으로 넘긴다.
	 *
	 * @return 계속 진행해도 되는지 여부(BLOCK 정책에서 정지 신호를 받으면 false)
	 */
	private boolean flushBatch() {
		if (batch == null || batchCount == 0) {
			return true;
		}
		if (!awaitWritable()) {
			// 수신측이 못 따라오는 상황. 정책에 따라 버리거나 다음 틱에 다시 시도한다.
			if (overflow == OverflowPolicy.DROP) {
				stats.recordDropped(batchCount);
				batch.clear();
				batchCount = 0;
				return true;
			}
			return false;
		}
		int size = batch.readableBytes();
		int count = batchCount;
		if (endpoint.send(batch)) {
			stats.recordSent(count, size);
		}
		else {
			stats.recordDropped(count);
		}
		// send() 가 소유권을 가져갔으므로 새 버퍼를 준비한다.
		batch = allocator.directBuffer(batchBytes * 2);
		batchCount = 0;
		return true;
	}

	/**
	 * 송신 버퍼에 여유가 생길 때까지 기다린다.
	 *
	 * @return 전송 가능하면 true. DROP 정책이거나 정지 신호를 받으면 false
	 */
	private boolean awaitWritable() {
		if (endpoint.isWritable()) {
			return true;
		}
		if (overflow == OverflowPolicy.DROP) {
			return false;
		}
		while (generator.isRunning() && !endpoint.isWritable()) {
			LockSupport.parkNanos(BLOCK_PARK_NANOS);
		}
		return endpoint.isWritable();
	}

	// ---------------- 렌더링 ----------------

	private void renderWithFraming(ByteBuf target, CompiledTemplate template) {
		// Base64 는 본문 전체 길이를 알아야 하므로 항상 임시 버퍼를 거친다.
		if (encoding.isBase64()) {
			int length = renderToScratch(template);
			writeFramed(target, length, true);
			return;
		}
		switch (framing) {
			case NONE -> template.render(target, context);
			case NEWLINE -> {
				template.render(target, context);
				target.writeByte('\n');
			}
			// 길이 프리픽스 계열도 본문 길이를 먼저 알아야 한다.
			case LENGTH_PREFIX, OCTET_COUNTING -> {
				int length = renderToScratch(template);
				writeFramed(target, length, false);
			}
		}
	}

	/** scratch 에 렌더링된 본문을 프레이밍과 함께 대상 버퍼에 기록한다. */
	private void writeFramed(ByteBuf target, int bodyLength, boolean base64) {
		int payloadLength = base64 ? Base64s.encodedLength(bodyLength) : bodyLength;
		switch (framing) {
			case LENGTH_PREFIX -> target.writeInt(payloadLength);
			case OCTET_COUNTING -> {
				ByteBufs.writeAsciiUnsigned(target, payloadLength);
				target.writeByte(' ');
			}
			default -> {
				// NONE / NEWLINE 은 선행 바이트가 없다.
			}
		}
		if (base64) {
			Base64s.encodeInto(scratch, 0, bodyLength, target);
		}
		else {
			target.writeBytes(scratch, 0, bodyLength);
		}
		if (framing == Framing.NEWLINE) {
			target.writeByte('\n');
		}
	}

	private int renderToScratch(CompiledTemplate template) {
		if (scratch == null) {
			scratch = allocator.directBuffer(Math.max(512, template.estimatedSize() * 2));
		}
		scratch.clear();
		template.render(scratch, context);
		return scratch.readableBytes();
	}

	// ---------------- 템플릿 ----------------

	/** 실행 중 템플릿이 수정되면 다음 틱에 자동 반영된다(무중단). */
	private void refreshTemplatesIfChanged() {
		int generation = generator.templateGeneration();
		if (generation == templateGeneration) {
			return;
		}
		List<TemplateSource> sources = generator.templates();
		CompiledTemplate[] compiled = new CompiledTemplate[sources.size()];
		int[] weights = new int[sources.size()];
		int running = 0;
		for (int i = 0; i < sources.size(); i++) {
			TemplateSource source = sources.get(i);
			compiled[i] = compiler.compile(source.id(), source.name(), source.format(),
					encoding.charset(), source.content());
			running += source.weight();
			weights[i] = running;
		}
		this.templates = compiled;
		this.cumulativeWeights = weights;
		this.totalWeight = running;
		this.templateGeneration = generation;
		if (index == 0) {
			log.debug("워커 템플릿 갱신: {}개 (generation={})", compiled.length, generation);
		}
	}

	private CompiledTemplate selectTemplate() {
		if (templates.length == 1) {
			return templates[0];
		}
		return switch (generator.spec().selection()) {
			case WEIGHTED_RANDOM -> {
				int pick = context.random().nextInt(totalWeight);
				int i = 0;
				while (pick >= cumulativeWeights[i]) {
					i++;
				}
				yield templates[i];
			}
			// 워커마다 시작점을 어긋나게 해 같은 순간에 같은 종류가 몰리지 않게 한다.
			case ROUND_ROBIN -> templates[Math.floorMod(roundRobinCursor++ + index, templates.length)];
			case SEQUENTIAL -> templates[Math.floorMod(roundRobinCursor++, templates.length)];
		};
	}

	private void releaseBuffers() {
		if (batch != null) {
			batch.release();
			batch = null;
		}
		if (scratch != null) {
			scratch.release();
			scratch = null;
		}
	}
}
