package com.example.rag.resume;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

@Service
public class ResumeFileService {

    @Value("${app.resumes.path:./downloaded-resumes}")
    private String resumesPath;

    public Path resolveResumePath(String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }
        Path base = Path.of(resumesPath).toAbsolutePath().normalize();
        Path candidate = base.resolve(filename).normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException("Invalid filename path");
        }
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            throw new NoSuchFileException(filename);
        }
        return candidate;
    }
}
