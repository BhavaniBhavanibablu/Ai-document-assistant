package com.example.document_assistant.controller;

import com.example.document_assistant.entity.DocumentEntity;
import com.example.document_assistant.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@CrossOrigin(origins = {
    "http://localhost:5173",
    "http://localhost:5174"
})
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(
        value = "/upload",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DocumentEntity upload(
            @RequestParam("file") MultipartFile file)
            throws IOException {

        try {
            return documentService.uploadDocument(file);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                e.getMessage()
            );
        }
    }

    @GetMapping
    public List<DocumentEntity> getAllDocuments() {
        return documentService.getAllDocuments();
    }

    
    @GetMapping("/{id}")
    public DocumentEntity getDocumentById(
            @PathVariable Long id) {
        try {
            return documentService.getDocumentById(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                e.getMessage()
            );
        }
    }
}