
package com.example.document_assistant.service;

import com.example.document_assistant.dto.QuestionRequest;
import com.example.document_assistant.dto.QuestionResponse;
import com.example.document_assistant.dto.SourceReference;
import com.example.document_assistant.entity.DocumentChunk;
import com.example.document_assistant.entity.DocumentEntity;
import com.example.document_assistant.repository.DocumentChunkRepository;
import com.example.document_assistant.repository.DocumentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class QuestionService {

    private static final int MAX_CONTEXT_CHUNKS = 6;
    private static final int MAX_SOURCE_REFERENCES = 3;
    private static final int MAX_EXCERPT_LENGTH = 350;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "is", "a", "an", "and", "or", "but",
            "in", "on", "at", "to", "for", "of", "with",
            "by", "from", "as", "it", "this", "that",
            "these", "those", "be", "are", "was", "were",
            "what", "which", "who", "whom", "when", "where",
            "why", "how", "about", "tell", "me", "please",
            "can", "could", "would", "do", "does", "did",
            "explain", "describe", "give", "show", "find",
            "define", "meaning", "according", "document",
            "context", "following", "difference", "java",
            "definition", "process", "explaination"
    );

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final AIService aiService;

    public QuestionService(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            AIService aiService) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.aiService = aiService;
    }

    @Transactional(readOnly = true)
    public QuestionResponse askQuestion(
            Long documentId,
            QuestionRequest request) {

        if (documentId == null) {
            throw new IllegalArgumentException(
                    "Document ID must not be null.");
        }

        if (request == null
                || request.getQuestion() == null
                || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException(
                    "Question must not be empty.");
        }

        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document not found: " + documentId));

        List<DocumentChunk> chunks =
                documentChunkRepository.findByDocumentId(documentId);

        if (chunks == null || chunks.isEmpty()) {
            return new QuestionResponse(
                    "No readable text was found in this document.",
                    List.of());
        }

        String question = request.getQuestion().trim();
        Set<String> questionTerms = meaningfulWords(question);

        if (questionTerms.isEmpty()) {
            return new QuestionResponse(
                    "Please ask a question about the document.",
                    List.of());
        }

        List<DocumentChunk> validChunks = chunks.stream()
                .filter(Objects::nonNull)
                .filter(chunk -> chunk.getContent() != null)
                .filter(chunk -> !chunk.getContent().isBlank())
                .filter(chunk -> chunk.getPageNumber() != null)
                .filter(chunk -> chunk.getPageNumber() > 0)
                .toList();

        if (validChunks.isEmpty()) {
            return new QuestionResponse(
                    "No readable text was found in this document.",
                    List.of());
        }

        Map<String, Integer> documentFrequency = new HashMap<>();

        for (DocumentChunk chunk : validChunks) {
            for (String term : meaningfulWords(chunk.getContent())) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        List<ScoredChunk> rankedChunks = validChunks.stream()
                .map(chunk -> new ScoredChunk(
                        chunk,
                        scoreChunk(
                                chunk.getContent(),
                                questionTerms,
                                documentFrequency,
                                validChunks.size())))
                .filter(item -> item.score() > 0)
                .sorted(
                        Comparator.comparingDouble(ScoredChunk::score)
                                .reversed())
                .limit(MAX_CONTEXT_CHUNKS)
                .toList();

        if (rankedChunks.isEmpty()) {
            return new QuestionResponse(
                    "I couldn't find a relevant passage in this document "
                            + "to answer your question.",
                    List.of());
        }

        String context = rankedChunks.stream()
                .map(item -> "[Page "
                        + item.chunk().getPageNumber()
                        + "]\n"
                        + item.chunk().getContent())
                .collect(Collectors.joining("\n\n"));

        AIService.AnswerResult result =
                aiService.askQuestionWithSources(question, context);

        if (result == null
                || result.answer() == null
                || result.answer().isBlank()) {
            return new QuestionResponse(
                    "I couldn't generate an answer from this document.",
                    List.of());
        }

        List<SourceReference> sources = buildSources(
                document,
                rankedChunks,
                result.passages(),
                question,
                result.answer());

        return new QuestionResponse(result.answer(), sources);
    }

    private double scoreChunk(
            String content,
            Set<String> questionTerms,
            Map<String, Integer> documentFrequency,
            int totalChunks) {

        Set<String> contentTerms = meaningfulWords(content);
        double score = 0;
        int matches = 0;

        for (String term : questionTerms) {
            if (!contentTerms.contains(term)) {
                continue;
            }

            matches++;

            int frequency = documentFrequency.getOrDefault(term, 1);

            score += Math.log(
                    1.0 + (double) totalChunks / frequency);
        }

        if (!questionTerms.isEmpty()) {
            score += 2.0 * matches / questionTerms.size();
        }

        return score;
    }

    private List<SourceReference> buildSources(
            DocumentEntity document,
            List<ScoredChunk> rankedChunks,
            List<AIService.MatchedPassage> aiPassages,
            String question,
            String answer) {

        List<SourceReference> sources = new ArrayList<>();

        Set<String> questionTerms = meaningfulWords(question);
        Set<String> answerTerms = meaningfulWords(answer);
        boolean definitionQuestion = asksForDefinition(question);

        List<ScoredSentence> candidates = new ArrayList<>();
        List<ScoredSentence> verifiedAiCandidates = new ArrayList<>();

        /*
         * Build candidate citations from sentences in the actual PDF text.
         */
        for (ScoredChunk item : rankedChunks) {
            String content = item.chunk().getContent();

            for (String sentence : extractSentences(content)) {
                if (!isUsefulExcerpt(sentence)) {
                    continue;
                }

                Set<String> sentenceTerms = meaningfulWords(sentence);

                double answerScore =
                        termCoverage(sentenceTerms, answerTerms);
                double questionScore =
                        termCoverage(sentenceTerms, questionTerms);

                boolean definitionSentence =
                        looksLikeDefinition(sentence);

                /*
                 * Relaxed relevance filter: allow additional explanatory
                 * passages instead of requiring every source to be a
                 * definition. Still reject passages with very little
                 * overlap with both the question and answer.
                 */
                if (answerScore < 0.15 && questionScore < 0.15) {
                    continue;
                }

                double score =
                        answerScore * 0.65 + questionScore * 0.35;

                if (definitionQuestion && definitionSentence) {
                    score += 0.50;
                }

                candidates.add(new ScoredSentence(
                        item.chunk().getPageNumber(),
                        sentence,
                        score));
            }
        }

        /*
         * Validate AI-proposed citations against the actual page text.
         */
        if (aiPassages != null) {
            for (AIService.MatchedPassage passage : aiPassages) {
                if (passage == null
                        || passage.pageNumber() == null
                        || passage.pageNumber() < 1
                        || passage.text() == null
                        || passage.text().isBlank()) {
                    continue;
                }

                for (ScoredChunk item : rankedChunks) {
                    if (!Objects.equals(
                            item.chunk().getPageNumber(),
                            passage.pageNumber())) {
                        continue;
                    }

                    String matchedSentence = findMatchingText(
                            item.chunk().getContent(),
                            passage.text());

                    if (matchedSentence == null
                            || !isUsefulExcerpt(matchedSentence)) {
                        continue;
                    }

                    Set<String> terms = meaningfulWords(matchedSentence);

                    double answerScore =
                            termCoverage(terms, answerTerms);
                    double questionScore =
                            termCoverage(terms, questionTerms);

                    if (answerScore < 0.15 && questionScore < 0.15) {
                        continue;
                    }

                    double score =
                            answerScore * 0.65 + questionScore * 0.35;

                    if (definitionQuestion
                            && looksLikeDefinition(matchedSentence)) {
                        score += 0.50;
                    }

                    verifiedAiCandidates.add(new ScoredSentence(
                            passage.pageNumber(),
                            matchedSentence,
                            score + 0.01));
                }
            }
        }

        /*
         * Prefer the best matching citations first.
         */
        verifiedAiCandidates.sort(
                Comparator.comparingDouble(ScoredSentence::score)
                        .reversed());

        candidates.sort(
                Comparator.comparingDouble(ScoredSentence::score)
                        .reversed());

        for (ScoredSentence candidate : verifiedAiCandidates) {
            addUniqueSource(sources, document, candidate);

            if (sources.size() >= MAX_SOURCE_REFERENCES) {
                return sources;
            }
        }

        for (ScoredSentence candidate : candidates) {
            addUniqueSource(sources, document, candidate);

            if (sources.size() >= MAX_SOURCE_REFERENCES) {
                break;
            }
        }

        return sources;
    }

    private String findMatchingText(
            String content,
            String requestedExcerpt) {

        if (content == null || requestedExcerpt == null) {
            return null;
        }

        String normalizedRequested = normalize(requestedExcerpt);

        if (normalizedRequested.length() < 25) {
            return null;
        }

        List<String> actualSentences = extractSentences(content);

        /*
         * Return a complete sentence from the original PDF if that
         * sentence is contained in the AI-proposed excerpt.
         */
        for (String actualSentence : actualSentences) {
            String normalizedActual = normalize(actualSentence);

            if (normalizedActual.length() >= 25
                    && normalizedRequested.contains(normalizedActual)
                    && isUsefulExcerpt(actualSentence)) {
                return actualSentence.trim();
            }
        }

        /*
         * Otherwise find a complete sentence matching the proposed text.
         */
        for (String requestedSentence :
                extractSentences(requestedExcerpt)) {

            String normalizedSentence = normalize(requestedSentence);

            if (normalizedSentence.length() < 25) {
                continue;
            }

            for (String actualSentence : actualSentences) {
                if (normalize(actualSentence).equals(normalizedSentence)
                        && isUsefulExcerpt(actualSentence)) {
                    return actualSentence.trim();
                }
            }
        }

        return null;
    }

    private List<String> extractSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String cleaned = text.replaceAll("\\s+", " ");

        List<String> sentences = new ArrayList<>();

        for (String part : cleaned.split("(?<=[.!?])\\s+")) {
            String candidate = part.trim();

            if (candidate.isBlank()) {
                continue;
            }

            /*
             * Try to isolate a definition when PDF extraction places
             * a heading or code immediately before it.
             */
            Matcher matcher = Pattern.compile(
                    "(?i)([A-Z][A-Za-z0-9 ]{1,45}?\\s+"
                            + "(?:is|refers to|can be defined as)\\s+"
                            + ".+?(?:[.!?](?=\\s|$)|$))")
                    .matcher(candidate);

            boolean foundDefinition = false;

            while (matcher.find()) {
                String definition = matcher.group(1).trim();

                if (definition.length() >= 25
                        && meaningfulWords(definition).size() >= 4) {
                    sentences.add(definition);
                    foundDefinition = true;
                }
            }

            if (!foundDefinition) {
                sentences.add(candidate);
            }
        }

        return sentences.stream()
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .distinct()
                .toList();
    }

    private boolean asksForDefinition(String question) {
        String q = question.toLowerCase(Locale.ROOT);

        return q.matches(".*\\bwhat\\s+is\\b.*")
                || q.matches(".*\\bwhat\\s+are\\b.*")
                || q.matches(".*\\bdefine\\b.*")
                || q.matches(".*\\bdefinition\\s+of\\b.*")
                || q.matches(".*\\bmeaning\\s+of\\b.*");
    }

    private boolean looksLikeDefinition(String sentence) {
        String s = normalize(sentence);

        return s.matches(".*\\bis\\s+(a|an|the)\\s+.*")
                || s.contains(" refers to ")
                || s.contains(" is defined as ")
                || s.contains(" can be defined as ")
                || s.contains(" is the process of ")
                || s.contains(" is the ability to ");
    }

    private boolean isUsefulExcerpt(String text) {
        if (text == null) {
            return false;
        }

        String cleaned = text.replaceAll("\\s+", " ").trim();

        if (cleaned.length() < 25
                || cleaned.matches("(?i)^\\[?page\\s+\\d+\\]?$")
                || cleaned.endsWith("...")
                || cleaned.endsWith("…")) {
            return false;
        }

        long codeSymbols = cleaned.chars()
                .filter(ch -> "{}();=".indexOf(ch) >= 0)
                .count();

        if (codeSymbols > 12
                && codeSymbols > cleaned.length() / 10) {
            return false;
        }

        return meaningfulWords(cleaned).size() >= 3;
    }

    private double termCoverage(
            Set<String> sentenceTerms,
            Set<String> targetTerms) {

        if (sentenceTerms.isEmpty() || targetTerms.isEmpty()) {
            return 0;
        }

        long matches = targetTerms.stream()
                .filter(sentenceTerms::contains)
                .count();

        return (double) matches / targetTerms.size();
    }

    private void addUniqueSource(
            List<SourceReference> sources,
            DocumentEntity document,
            ScoredSentence candidate) {

        if (sources.size() >= MAX_SOURCE_REFERENCES) {
            return;
        }

        SourceReference source = new SourceReference(
                document.getFilename(),
                candidate.pageNumber(),
                makeExcerpt(candidate.text()));

        boolean duplicate = sources.stream().anyMatch(existing ->
                Objects.equals(
                        existing.getPageNumber(),
                        source.getPageNumber())
                && normalize(existing.getExcerpt())
                        .equals(normalize(source.getExcerpt())));

        if (!duplicate) {
            sources.add(source);
        }
    }

    private Set<String> meaningfulWords(String text) {
        Set<String> words = new LinkedHashSet<>();

        if (text == null || text.isBlank()) {
            return words;
        }

        for (String word : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", " ")
                .split("\\s+")) {

            if (word.length() > 1 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }

        return words;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private String makeExcerpt(String text) {
        if (text == null) {
            return "";
        }

        String cleaned = text.replaceAll("\\s+", " ").trim();

        if (cleaned.length() <= MAX_EXCERPT_LENGTH) {
            return cleaned;
        }

        int limit = Math.min(
                MAX_EXCERPT_LENGTH, cleaned.length());

        int sentenceEnd = -1;

        for (int i = 0; i < limit; i++) {
            char ch = cleaned.charAt(i);

            if ((ch == '.' || ch == '!' || ch == '?')
                    && (i + 1 == cleaned.length()
                    || Character.isWhitespace(cleaned.charAt(i + 1)))) {
                sentenceEnd = i + 1;
            }
        }

        if (sentenceEnd > 0) {
            return cleaned.substring(0, sentenceEnd).trim();
        }

        int wordEnd = cleaned.lastIndexOf(' ', limit);

        if (wordEnd < limit / 2) {
            wordEnd = limit;
        }

        return cleaned.substring(0, wordEnd).trim();
    }

    private record ScoredChunk(
            DocumentChunk chunk,
            double score) {
    }

    private record ScoredSentence(
            Integer pageNumber,
            String text,
            double score) {
    }
}
