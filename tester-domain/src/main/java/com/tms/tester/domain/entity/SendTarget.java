package com.tms.tester.domain.entity;

import com.tms.tester.common.Framing;
import com.tms.tester.common.Protocol;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 로그를 보낼 서버. */
@Entity
@Table(name = "send_targets",
		uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "protocol", "host", "port"}))
public class SendTarget extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private User owner;

	/** 표시용 이름. 입력받지 않고 프로토콜/호스트/포트에서 자동으로 만든다. */
	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 20)
	private String protocol = Protocol.TCP;

	@Column(nullable = false, length = 255)
	private String host;

	@Column(nullable = false)
	private int port;

	/** 소켓(커넥션) 수. 0 이면 워커 수에 맞춘다. */
	@Column(nullable = false)
	private int connections;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Framing framing = Framing.NEWLINE;

	/** TCP 배치 임계치(바이트). */
	@Column(nullable = false)
	private int batchBytes = 16384;

	@Column(nullable = false)
	private int sendBufferBytes = 4 * 1024 * 1024;

	@Column(nullable = false)
	private int connectTimeoutMs = 3000;

	protected SendTarget() {
	}

	public SendTarget(User owner, String protocol, String host, int port) {
		this.owner = owner;
		this.protocol = protocol;
		this.host = host;
		this.port = port;
		this.name = displayName();
	}

	/** 전송 대상은 주소로 구분한다. 이름은 거기서 파생된 표시값일 뿐이다. */
	public String displayName() {
		return protocol + " " + host + ":" + port;
	}

	public void refreshName() {
		this.name = displayName();
	}

	public Long getId() {
		return id;
	}

	public User getOwner() {
		return owner;
	}

	public String getName() {
		return name;
	}

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getConnections() {
		return connections;
	}

	public void setConnections(int connections) {
		this.connections = connections;
	}

	public Framing getFraming() {
		return framing;
	}

	public void setFraming(Framing framing) {
		this.framing = framing;
	}

	public int getBatchBytes() {
		return batchBytes;
	}

	public void setBatchBytes(int batchBytes) {
		this.batchBytes = batchBytes;
	}

	public int getSendBufferBytes() {
		return sendBufferBytes;
	}

	public void setSendBufferBytes(int sendBufferBytes) {
		this.sendBufferBytes = sendBufferBytes;
	}

	public int getConnectTimeoutMs() {
		return connectTimeoutMs;
	}

	public void setConnectTimeoutMs(int connectTimeoutMs) {
		this.connectTimeoutMs = connectTimeoutMs;
	}

	public String endpoint() {
		return protocol + "://" + host + ":" + port;
	}
}
