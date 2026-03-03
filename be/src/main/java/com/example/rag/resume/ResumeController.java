package com.example.rag.resume;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeFileService resumeFileService;

    public ResumeController(ResumeFileService resumeFileService) {
        this.resumeFileService = resumeFileService;
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<?> getResume(
            @PathVariable String filename,
            @RequestParam(defaultValue = "false") boolean download) {
        try {
            Path path = resumeFileService.resolveResumePath(filename);
            byte[] bytes = Files.readAllBytes(path);
            Resource resource = new ByteArrayResource(bytes);
            ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                    .filename(path.getFileName().toString())
                    .build();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(bytes.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(resource);
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to read resume"));
        }
    }
}
