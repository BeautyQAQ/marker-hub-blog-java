package com.blog.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private JwtUtil jwt;

    @BeforeEach
    void configure() {
        jwt = new JwtUtil();
        jwt.setSecret(Encoders.BASE64.encode(new byte[64]));
        jwt.setExpire(60);
    }

    @Test
    void signsAndVerifiesHs512OnJava21() {
        Claims claims = jwt.getClaimByToken(jwt.generateToken(42));
        assertNotNull(claims);
        assertEquals("42", claims.getSubject());
        assertTrue(claims.getExpiration().after(new Date()));
    }

    @Test
    void rejectsTokenSignedByAnotherKey() {
        String token = jwt.generateToken(42);
        byte[] otherKey = new byte[64];
        otherKey[0] = 1;
        jwt.setSecret(Encoders.BASE64.encode(otherKey));
        assertNull(jwt.getClaimByToken(token));
    }

    @Test
    void rejectsExpiredToken() {
        jwt.setExpire(-60);
        assertNull(jwt.getClaimByToken(jwt.generateToken(42)));
    }

    @Test
    void rejectsMalformedToken() {
        assertNull(jwt.getClaimByToken("invalid.token"));
    }
}
