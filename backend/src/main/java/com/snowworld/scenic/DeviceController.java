package com.snowworld.scenic;

import com.snowworld.auth.ScopeService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final DeviceMapper deviceMapper;
    private final PoiMapper poiMapper;
    private final ScopeService scope;
    private final VideoService videoService;

    public DeviceController(DeviceMapper deviceMapper, PoiMapper poiMapper, ScopeService scope, VideoService videoService) {
        this.deviceMapper = deviceMapper;
        this.poiMapper = poiMapper;
        this.scope = scope;
        this.videoService = videoService;
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('DEVICE_READ')")
    public List<DeviceMapper.DeviceRow> devices(@RequestParam(required = false) List<Long> siteId,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Boolean keyPoint) {
        List<Long> sites = scope.resolve(siteId);
        return sites.isEmpty() ? List.of() : deviceMapper.find(sites, type, status, keyPoint);
    }

    @GetMapping("/devices/status-summary")
    @PreAuthorize("hasAuthority('DEVICE_READ')")
    public List<DeviceMapper.StatusCount> statusSummary(@RequestParam(required = false) List<Long> siteId) {
        List<Long> sites = scope.resolve(siteId);
        return sites.isEmpty() ? List.of() : deviceMapper.statusSummary(sites);
    }

    @PostMapping("/devices/{id}/playback-session")
    @PreAuthorize("hasAuthority('VIDEO_VIEW')")
    public VideoService.PlaybackSession playback(@PathVariable long id) {
        return videoService.open(id);
    }

    @GetMapping("/video/sessions/{token}/media")
    @PreAuthorize("hasAuthority('VIDEO_VIEW')")
    public ResponseEntity<Resource> media(@PathVariable String token) {
        Path file = videoService.resolve(token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4")).body(new FileSystemResource(file));
    }

    @GetMapping("/map/pois")
    @PreAuthorize("hasAuthority('SITE_READ')")
    public List<PoiMapper.PoiRow> pois(@RequestParam(required = false) List<Long> siteId,
                                       @RequestParam(required = false) String category) {
        List<Long> sites = scope.resolve(siteId);
        return sites.isEmpty() ? List.of() : poiMapper.find(sites, category);
    }
}
