package com.tms.tester.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tms.tester.domain.entity.SendTarget;

public interface SendTargetRepository extends JpaRepository<SendTarget, Long> {

	List<SendTarget> findByOwnerIdOrderByHostAscPortAsc(Long ownerId);

	boolean existsByOwnerIdAndProtocolAndHostAndPort(Long ownerId, String protocol, String host, int port);
}
