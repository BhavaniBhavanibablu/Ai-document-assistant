
package com.example.document_assistant.service;

import com.example.document_assistant.entity.DocumentEntity;
import com.example.document_assistant.repository.DocumentChunkRepository;
import com.example.document_assistant.repository.DocumentRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTests {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private PdfService pdfService;

    @InjectMocks
    private DocumentService documentService;

    @Test
    void getDocumentByIdShouldReturnDocumentWhenFound() {
        DocumentEntity document = mock(DocumentEntity.class);

        when(documentRepository.findById(5L))
                .thenReturn(Optional.of(document));

        DocumentEntity result =
                documentService.getDocumentById(5L);

        assertSame(document, result);

        verify(documentRepository).findById(5L);
    }

    @Test
    void getDocumentByIdShouldThrowExceptionWhenNotFound() {
        when(documentRepository.findById(50L))
                .thenReturn(Optional.empty());

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> documentService.getDocumentById(50L)
                );

        assertEquals(
                "Document not found: 50",
                exception.getMessage()
        );
    }

    @Test
    void getAllDocumentsShouldReturnDocuments() {
        DocumentEntity document = mock(DocumentEntity.class);

        when(documentRepository.findAll())
                .thenReturn(List.of(document));

        List<DocumentEntity> result =
                documentService.getAllDocuments();

        assertEquals(1, result.size());
        assertSame(document, result.get(0));

        verify(documentRepository).findAll();
    }

    @Test
    void askQuestionShouldRejectBlankQuestion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> documentService.askQuestion(5L, "")
        );

        verifyNoInteractions(documentRepository);
    }

    @Test
    void askQuestionShouldThrowExceptionWhenDocumentNotFound() {
        when(documentRepository.findById(50L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> documentService.askQuestion(
                        50L,
                        "What is inheritance?"
                )
        );

        verify(documentRepository).findById(50L);
    }
}
