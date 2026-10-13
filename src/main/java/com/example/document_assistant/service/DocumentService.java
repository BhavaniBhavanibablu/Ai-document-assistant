
package com.example.document_assistant.service;

import com.example.document_assistant.entity.DocumentChunk;
import com.example.document_assistant.entity.DocumentEntity;
import com.example.document_assistant.repository.DocumentChunkRepository;
import com.example.document_assistant.repository.DocumentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final PdfService pdfService;

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final int MAX_SOURCES = 2;
    private static final int MAX_EXCERPT_LENGTH = 700;
    private static final int MINIMUM_SCORE = 3;

    private static final Pattern WORD_PATTERN =
        Pattern.compile("[a-zA-Z0-9]+");

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "is", "are", "was", "were", "what", "when",
        "where", "who", "why", "how", "does", "did", "do",
        "a", "an", "and", "or", "of", "to", "in", "on",
        "for", "with", "about", "tell", "me", "please",
        "this", "that", "it", "they", "their", "from",
        "which", "can", "could", "would", "should",
        "define", "explain", "describe", "give", "show"
    );

    private static final Map<String, Set<String>> SYNONYMS =
        Map.of(
            "inheritance", Set.of(
                "inherit", "inherits", "inherited",
                "base", "derived", "extends", "parent", "child"
            ),
            "inherit", Set.of(
                "inheritance", "inherits", "inherited",
                "base", "derived", "extends", "parent", "child"
            ),
            "polymorphism", Set.of(
                "overloading", "overriding", "overridden",
                "override", "overload"
            ),
            "encapsulation", Set.of(
                "private", "getter", "setter", "data"
            ),
            "abstraction", Set.of(
                "abstract", "interface", "implementation"
            ),
            "constructor", Set.of(
                "constructors", "initialization", "initialize"
            )
        );

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            PdfService pdfService) {

        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.pdfService = pdfService;
    }

    // Upload a PDF and store its extracted text page by page.
    @Transactional
    public DocumentEntity uploadDocument(MultipartFile file)
            throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                "Please select a PDF file."
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                "File size must not exceed 50 MB."
            );
        }

        String filename = file.getOriginalFilename();

        if (filename == null
                || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException(
                "Only PDF files are allowed."
            );
        }

        List<String> pages = pdfService.extractTextByPage(file);

        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException(
                "No pages could be extracted from this PDF."
            );
        }

        DocumentEntity document = new DocumentEntity();
        document.setFilename(filename);
        document.setTotalPages(pages.size());
        document.setStatus("PROCESSED");

        document = documentRepository.save(document);

        for (int i = 0; i < pages.size(); i++) {
            String pageText = pages.get(i);

            if (pageText == null || pageText.isBlank()) {
                continue;
            }

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(document.getId());
            chunk.setPageNumber(i + 1);
            chunk.setChunkIndex(0);
            chunk.setContent(pageText.trim());

            chunkRepository.save(chunk);
        }

        return document;
    }

    // Return the uploaded document library.
    @Transactional(readOnly = true)
    public List<DocumentEntity> getAllDocuments() {
        return documentRepository.findAll();
    }

    // Retrieve a document by ID.
    @Transactional(readOnly = true)
    public DocumentEntity getDocumentById(Long id) {
        return documentRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException(
                "Document not found: " + id
            ));
    }

    // Retrieve relevant page excerpts for a question.
    @Transactional(readOnly = true)
    public Map<String, Object> askQuestion(
            Long documentId,
            String question) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                "Please enter a question."
            );
        }

        DocumentEntity document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Document not found: " + documentId
            ));

        // Extract meaningful words from the question.
        Set<String> questionWords = extractKeywords(question);

        // Include related terms for improved matching.
        Set<String> searchWords = new HashSet<>(questionWords);

        for (String word : questionWords) {
            searchWords.addAll(
                SYNONYMS.getOrDefault(word, Set.of())
            );
        }

        // Find text belonging only to the selected document.
        List<DocumentChunk> chunks = chunkRepository.findAll()
            .stream()
            .filter(chunk ->
                documentId.equals(chunk.getDocumentId()))
            .toList();

        List<ScoredChunk> scoredChunks = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            String content = chunk.getContent();

            if (content == null || content.isBlank()) {
                continue;
            }

            String normalizedContent =
                content.toLowerCase(Locale.ROOT);

            int score = 0;

            // Exact question keywords receive more weight.
            for (String word : questionWords) {
                score += countWholeWordMatches(
                    normalizedContent, word
                ) * 3;
            }

            // Related words receive a smaller weight.
            for (String word : searchWords) {
                if (questionWords.contains(word)) {
                    continue;
                }

                score += countWholeWordMatches(
                    normalizedContent, word
                );
            }

            // Give a small bonus to passages that look like definitions.
            if (normalizedContent.contains(" is defined as")
                    || normalizedContent.contains(" refers to")
                    || normalizedContent.contains(" is the process of")
                    || normalizedContent.contains(" is a process of")) {
                score += 2;
            }

            if (score > 0) {
                scoredChunks.add(new ScoredChunk(chunk, score));
            }
        }

        // Sort by relevance, then by page number.
        scoredChunks.sort(
            Comparator.comparingInt(ScoredChunk::score)
                .reversed()
                .thenComparingInt(
                    item -> item.chunk().getPageNumber()
                )
        );

        // Build source references for the frontend.
        List<Map<String, Object>> sources = new ArrayList<>();

        for (ScoredChunk item : scoredChunks) {
            if (sources.size() >= MAX_SOURCES) {
                break;
            }

            DocumentChunk chunk = item.chunk();

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunk.getId());
            source.put("filename", document.getFilename());
            source.put("pageNumber", chunk.getPageNumber());

            String excerpt = chunk.getContent().trim();

            if (excerpt.length() > MAX_EXCERPT_LENGTH) {
                excerpt =
                    excerpt.substring(0, MAX_EXCERPT_LENGTH) + "...";
            }

            source.put("excerpt", excerpt);
            source.put("score", item.score());

            sources.add(source);
        }

        // This is keyword retrieval, not a genuine AI-generated answer.
        String answer;

        if (sources.isEmpty()) {
            answer = "I couldn't find a relevant passage in this document "
                + "using keyword matching. Try rephrasing your question "
                + "or using keywords from the document.";
        } else {
            answer = "I found potentially relevant passages in "
                + document.getFilename()
                + ". These results use keyword matching, not AI-generated "
                + "reasoning. Review the source pages below to verify "
                + "the information.";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId", document.getId());
        response.put("filename", document.getFilename());
        response.put("question", question);
        response.put("answer", answer);
        response.put("sources", sources);

        return response;
    }

    // Extract searchable words while ignoring common question words.
    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();

        Matcher matcher = WORD_PATTERN.matcher(
            text.toLowerCase(Locale.ROOT)
        );

        while (matcher.find()) {
            String word = matcher.group();

            if (word.length() > 1 && !STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords;
    }

    // Count whole-word matches to avoid matching partial words.
    private int countWholeWordMatches(
            String content,
            String word) {

        Pattern pattern = Pattern.compile(
            "\\b" + Pattern.quote(word) + "\\b"
        );

        Matcher matcher = pattern.matcher(content);
        int count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }

    private record ScoredChunk(
        DocumentChunk chunk,
        int score
    ) {}
}
