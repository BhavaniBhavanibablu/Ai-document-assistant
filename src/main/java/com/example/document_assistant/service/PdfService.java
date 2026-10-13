package com.example.document_assistant.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfService {

    public List<String> extractTextByPage(MultipartFile file)
            throws IOException {

        List<String> pages = new ArrayList<>();

        try (PDDocument document =
                     Loader.loadPDF(file.getBytes())) {

            PDFTextStripper stripper = new PDFTextStripper();

            for (int page = 1;
                 page <= document.getNumberOfPages();
                 page++) {

                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String text = stripper.getText(document).trim();

                if (!text.isEmpty()) {
                    pages.add(text);
                }
            }
        }

        if (pages.isEmpty()) {
            throw new IllegalArgumentException(
                    "No readable text found in the PDF."
            );
        }

        return pages;
    }
}
