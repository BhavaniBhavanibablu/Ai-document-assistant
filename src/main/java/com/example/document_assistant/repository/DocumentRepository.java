
package com.example.document_assistant.repository;

import com.example.document_assistant.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository
        extends JpaRepository<DocumentEntity, Long> {
}
