package com.example.document_assistant.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
            "application", "Document Assistant",
            "status", "running",
            "message", "Welcome to Document Assistant API"
        );
    }
}