package io.superharness.api;

import io.superharness.domain.Problem;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.getMessage()));
    }
}
