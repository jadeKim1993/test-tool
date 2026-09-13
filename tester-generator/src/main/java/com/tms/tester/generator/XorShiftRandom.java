package com.tms.tester.generator;

/**
 * 워커 스레드 전용 난수 생성기.
 * <p>{@code ThreadLocalRandom} 보다 호출 비용이 낮고, 인스턴스가 워커에 고정되므로
 * 스레드로컬 조회조차 발생하지 않는다. <b>스레드 안전하지 않다.</b>
 */
public final class XorShiftRandom {

	private long state;
	private double nextGaussian;
	private boolean hasNextGaussian;

	public XorShiftRandom() {
		this(System.nanoTime() ^ (((long) System.identityHashCode(Thread.currentThread())) << 32));
	}

	public XorShiftRandom(long seed) {
		this.state = (seed == 0) ? 0x9E3779B97F4A7C15L : seed;
	}

	public long nextLong() {
		long x = state;
		x ^= x << 13;
		x ^= x >>> 7;
		x ^= x << 17;
		state = x;
		return x;
	}

	public int nextInt() {
		return (int) (nextLong() >>> 32);
	}

	/** {@code 0 <= n < bound} 범위의 정수. */
	public int nextInt(int bound) {
		if (bound <= 1) {
			return 0;
		}
		return (int) ((nextLong() >>> 1) % bound);
	}

	/** {@code min <= n <= max} 범위의 정수(양 끝 포함). */
	public int nextIntInclusive(int min, int max) {
		if (max <= min) {
			return min;
		}
		return min + nextInt(max - min + 1);
	}

	public long nextLongInclusive(long min, long max) {
		if (max <= min) {
			return min;
		}
		return min + (nextLong() >>> 1) % (max - min + 1);
	}

	public double nextDouble() {
		return (nextLong() >>> 11) * 0x1.0p-53;
	}

	public boolean nextBoolean() {
		return (nextLong() & 1L) != 0;
	}

	/** 평균 0, 표준편차 1 의 정규분포 난수(Box-Muller, 값 1개 캐시). */
	public double nextGaussian() {
		if (hasNextGaussian) {
			hasNextGaussian = false;
			return nextGaussian;
		}
		double u1, u2, s;
		do {
			u1 = 2 * nextDouble() - 1;
			u2 = 2 * nextDouble() - 1;
			s = u1 * u1 + u2 * u2;
		} while (s >= 1 || s == 0);
		double factor = Math.sqrt(-2 * Math.log(s) / s);
		nextGaussian = u2 * factor;
		hasNextGaussian = true;
		return u1 * factor;
	}
}
