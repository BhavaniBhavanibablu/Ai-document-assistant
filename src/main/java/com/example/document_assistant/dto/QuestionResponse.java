
package com.example.document_assistant.dto;

import java.util.List;

public class QuestionResponse {

    private String answer;
    private List<SourceReference> sources;

    public QuestionResponse() {
    }

    public QuestionResponse(String answer, List<SourceReference> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<SourceReference> getSources() {
        return sources;
    }

    public void setSources(List<SourceReference> sources) {
        this.sources = sources;
    }
}
