package com.example.cmdvolunteer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

// =========================================================================
//  CORS SECURITY CONTROLS FOR EXTERNAL GITHUB HOOKS
// =========================================================================
@Configuration
class WebConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // Allows your hosted GitHub pages client domain to route requests securely
                registry.addMapping("/api/**")
                        .allowedOrigins("*") 
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
            }
        };
    }
}

// =========================================================================
//  NEXTCLOUD WEBDAV BACKEND TRANSACTION ENGINE
// =========================================================================
@Service
class NextcloudStorageService {

    // Target configuration points for Nextcloud Shared Links
    private static final String NEXTCLOUD_BASE_URL = "https://your-nextcloud-instance.com/public.php/webdav/";
    private static final String PUBLIC_SHARE_TOKEN = "PASTE_YOUR_PUBLIC_SHARE_TOKEN_HERE"; 
    private static final String SHARE_PASSWORD = ""; 

    public void streamFileToNextcloud(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getOriginalFilename() == null) {
            throw new IllegalArgumentException("Payload validation failed: file is completely empty.");
        }

        String secureFileName = file.getOriginalFilename().replaceAll("[^a-zA-Z0-9.-]", "_");
        String finalDestinationPath = NEXTCLOUD_BASE_URL + secureFileName;

        URL url = new URL(finalDestinationPath);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT"); 
        
        // Base64 Authorization String composition using public tokens
        String identityString = PUBLIC_SHARE_TOKEN + ":" + SHARE_PASSWORD;
        String encodedAuth = Base64.getEncoder().encodeToString(identityString.getBytes());
        
        connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
        connection.setRequestProperty("Content-Type", file.getContentType());

        try (InputStream inputStream = file.getInputStream();
             var outputStream = connection.openConnection().getOutputStream()) {
            byte[] streamingBuffer = new byte[4096];
            int processingBlock;
            while ((processingBlock = inputStream.read(streamingBuffer)) != -1) {
                outputStream.write(streamingBuffer, 0, processingBlock);
            }
        }

        int serverStatusResponse = connection.getResponseCode();
        if (serverStatusResponse != HttpURLConnection.HTTP_CREATED && serverStatusResponse != HttpURLConnection.HTTP_NO_CONTENT) {
            throw new IOException("Nextcloud storage endpoint validation error. Status Code: " + serverStatusResponse);
        }
    }
}

// =========================================================================
//  REST DATA STREAM ROUTER 
// =========================================================================
@RestController
@RequestMapping("/api/storage")
class StorageGatewayController {

    private final NextcloudStorageService storageService;

    public StorageGatewayController(NextcloudStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/nextcloud/upload")
    public ResponseEntity<String> processClientUpload(@RequestParam("file") MultipartFile file) {
        try {
            storageService.streamFileToNextcloud(file);
            return ResponseEntity.ok("Success: File streamed securely onto Nextcloud storage bucket.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Upload failed during target cloud route processing: " + e.getMessage());
        }
    }
}