
package com.example.document_assistant.dto;

public class SourceReference {

    private String filename;
    private int pageNumber;
    private String excerpt;

    public SourceReference() {
    }

    public SourceReference(String filename, int pageNumber, String excerpt) {
        this.filename = filename;
        this.pageNumber = pageNumber;
        this.excerpt = excerpt;
    }

    public String getFilename() {
        return filename;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getExcerpt() {
        return excerpt;
    }
}
