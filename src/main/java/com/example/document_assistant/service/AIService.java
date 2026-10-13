

package com.example.document_assistant.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AIService {

    private static final int MAX_SOURCES = 3;

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
            "context"
    );

    private static final Pattern PAGE_PATTERN =
            Pattern.compile("\\[Page (\\d+)\\]");

    private static final Pattern SENTENCE_PATTERN =
            Pattern.compile("(?<=[.!?])\\s+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.model:llama3.2}")
    private String model;

    @Value("${app.ai.mock-mode:false}")
    private boolean mockMode;

    public AIService(
            @Value("${app.ai.base-url:http://localhost:11434}") String baseUrl,
            ObjectMapper objectMapper) {

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.objectMapper = objectMapper;
    }

    public AnswerResult askQuestionWithSources(
            String question,
            String documentContext) {

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Question cannot be empty.");
        }

        if (documentContext == null || documentContext.isBlank()) {
            return new AnswerResult(
                    "I could not find relevant information in this document.",
                    List.of());
        }

        if (mockMode) {
            return fallbackAnswer(question, documentContext);
        }

        String prompt = """
                Answer the user's question using ONLY the supplied PDF context.

                Return ONLY valid JSON in this format:
                {
                  "answer": "Your concise answer",
                  "sources": [
                    {
                      "pageNumber": 1,
                      "excerpt": "An exact quote copied from the context"
                    }
                  ]
                }

                Rules:
                - Include up to 3 relevant sources.
                - pageNumber must match a [Page N] label exactly.
                - excerpt must be copied exactly from that page's context.
                - Each source must directly support the answer.
                - Do not invent facts, page numbers, or quotes.
                - If the context does not answer the question, explain that
                  and return an empty sources array.
                - Return JSON only, without Markdown fences.

                PDF CONTEXT:
                %s

                QUESTION:
                %s
                """.formatted(documentContext, question);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", model,
                            "stream", false,
                            "format", "json",
                            "options", Map.of("temperature", 0.1),
                            "messages", List.of(
                                    Map.of(
                                            "role", "user",
                                            "content", prompt))))
                    .retrieve()
                    .body(Map.class);

            if (response == null
                    || !(response.get("message") instanceof Map<?, ?> message)) {
                return fallbackAnswer(question, documentContext);
            }

            Object content = message.get("content");

            if (!(content instanceof String json) || json.isBlank()) {
                return fallbackAnswer(question, documentContext);
            }

            JsonNode root = objectMapper.readTree(json);
            String answer = root.path("answer").asText("").trim();

            if (answer.isBlank()) {
                return fallbackAnswer(question, documentContext);
            }

            List<MatchedPassage> verifiedSources =
                    verifySources(root.path("sources"), documentContext);

            return new AnswerResult(answer, verifiedSources);

        } catch (RestClientException
                 | IllegalStateException exception) {
            return fallbackAnswer(question, documentContext);

        } catch (Exception exception) {
            return fallbackAnswer(question, documentContext);
        }
    }

    private List<MatchedPassage> verifySources(
            JsonNode sources,
            String documentContext) {

        List<MatchedPassage> verified = new ArrayList<>();

        if (sources == null || !sources.isArray()) {
            return verified;
        }

        for (JsonNode source : sources) {
            if (verified.size() >= MAX_SOURCES) {
                break;
            }

            JsonNode pageNode = source.path("pageNumber");

            if (!pageNode.canConvertToInt()) {
                continue;
            }

            int pageNumber = pageNode.asInt();
            String excerpt = source.path("excerpt").asText("").trim();

            if (pageNumber < 1 || excerpt.isBlank()) {
                continue;
            }

            String pageText = findPageText(
                    documentContext,
                    pageNumber);

            if (pageText == null || !containsNormalized(pageText, excerpt)) {
                continue;
            }

            boolean duplicate = verified.stream().anyMatch(existing ->
                    existing.pageNumber() == pageNumber
                            && normalize(existing.text())
                            .equals(normalize(excerpt)));

            if (!duplicate) {
                verified.add(new MatchedPassage(pageNumber, excerpt));
            }
        }

        return List.copyOf(verified);
    }

    private String findPageText(
            String context,
            int requestedPageNumber) {

        Matcher matcher = PAGE_PATTERN.matcher(context);

        while (matcher.find()) {
            int currentPage;

            try {
                currentPage = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                continue;
            }

            if (currentPage != requestedPageNumber) {
                continue;
            }

            int start = matcher.end();
            int end = matcher.find() ? matcher.start() : context.length();

            return context.substring(start, end).trim();
        }

        return null;
    }

    private boolean containsNormalized(
            String originalText,
            String quotedText) {

        String normalizedOriginal = normalize(originalText);
        String normalizedQuote = normalize(quotedText);

        return !normalizedQuote.isBlank()
                && normalizedOriginal.contains(normalizedQuote);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private AnswerResult fallbackAnswer(
            String question,
            String documentContext) {

        Set<String> questionTerms = meaningfulWords(question);

        if (questionTerms.isEmpty()) {
            return new AnswerResult(
                    "Please ask a question about the document.",
                    List.of());
        }

        List<MatchedPassage> passages = new ArrayList<>();

        Matcher pageMatcher = PAGE_PATTERN.matcher(documentContext);
        List<PageText> pages = new ArrayList<>();

        while (pageMatcher.find()) {
            int pageNumber;

            try {
                pageNumber = Integer.parseInt(pageMatcher.group(1));
            } catch (NumberFormatException exception) {
                continue;
            }

            int start = pageMatcher.end();
            int end = pageMatcher.find()
                    ? pageMatcher.start()
                    : documentContext.length();

            String pageText = documentContext.substring(start, end).trim();

            if (!pageText.isBlank()) {
                pages.add(new PageText(pageNumber, pageText));
            }
        }

        List<ScoredSentence> candidates = new ArrayList<>();

        for (PageText page : pages) {
            for (String part : SENTENCE_PATTERN.split(page.text())) {
                String sentence = part.trim();

                if (sentence.length() < 20) {
                    continue;
                }

                Set<String> sentenceTerms = meaningfulWords(sentence);
                int matches = 0;

                for (String term : questionTerms) {
                    if (sentenceTerms.contains(term)) {
                        matches++;
                    }
                }

                if (matches == 0) {
                    continue;
                }

                double coverage =
                        (double) matches / questionTerms.size();

                candidates.add(new ScoredSentence(
                        page.pageNumber(),
                        sentence,
                        coverage,
                        matches));
            }
        }

        candidates.sort((first, second) -> {
            int coverageComparison = Double.compare(
                    second.coverage(), first.coverage());

            if (coverageComparison != 0) {
                return coverageComparison;
            }

            return Integer.compare(
                    second.matchCount(), first.matchCount());
        });

        for (ScoredSentence candidate : candidates) {
            if (passages.size() >= MAX_SOURCES) {
                break;
            }

            boolean duplicate = passages.stream().anyMatch(existing ->
                    existing.pageNumber().equals(candidate.pageNumber())
                            && normalize(existing.text())
                            .equals(normalize(candidate.text())));

            if (!duplicate) {
                passages.add(new MatchedPassage(
                        candidate.pageNumber(),
                        candidate.text()));
            }
        }

        if (passages.isEmpty()) {
            return new AnswerResult(
                    "I could not find sufficient information in this "
                            + "document to answer that question.",
                    List.of());
        }

        String answer = passages.get(0).text();

        return new AnswerResult(answer, List.copyOf(passages));
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

    private record PageText(
            int pageNumber,
            String text) {
    }

    private record ScoredSentence(
            int pageNumber,
            String text,
            double coverage,
            int matchCount) {
    }

    public record MatchedPassage(
            Integer pageNumber,
            String text) {
    }

    public record AnswerResult(
            String answer,
            List<MatchedPassage> passages) {
    }
}
