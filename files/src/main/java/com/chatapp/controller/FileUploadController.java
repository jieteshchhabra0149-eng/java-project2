package com.chatapp.controller;

import com.chatapp.entity.UploadedImage;
import com.chatapp.repository.UploadedImageRepository;
import com.chatapp.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
public class FileUploadController {

    private final UploadedImageRepository repository;
    private final AuthService authService;
    private final String uploadDir = new File("uploads").getAbsolutePath();

    public FileUploadController(UploadedImageRepository repository, AuthService authService) {
        this.repository = repository;
        this.authService = authService;
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Empty file"));
            }

            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf("."));
            }
            String filename = UUID.randomUUID().toString() + extension;
            File targetFile = new File(uploadDir, filename);
            file.transferTo(targetFile);

            String url = "/uploads/" + filename;
            String uploader = authService.currentUser().map(u -> u.getUsername()).orElse("Guest");

            UploadedImage image = new UploadedImage();
            image.setFilename(filename);
            image.setUrl(url);
            image.setUploader(uploader);
            repository.save(image);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "url", url
            ));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
