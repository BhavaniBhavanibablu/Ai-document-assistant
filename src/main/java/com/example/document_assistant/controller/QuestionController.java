
package com.example.document_assistant.controller;

import com.example.document_assistant.dto.QuestionRequest;
import com.example.document_assistant.dto.QuestionResponse;
import com.example.document_assistant.service.QuestionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = {
    "http://localhost:5173",
    "http://localhost:5174"
})
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping("/{documentId}/ask")
    public ResponseEntity<?> askQuestion(
            @PathVariable Long documentId,
            @RequestBody QuestionRequest request) {

        try {
            QuestionResponse response =
                questionService.askQuestion(documentId, request);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .badRequest()
                .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            e.printStackTrace();

            String message = e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage();

            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", message));
        }
    }
}
