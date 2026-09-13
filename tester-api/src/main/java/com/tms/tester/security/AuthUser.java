package com.tms.tester.security;

/** 인증된 사용자. JWT 클레임에서 복원되며 DB 조회 없이 사용한다. */
public record AuthUser(Long id, String username) {
}
