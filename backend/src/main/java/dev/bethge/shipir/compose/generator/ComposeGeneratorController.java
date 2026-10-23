package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compose")
class ComposeGeneratorController {

    private final ComposeGeneratorService generator;

    ComposeGeneratorController(ComposeGeneratorService generator) {
        this.generator = generator;
    }

    @PostMapping("/generate")
    GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        return new GenerateResponse(generator.generate(request.spec()));
    }

    @ExceptionHandler(InvalidComposeSpecException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidComposeSpecException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_COMPOSE_SPEC", "Compose specification is invalid", ex.violations()));
    }
}
