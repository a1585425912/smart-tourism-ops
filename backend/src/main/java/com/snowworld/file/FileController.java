package com.snowworld.file;

import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('INSPECTION_EXECUTE', 'INSPECTION_MANAGE', 'INSPECTION_REVIEW')")
    public FileRef upload(@RequestParam("file") MultipartFile file, @RequestParam long siteId,
                          @RequestParam String bizType, @RequestParam(required = false) Long bizId) {
        return fileService.upload(file, siteId, bizType, bizId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        FileMapper.FileRow row = fileService.requireReadable(id);
        Path path = fileService.locate(row);
        String ext = row.getObjectKey().substring(row.getObjectKey().lastIndexOf('.') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getContentType()))
                .contentLength(row.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("file-" + row.getId() + "." + ext).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new FileSystemResource(path));
    }
}
